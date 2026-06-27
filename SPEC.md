# TagDrop Encoding Specification

**Version:** 1  
**Status:** Draft — version 1 has no real-world deployments yet (no printed
or distributed codes), so it may still change incompatibly without a version
bump. Once the first real code ships, that freeze point ends: breaking
changes from then on require a version bump (§14). Feedback welcome via
GitHub issues.

---

## 1. Overview

TagDrop encodes self-contained digital content into one or more 2D barcodes (QR, Aztec, Data Matrix) for physical placement — on walls, along trails, inside objects. A finder scans the code(s) with the TagDrop app (or any QR reader that understands the URI scheme) to retrieve the content.

The format is designed to:

- Fit rich content (HTML pages, images, audio snippets, plain text) into one or more standard QR codes
- Support **geographic distribution**: chunks of a large payload can be placed at different physical locations, so finding all pieces is part of the experience
- Be **future-proof**: versioned, binary-compact, with reserved fields for compression and future extensions
- Remain **backward-compatible** with the legacy `data:` URI approach
- Be **transport-agnostic**: the same CBOR sequence can be carried by QR codes, NFC NDEF tags, or other 2D barcode formats (Aztec, JABCode)

---

## 2. URI Scheme

All TagDrop codes use the URI scheme `tagdrop:` so any QR scanner routes them to the app. The URI doubles as an intent deep-link on Android.

```
tagdrop:<base41-cbor-sequence>
```

`<base41-cbor-sequence>` is a **Base41** encoding of a short **CBOR Sequence** ([RFC 8742](https://www.rfc-editor.org/rfc/rfc8742) — concatenated CBOR data items, no enclosing array or map). Every code — whether it stands alone or is one of several pieces of a larger payload — is a **sector**, carrying the same four-item envelope:

```
CBOR(version) || CBOR(type) || CBOR(part_meta) || CBOR(sector_bytes)
```

Laid out as a sequence of four concatenated items:

```
┌──────────┬──────────┬────────────┬───────────────┐
│ version  │ type     │ part_meta  │ sector_bytes  │
│ (1 byte) │ (1 byte) │ (CBOR map) │ (byte string) │
└──────────┴──────────┴────────────┴───────────────┘
```

| Item | Type | Meaning |
|---|---|---|
| `version` | uint | Format version. Currently `1`. |
| `type` | uint | Payload kind — see table below. |
| `part_meta` | map | Per-sector bookkeeping — addressing, position, parity. See §4.1. |
| `sector_bytes` | bytes | This sector's slice of the reassembled stream. See §4. |

| `type` | Payload |
|---|---|
| 0 | Content — a cache (file, page, snippet) of any size, one or more sectors |
| 1 | Paper — directory of files on a physical paper, one or more sectors |

A small piece of content that fits in one code is simply a `type` 0 payload whose `part_meta` has `sector_count` 1 — there's no separate "Single" type. A Paper that needs only one code likewise has `sector_count` 1. Splitting across more codes never changes `type`, only `part_meta`'s sector fields — see §4.1 and §5.

For values 0–23, a CBOR unsigned integer is exactly **one byte** (RFC 8949 major type 0, value packed into the initial byte). So `version` and `type` together still cost just **2 bytes**, same as every sector under the previous design.

### Navigation links (not QR payloads)

HTML pages embedded in TagDrop caches can link to other files and papers using:

```
tagdrop://<rootHash-hex>/<slug>
```

`rootHash` is the Paper's `root_hash` (§4.4) — the 8-byte SHA-256 of its reassembled stream (`core_meta_item || bulky_meta_item || content`, §4.2) — lowercase-hex-encoded (16 characters). `slug` is the file's identifier within that paper. The TagDrop app intercepts these links in its WebView and resolves them against the local scanned-paper database — no network needed. A human-readable **domain name** may be used in place of `<rootHash-hex>` — see "Domains" in §7.

**Why hex, not Base41, here?** Base41's alphabet includes `:`, which is fine inside the *path* of an opaque URI like `tagdrop:<base41-cbor-sequence>` but not inside a URL's *authority* (host[:port]) component — a `:` there starts the port subcomponent per the WHATWG URL Standard, and anything after it that isn't a bare port number is a hard parse failure for the whole URL. Since the root hash sits in the authority position of a `tagdrop://` link, a Base41-encoded root hash containing `:` (roughly 1 in 4, since `:` is 1 of 41 alphabet characters) would make the link unparseable. Plain lowercase hex has no such character, at the cost of 4 extra characters (16 hex vs 12 Base41 for 8 bytes) — cheap, since navigation links are clicked/typed, not scanned from a QR code.

**Disambiguation:** encoding payloads never contain `//` — `tagdrop:<base41-cbor-sequence>` has no authority component. Navigation links always do — the root hash serves as the link's authority. Base41's alphabet has no `/` character at all, so it can never appear anywhere in a Base41-encoded string, let alone right after the scheme. Encoding URIs and navigation links are therefore unambiguously distinguishable by whether `//` follows the scheme.

**Why Base41?** QR codes have an alphanumeric mode (charset 0–9, A–Z, space, `$%*+-./:`, 45 characters) that stores 5.5 bits per character vs 8 bits per character in binary mode. RFC 9285's Base45 uses that full 45-character set, encoding 2 bytes → 3 alphanumeric characters (~3% overhead over QR's alphanumeric capacity) — far better than Base64 (33% overhead, and forces binary mode since Base64 needs lowercase letters). TagDrop uses **Base41**: the same 2-bytes-to-3-characters packing as Base45, but over a 41-character subset of the QR alphanumeric set — `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$*-.:` — that drops the 4 characters which cause trouble outside QR codes: space and `%` aren't valid unescaped in a URI (RFC 3986), and `+`/`/` carry special meaning in URLs (`+` as a space in query strings, `/` as a path separator). 41 is the smallest alphabet for which 3 characters can still represent every 16-bit value (41³ = 68921 ≥ 65536; 40³ = 64000 does not), so this costs nothing — Base41 output is exactly the same length as Base45 output would be for the same bytes. The result is a string that's always a strictly valid, unescaped URI component, with no percent-encoding step needed on either side. This alphabet is also a safe (if non-optimal) subset of Data Matrix's C40 mode and Aztec's Upper/Digit modes, so the same encoded string stays reasonably dense on those carriers too (§13).

**Credit:** TagDrop's Base41 builds on two independent lines of prior art. The
base scheme — packing 2 bytes into 3 characters, exactly like RFC 9285's
Base45 but over a smaller alphabet — was created and placed in the public
domain in 2014 by GitHub user [sveljko](https://github.com/sveljko/base41),
whose repo also ships an independent reference encoder/decoder and test
vectors (`base41_test.go`) — useful for cross-checking any new
implementation of this format. That repo's "BYOA" (bring-your-own-alphabet)
variant — swapping the fixed 41-character alphabet for a use-case-specific
one — was proposed by Philippe Majerus
([PhMajerus](https://github.com/PhMajerus)), who suggested exactly the
QR/URL-safe alphabet TagDrop uses here:
`0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$*-.:`. Botta and Cavagnino
independently arrived at the same idea and formalized it in a peer-reviewed
paper, [*"Base41: A proposal for printable encoding of bit
strings"*](https://doi.org/10.1002/eng2.12606) (Engineering Reports, 2022),
which cites sveljko's repo as prior art and discusses the same URL-safe BYOA
alphabet choice — useful further reading on the reasoning behind this
approach. There's no RFC for this specific alphabet — TagDrop defines it
here as its own encoding, reusing Base45's well-understood packing
algorithm.

**Case:** encoders MUST emit uppercase letters only. Decoders MUST accept lowercase letters as equivalent to their uppercase counterparts (case-insensitive decode) — this tolerates content that's been manually retyped (e.g. a `tagdrop://` link copied by hand), since `$*-.:` and the digits have no case to confuse and the QR alphanumeric mode itself is uppercase-only by convention.

**Why CBOR?** CBOR (RFC 8949) is binary JSON: self-describing, compact, standardised, and easy to parse without a schema. It is 20–50% smaller than JSON for typical payloads. Integer map keys (used here) are 1 byte each. CBOR Sequences (RFC 8742) let the `version`/`type` envelope reuse the same compact integer encoding, with no extra framing.

---

## 3. CBOR Map Keys

TagDrop's wire format has four internal CBOR structures, all integer-keyed maps (or, for content, a structure built around one). Unknown keys must be ignored (forward compatibility).

- **`part_meta`** — one per sector, carried in the envelope alongside `sector_bytes` (§2, §4.1). Addressing and position: which content this sector belongs to, where it sits in the sequence, how many sectors to expect.
- **`core_meta_item`** — the first item of the reassembled stream (§4.2). Always small and uncompressed: identity/preview fields, plus declarations describing the two items that follow.
- **`bulky_meta_item`** — the second item of the reassembled stream. Whatever doesn't need to be in the early preview but isn't raw content: directories, related-paper hints, large fixed-size fields. May be compressed.
- **content** — the third and last item: the actual bytes (a Content payload's cache; empty for a Paper, which has no content of its own). May be compressed, and may be a hidden encrypted override map (§9).

| Key | Field | Type | Lives in |
|---|---|---|---|
| 2 | `cache_id` / `root_hash` | bytes (8, opt) | `part_meta` |
| 3 | `hint` / `label` | text (opt) | `core_meta_item` |
| 4 | `mime_type` | text (opt) | `core_meta_item`; Content only |
| 7 | `total_bytes` | uint | `part_meta` |
| 8 | `content_sha256` | bytes (32, required iff `sector_count > 1`) | `core_meta_item` |
| 11 | `filename` | text (opt) | `core_meta_item`; Content only |
| 12 | `content_compression` | uint (opt) | `core_meta_item` |
| 13 | `set` | text (opt) | `core_meta_item`; Paper only |
| 14 | `slug` | text (opt) | `core_meta_item`; Paper only |
| 15 | `files` | array | `bulky_meta_item`; Paper only |
| 16 | `related` | array | `bulky_meta_item`; Paper only |
| 17 | `collection_id` | bytes (8, opt) | `core_meta_item` |
| 18 | `collection_label` | text (opt) | `core_meta_item` |
| 19 | `collection_tag` | text (opt) | `core_meta_item` |
| 24 | `icon` | text (opt) | `core_meta_item` |
| 26 | `lat` | float64 (opt) | `core_meta_item` — author-declared latitude of this payload's own location |
| 27 | `lng` | float64 (opt) | `core_meta_item` — author-declared longitude, same scope as `lat` |
| 28 | `encryption` | uint (opt) | `core_meta_item`; Content only |
| 30 | `key_material` | bytes (32, opt) | `core_meta_item` |
| 31 | `retain_key` | bool (opt, default `true`) | wherever `key_material` appears |
| 32 | `signature_algorithm` | uint (opt) | `core_meta_item` |
| 33 | `signature` | bytes (2420, opt) | `bulky_meta_item` |
| 34 | `signer_pubkey` | bytes (1312, opt) | `bulky_meta_item` |
| 35 | `signer_id` | bytes (8, opt) | `core_meta_item` |
| 36 | `signer_label` | text (opt) | `core_meta_item` |
| 37 | `kdf_alg` | uint (opt) | `core_meta_item`; Content only |
| 38 | `kdf_salt` | bytes (16, opt) | `core_meta_item`; Content only |
| 39 | `kdf_iters` | uint (opt, default 100000) | `core_meta_item`; Content only |
| 40 | `description` | text (opt) | `core_meta_item` |
| 42 | `sector_index` | uint | `part_meta` |
| 43 | `sector_count` | uint | `part_meta` |
| 44 | `parity_scheme` | uint (opt) | `part_meta`; only on sectors at index ≥ `sector_count` |
| 45 | `bulky_meta_compression` | uint (opt) | `core_meta_item` |
| 46 | `bulky_meta_compressed_bytes` | uint (required iff key 45 present) | `core_meta_item` |
| 47 | `bulky_meta_sha256` | bytes (32, required iff `sector_count > 1`) | `core_meta_item` |
| 48 | `radius_m` | float64 (opt) | wherever `lat`/`lng` appears — `core_meta_item` or a `related` entry |
| 49 | `prefer_declared_location` | bool (opt, default `false`) | `core_meta_item` only |
| 50 | `in_reply_to` | bytes (8, opt) | `core_meta_item` — `cache_id`/`root_hash` of the single parent this is replying to (§7) |
| 51 | `title` | text (opt) | `core_meta_item` |
| 52 | `created_at` | uint (opt) | `core_meta_item` — author-declared Unix timestamp (seconds since epoch) this payload was authored; reflects the authoring device's clock, not independently verified |
| 53 | `domain` | text (opt) | `core_meta_item`; Paper only — human-readable name for `tagdrop://<domain>/<slug>` links, see §7 "Domains" |
| 54 | `location_label` | text (opt) | `core_meta_item` — human-readable, non-coordinate description of this payload's own location, e.g. "🚋 Tram 40"; see §4.2 |

Keys **1**, **6**, **9**, **10** are retired (formerly `version`-inside-payload,
`chunk_count`, `chunk_index`, `chunk_data` — superseded by the envelope's
`version` and by `part_meta`'s `sector_index`/`sector_count`; §14). Key **5**
(`content`) no longer appears in `core_meta_item` or `bulky_meta_item`, but is
reused with the same meaning inside the encrypted override map structure only
(§9). Key 25 is reserved for a future small embedded image icon (raw bytes),
as an alternative to the emoji `icon` field. Keys 28, 30, 31 are defined in §9
(Encryption); keys 32–36 in §10 (Verified Authorship); keys 37–39 in §9
(Passphrase-based key derivation); keys 26, 27, 48, 49, 54 in §4.2 (Declared
location and priority); key 53 in §7 (Domains). Key 29 is reserved and
unused — see §9 for why an encrypted override map's nonce doesn't need its
own clear field.

**`content_sha256`/`bulky_meta_sha256` are REQUIRED whenever `sector_count >
1`.** Without it, an adversary who substitutes one sector of a multi-sector
payload after the fact (e.g. replacing one sticker in a physical multi-code
layout) goes undetected — see §5, which requires decoders to reject a
multi-sector payload whose hash is missing rather than silently accepting
unverified reassembled bytes. They remain OPTIONAL for `sector_count == 1`,
where there's nothing to verify completeness of — a single code's own QR
error correction already guards against incidental corruption within one
code, so the bytes aren't worth taxing every simple code with. Decoders MUST
verify either hash whenever present, regardless of `sector_count`.

**`bulky_meta_compressed_bytes`** is the one explicit length field anywhere in
the stream: `bulky_meta_item` sits *between* `core_meta_item` and content, so
if it's compressed, a decoder needs to know exactly where its compressed
bytes end and content begins. Uncompressed items need no such field — CBOR's
self-delimiting structure already marks their end — and content, being last,
simply runs to the end of `total_bytes` regardless of whether it's
compressed.

**Codes may carry a hidden, encrypted override map.** Once a Content
payload's bytes are fully assembled, they may be a self-contained, encrypted
**override map** — a small CBOR map using key numbers 3 (`hint`), 4
(`mime_type`), 5 (`content`), and 11 (`filename`) — that overlays
`core_meta_item`'s same-numbered fields once decrypted. `encryption` (key 28),
if present and non-zero, is an optional hint that this is the case; its
absence does NOT mean the code has no hidden override map — see §9,
"Discovery, not declaration." `core_meta_item`'s own keys 3/4/11, if present,
serve as the values shown before (or without) a matching key. See §9.

### File entry sub-keys (elements of key 15)

Each element is a CBOR map:

| Key | Field | Type |
|---|---|---|
| 20 | `slug` | text |
| 21 | `mime_type` | text |
| 22 | `file_id` | bytes (8) — `cache_id` of the file's root QR |
| 41 | `description` | text (opt) — what this file is, e.g. "A poem to read" |

### Related paper sub-keys (elements of key 16)

Each element is a CBOR map:

| Key | Field | Type |
|---|---|---|
| 3 | `hint` | text — human-readable location description |
| 13 | `set` | text (opt) |
| 14 | `slug` | text (opt) |
| 23 | `paper_id` | bytes (8, opt) — root hash of the related paper |
| 26 | `lat` | float64 (opt) — latitude of the related paper |
| 27 | `lng` | float64 (opt) — longitude of the related paper |
| 48 | `radius_m` | float64 (opt) — circle-of-uncertainty radius in meters around `lat`/`lng` |
| 30 | `key_material` | bytes (32, opt) — decryption key for the related paper's content, see §9 |
| 31 | `retain_key` | bool (opt, default `true`) — see §9 |

---

## 4. Payload Types

### 4.1 Sectors and `part_meta`

Every code is one **sector** of a payload. `part_meta` (§3) tells a decoder
how this sector fits into the whole:

```
part_meta map {
  2: h'<8 bytes>',  // cache_id / root_hash — identifies the payload this sector belongs to
  42: 0,            // sector_index — 0-based
  43: 1,            // sector_count — how many data sectors carry the payload
  7: <n>,           // total_bytes — length of the full reassembled stream
}
```

A payload that fits in one code is `sector_count: 1`, `sector_index: 0` —
the common case, at a cost of a few extra bytes in `part_meta` over a
hypothetical single-map design (see §4.2 for what those bytes buy). A
payload spanning several codes uses `sector_index` 0 through
`sector_count − 1`, one per sector, each carrying a slice of `sector_bytes`
(every sector but the last is the same length). `cache_id`/`root_hash` is
identical across every sector of the same payload — exactly as today's
Chunk already carries `cache_id` in the clear on every fragment — so a
single isolated sector scan identifies which payload it belongs to without
needing any other sector decoded first. It's the one `part_meta` field that
may be omitted: a key-only code (§9, "Decryption keys") carries no content of
its own to identify, so it typically has no `cache_id` either.

**Parity sectors:** a sector at `sector_index ≥ sector_count` is recovery
data, not payload data — see §5's redundancy scheme. It carries
`parity_scheme` (key 44) so a decoder that wants to use it can, while any
decoder that doesn't recognise the index range safely ignores it (the same
forward-compatibility rule §5 already required for chunk indices).

Reassembly (§5): concatenate `sector_bytes` from sectors `0` through
`sector_count − 1` in order. The result is the **reassembled stream** — see
§4.2.

### 4.2 The Reassembled Stream

The reassembled stream (§4.1) is a CBOR Sequence of two maps followed by raw
bytes:

```
CBOR(core_meta_item) || CBOR(bulky_meta_item) || content
```

Laid out as a sequence of three concatenated parts:

```
┌────────────────┬─────────────────────┬─────────────────────────┐
│ core_meta_item │ bulky_meta_item     │ content                 │
│ plain CBOR map │ CBOR map            │ raw or compressed bytes │
│ (always small) │ (may be compressed) │ (empty for Paper)       │
└────────────────┴─────────────────────┴─────────────────────────┘
```

**`core_meta_item`** is always plain CBOR (never compressed) and always
small — by authoring convention, meant to fit in the first sector or two. It
carries the preview-tier fields — `hint`/`label`, `title`, `mime_type`,
`filename`, `set`/`slug`, `description`, collection fields, `icon`, declared
location (`lat`/`lng`/`radius_m`/`prefer_declared_location`/`location_label`),
`key_material`/`retain_key`, kdf fields, the small signature fields, `in_reply_to` — plus
declarations about what follows: `bulky_meta_compression`, `bulky_meta_compressed_bytes`
(only present when key 45 is, since uncompressed `bulky_meta_item` keeps the
free self-delimiting boundary — §3), `bulky_meta_sha256`,
`content_compression`, `content_sha256`.

**Declared location and priority:** `core_meta_item` may carry `lat`/`lng`
(keys 26/27) — the *author's declared* coordinates for this Content's or
Paper's own physical placement, useful when the scanning device lacks a GPS
lock, or simply to record where the code was placed regardless of whether it
does. This is distinct from a `related` entry's `lat`/`lng` (§4.3), which
hints at a *different*, not-yet-scanned paper's location rather than this
payload's own. An optional `radius_m` (key 48, float64) gives a
circle-of-uncertainty radius in meters around the point — valid wherever
`lat`/`lng` appears, whether at `core_meta_item` level or inside a `related`
entry. By default, a live GPS fix at scan time takes priority over the
declared location when both are available — the declared location is only a
fallback for when GPS is unavailable. Setting `prefer_declared_location`
(key 49, bool, default `false`) flips that priority so the declared
coordinates win even when a live GPS fix is available, for placements where
the author's coordinates are known to be more reliable than whatever fix the
scanning device manages (e.g. deep indoors, under tree cover, in a
basement). Implementations are expected to resolve and store only the single
effective `(lat, lng, radius_m)` triple after applying this priority, not
both candidate locations.

**Explicit no fixed point.** Some drops have no single coordinate worth
recording at all — e.g. an item mailed to a recipient whose address the
author never geocoded, or one carried on a moving vehicle ("🚋 Tram 40")
rather than left at a point. Two ways to say so:

- `prefer_declared_location` (key 49) set `true` while `lat`/`lng` are both
  absent. Ordinarily this key only reorders *priority* between two
  candidate locations (declared vs. live), but with no declared coordinates
  to prioritize there is nothing for it to prefer — so this combination is
  instead read as an explicit author assertion that this payload has no
  reliable fixed point, and a live GPS fix at scan time (which would
  otherwise fill the gap by default, per the priority rule above) MUST NOT
  be substituted for it.
- `location_label` (key 54, text, optional) — a human-readable, non-coordinate
  description of the drop's location ("🚋 Tram 40", "mailed, destination
  unknown"). Decoders SHOULD display it as-is. Its presence without
  declared `lat`/`lng` carries the same "no fixed point, don't substitute
  live GPS" meaning as the flag above (a label like "Tram 40" describes
  something that moves, so a scan-time GPS fix would misrepresent it as a
  fixed point); the two MAY be combined for emphasis but neither requires
  the other. `location_label` MAY also be present alongside declared or
  resolved coordinates, simply as descriptive text (e.g. "back garden, behind
  the shed") — only its presence *without* coordinates changes resolution
  behavior.

In either case, implementations resolving location for storage/display MUST
treat the result as "no location" — `(lat, lng, radius_m)` all absent —
rather than falling back to a live GPS fix. `location_label`, when present,
is independent of that triple and is carried/stored alongside it regardless
of whether a fixed point was resolved.

**`bulky_meta_item`** holds whatever doesn't need to be in the early preview
but isn't raw content — for a Paper, that's `files[]` and `related[]`; for
either payload kind, any large fixed-size field regardless of category, e.g.
`signature` and `signer_pubkey` (§10), which are "identity" fields by
category but bulky by size in practice. Placement here is about size, not
meaning. May be compressed per `bulky_meta_compression` (§8); if so,
`bulky_meta_compressed_bytes` marks exactly where it ends and content
begins (§3).

**content** is the actual bytes: a Content payload's cache (raw or
compressed per `content_compression`), or nothing at all for a Paper, which
has no content of its own. Content never needs a declared length, compressed
or not — it's always last, so its length is simply whatever remains once
`total_bytes` worth of sectors are all in. For a Content payload, it may also
be a hidden encrypted override map instead of plain content — see §9; this
doesn't extend to Paper, whose content slot stays empty either way (`root_hash`
is always content-addressed, with no random-`cache_id`-style exception).

Example — a Content payload, one sector:

```
envelope: version=1, type=0
part_meta { 2: h'<cache_id>', 42: 0, 43: 1, 7: <n> }

core_meta_item {
  3: "under the bridge",     // hint
  4: "text/html",            // mime_type
  11: "poem.html",           // filename
  17: h'<8 random bytes>',   // collection_id — optional, see §7 Collections
  18: "Spring Sticker Hunt", // collection_label — optional, see §7 Collections
  19: "springtrail2026",     // collection_tag — optional, see §7 Collections
  24: "🌳",                   // icon — optional, see §7 Icons
}
bulky_meta_item {}
content: h'<page bytes, raw or compressed per content_compression>'
```

Example — a Paper, one sector:

```
envelope: version=1, type=1
part_meta { 2: h'<root_hash>', 42: 0, 43: 1, 7: <n> }

core_meta_item {
  3: "Trail Stop 3 — Oak Tree", // label
  40: "Day 2 of the sunset trail: a poem and a hand-drawn map", // description
  13: "sunset-trail",           // set
  14: "oak-tree",               // slug
  17: h'<8 random bytes>',      // collection_id — optional, see §7 Collections
  18: "Spring Sticker Hunt",    // collection_label — optional, see §7 Collections
  19: "springtrail2026",        // collection_tag — optional, see §7 Collections
  24: "🌳",                      // icon — optional, see §7 Icons
  26: -33.8688,                  // lat — optional, author-declared location of this paper
  27: 151.2093,                  // lng — optional, author-declared location of this paper
  48: 25.0,                      // radius_m — optional, circle of uncertainty in meters
}
bulky_meta_item {
  15: [                         // files — directory of codes on this paper
    {20: "index", 21: "text/html",    22: h'<file_id>', 41: "A poem to read"},
    {20: "map",   21: "image/svg+xml", 22: h'<file_id>', 41: "A hand-drawn map"},
  ],
  16: [                         // related — hints to other papers
    {3: "Next stop: the red letterbox 200m north", 14: "letterbox",
     23: h'<paper_id>', 26: -33.8688, 27: 151.2093, 48: 50.0},
    {3: "Start of trail: town square notice board"},
  ],
}
content: (empty)
```

A payload too large for one sector splits `core_meta_item || bulky_meta_item
|| content` across several sectors purely by byte position — a decoder
reassembles first (§4.1), then parses the three reassembled items. There is
no longer a separate "manifest code": `sector_count` directly equals the
number of physical codes needed, and any sector — not just the first — can
be scanned in any order or session (§5).

### 4.3 Paper (`type` = 1)

A Paper is the **directory payload** for a physical paper (A4 sheet, sticker
board, poster). Think of it as a floppy disk's FAT: it lists every file on
the paper and can point toward related papers at other locations. Its
`core_meta_item`/`bulky_meta_item` shape is shown in §4.2 above; `content` is
always empty.

**`label` vs. `description` vs. `title` (issue #35):** `label` (key 3) is
the paper's *name or location* — "Trail Stop 3 — Oak Tree" tells you where
you are, not what's on it (the same key is called `hint` for a Content
payload instead — same field, different name by convention, §4.2).
`description` (key 40, optional — originally Paper-only, now valid for
Content too) is a content teaser or message body: for a Paper, the same
role `hint` plays for a Content payload, but shown once the directory is
already being browsed rather than as a "should I look for this" decision;
for a Content payload, free text alongside the cache's own bytes, or —
when the content slot is occupied by an attachment instead — standing in
as the message itself (see Postcards below). A per-file `description`
(key 41, optional, in each `files[]` entry alongside `slug`/`mime_type`)
plays the analogous role for an individual file, e.g. "A poem to read" or
"A hand-drawn map" — letting a finder choose among files they can already
see listed, before scanning each one's own code. `title` (key 51,
optional, valid for both payload kinds) is a short subject/caption,
deliberately kept separate from `label`/`hint` so a caption never has to
share a field with "where this is" or "should I look for this". All three
fields are optional; omitting them just means the directory or preview
shows filenames/MIME types with no caption or teaser.

**Located related papers:** A `related` entry (key 16) may include `lat`/`lng`
(keys 26/27) — the approximate coordinates of that related paper, if the
author knows them — and an optional `radius_m` (key 48) circle-of-uncertainty
radius in meters around that point, the same field and semantics as the
core-level declared location (§4.2). The app shows these as a "❓" placeholder
pin (plus an uncertainty circle when `radius_m` is set) on the map for
related papers that haven't been scanned yet, helping the finder navigate
toward them. Once that paper is scanned, its own `ScannedPaper` location
(resolved from the device's live GPS fix and/or that paper's own declared
location, per §4.2's priority rule) replaces the placeholder.

**Navigation:** HTML files on the paper can link to other files using:
```html
<a href="tagdrop://<paper-root-hash-hex>/map">See the map</a>
```
The TagDrop app intercepts these links and resolves them from the local database.

**No more practical size limit (issue #37):** Previously, `files[]`/
`related[]` entries competed for space against the ~800-byte budget of a
single code, capping a Paper at roughly 15–20 files or 8–12 related entries
before it had to be split into multiple linked papers. Because `bulky_meta_item`
(§4.2) can now span as many sectors as it needs, that ceiling is gone — a
Paper with hundreds of files is just a Paper with a larger `sector_count`,
not a different shape.

### 4.4 Content-Addressed IDs (IPFS-inspired)

TagDrop uses **content-addressed identifiers** — the same content always gets the same ID, regardless of who created it or where it was found.

**File IDs (`cache_id`):**
```
cache_id = SHA-256(uncompressed content)[0:8]
```
Two payloads encoding the same content bytes will have the same `cache_id`,
regardless of `core_meta_item`/`bulky_meta_item`. This enables deduplication
across multiple papers and payloads made by different authors. As before,
`cache_id` MUST be random instead, when a hidden override map might be
present (§9).

**Paper root hashes:**
```
root_hash = SHA-256(core_meta_item || bulky_meta_item || content)[0:8]
```
computed over the **logical** (decompressed) bytes of all three — never the
compressed wire bytes, mirroring exactly why `cache_id` above is defined over
uncompressed content: the same logical payload must always produce the same
root hash, regardless of which DEFLATE implementation or level happened to
encode it onto the wire. `bulky_meta_item` is hashed with keys 32–36 (§10's
signature fields) absent, whether or not the paper ends up signed — a fixed
convention, not a chicken-and-egg dance: unlike the single-map design this
replaces, `root_hash` lives in `part_meta` (§4.1), *outside* the structure
it's computed over, so there's no self-reference and no placeholder/re-encode
pass needed. Compute it once, then copy the same value into every sector's
`part_meta`. For a Paper, `content` is always empty, so in practice
`root_hash` is over `core_meta_item || bulky_meta_item` alone. The root hash
is the paper's permanent, immutable address. Because paper is inherently
immutable (you can't update a sticker), this is fine — a new revision gets a
new root hash.

**Three-level hierarchy:**

```
Paper (root_hash)
  └─ Files (cache_id)
       └─ Sectors (cache_id/root_hash carried in part_meta on every sector)
```

---

## 5. Multi-Code Assembly Protocol

1. **Scan sectors** in any order, any session. Each sector's `part_meta`
   (§4.1) gives `cache_id`/`root_hash`, `sector_index`, `sector_count`, and
   `total_bytes` — identical across every sector of the same payload, so the
   app can match a newly-scanned sector to a pending payload (or start a new
   one) regardless of which sector arrives first.
2. Track which `sector_index` values (`0` through `sector_count − 1`) have
   been collected for that payload, independently of any sector at index
   `≥ sector_count` (parity — see below).
3. When all of `0..sector_count-1` are collected: concatenate their
   `sector_bytes` in ascending `sector_index` order → the reassembled stream
   (§4.2).
4. Parse `core_meta_item` (plain CBOR, from the front of the stream). Parse
   `bulky_meta_item` next: if `bulky_meta_compression` is absent, CBOR's
   self-delimiting structure marks its end; if present, read exactly
   `bulky_meta_compressed_bytes` and decompress. If `sector_count > 1`,
   `bulky_meta_sha256` is REQUIRED (§3) — reject the payload as malformed if
   it's absent — and verify `SHA-256(bulky_meta_item bytes as transmitted) ==
   bulky_meta_sha256`, rejecting on mismatch; for `sector_count == 1`, verify
   it only if present. Whatever remains is `content`.
5. If `content` is ≥ 28 bytes, try AES-256-GCM decryption as
   `nonce(12) || ciphertext || tag(16)` (§9) against every known
   `key_material`. If one authenticates, decompress the plaintext if
   `content_compression != 0` and CBOR-decode it as the override map (§9) —
   merge it onto `core_meta_item` (override map's keys win) to get the final
   `hint`/`mime_type`/`content`/`filename`. Otherwise: if `sector_count > 1`,
   `content_sha256` is REQUIRED (§3) — reject the payload as malformed if
   it's absent — and verify `SHA-256(content as transmitted) ==
   content_sha256`, rejecting on mismatch; for `sector_count == 1`, verify it
   only if present. Then decompress `content` if `content_compression != 0`;
   `core_meta_item`'s fields are final as-is.
6. Deliver MIME-typed content to the viewer (Content payloads), or render the
   directory (Paper payloads, §4.3).

**Redundancy / erasure coding (issue #37):** A sector at `sector_index ==
sector_count` carries a `parity_scheme` (key 44). For `parity_scheme = 1`,
its `sector_bytes` is the byte-wise XOR of every data sector's `sector_bytes`
(`sector_index` `0` through `sector_count − 1`), each zero-padded to the
length of the longest data sector before XOR-ing. If exactly one data sector
is missing, reconstruct it: XOR the parity sector against every *other* data
sector the app already holds, then truncate the result using `total_bytes`
to discard any padding on a reconstructed final sector. Verify the
reconstructed stream the same way as a fully-scanned one (steps 4–5's hash
checks) before treating it as good — if verification fails (e.g. the parity
sector itself was the one that was corrupted), discard the attempt and fall
back to waiting for the real missing sector. Exactly one parity sector is
defined for `parity_scheme = 1` — it recovers from exactly one lost sector;
additional copies of the same parity sector would be redundant, not
additional protection. `parity_scheme` values `2` and up are reserved for
future schemes tolerating more than one loss (e.g. Reed-Solomon over
GF(256)) — not yet defined; until one is, authors needing more than
single-loss protection should fall back to physical redundancy (duplicate a
sticker) as before.

**Resumability (issue #14):** Because assembly only needs the `part_meta` and
`sector_bytes` stored from each sector, a partially-collected payload can be
saved to a database and resumed later. Every sector carries
`cache_id`/`root_hash` so the app can match newly-scanned sectors to a
pending payload.

**Geographic distribution:** Each sector is an independent, self-contained
code. Sectors can be placed at geographically separate locations (along a
trail, in different rooms, across a city). The finder accumulates them over
time. `core_meta_item`'s `hint`/`label` can describe the treasure hunt as
soon as any sector carrying it has been scanned.

**Sector-index forward compatibility (issue #37):** Step 3 above only ever
reads `sector_index` values `0` through `sector_count − 1` — reference
implementations (§15) build the reassembled byte array by indexing exactly
that range, never by iterating "every sector seen so far." This is the
mechanism the redundancy scheme above relies on: a sector at any other index
(`≥ sector_count`) is already, by construction, ignored by anything that
isn't specifically looking for parity at that index, with no explicit
discriminator needed beyond the index itself. Reusing an index *within*
`0..sector_count-1` for anything other than that exact data sector would not
be safe — that range is reserved for the data sectors, in that order, with no
exceptions.

---

## 6. Placing Codes in the Field

### Recommended layouts

**Single-code cache (simple drop):**
```
[ tagdrop:<base41> ]
```
One sticker, one code. Scan and done.

**Multi-code cache (trail):**
```
Location A: [ Sector 0: tagdrop:<base41> ]  ┐
Location B: [ Sector 1: tagdrop:<base41> ]  │   same cache_id/root_hash in every sector's part_meta (§4.1)
Location C: [ Sector 2: tagdrop:<base41> ]  │   sector_bytes concatenated in sector_index order (§5)
Location D: [ Sector 3: tagdrop:<base41> ]  ┘   → the reassembled stream (§4.2)
```

Sectors can be scanned in any order, any session (§5) — there's no
designated "start here" code the way an old Manifest used to be. Any sector
alone is enough to identify the payload (`cache_id`/`root_hash` is on every
one, §4.1) and to show its `hint`/`label` as soon as it's scanned, even
before the rest arrive.

**Sector size recommendation:** Target ~400 bytes per sector (decoded),
which encodes to ~600 Base41 characters and fits in a QR Version 15 that
prints cleanly at 3cm × 3cm and scans without zooming in on most phones.

**Redundancy (issue #37):** Add one parity sector (`parity_scheme` 1, §5) at
`sector_index == sector_count` and the set tolerates losing **any one**
physical sticker — a data sticker or the parity sticker — without stranding
the rest: the missing data sector is reconstructed by XOR-ing the
parity sector against the rest. This costs one extra code per payload and is
available at the format level today. It only covers a single loss per
payload, though — for sites where losing two or more stickers from the same
set is a real risk (e.g. high-traffic public locations), physical
duplication remains the right additional mitigation: print and place a
second copy of whichever sticker(s) matter most, same QR, same content.

---

## 7. TagDropNet — The Offline Paper Web

A collection of physical papers forms a **TagDropNet**: an offline, content-addressed hypertext web made of paper, with no server, no internet connection, and no central authority.

### Paper as floppy disk

Each A4 sheet is analogous to a floppy disk:

| Floppy disk concept | TagDrop equivalent |
|---|---|
| Disk label / volume name | `label` field in the Paper's `core_meta_item` |
| FAT (file allocation table) | the Paper payload (`type` 1) |
| Sectors | TagDrop **sector** — one physical QR code (`part_meta`/`sector_bytes`, §4.1); the Paper itself or any one of its files can each span several |
| Directory | `files` array in `bulky_meta_item` |
| Volume serial number | `root_hash` (content-addressed, permanent) |

A recommended layout for an A4 paper:

```
┌─────────────────────────────────────────────┐
│  [ Paper QR ]      Trail Stop 3 — Oak Tree  │
│                                             │
│  [ index.html ]    [ map.svg ]              │
│                                             │
│  Next: letterbox 200m north                 │
└─────────────────────────────────────────────┘
```

Scan the Paper code first to get the directory, then scan whichever file you want — you don't have to scan everything.

### Navigation links

HTML files can link across the TagDropNet using:
```
tagdrop://<rootHash-hex>/<slug>
```

When the TagDrop WebView encounters such a link, it:
1. Looks up `rootHash` in the local scanned-papers database.
2. Finds the `slug` in that paper's file directory.
3. Looks up the file's `cache_id` in the found-caches database.
4. If found: loads the file. If not: shows a "not yet scanned" message with the location hint.

This gives the experience of browsing a website, but entirely offline and made of physical paper. `rootHash` may instead be a human-readable **domain name** — see "Domains" below.

### Relative links (same-paper)

HTML authored for TagDrop can use ordinary relative URLs (`./about.html`,
`../images/logo.svg`, `style.css`) to reference other files on the **same**
physical paper — exactly as if the paper's files were a normal website
directory, with no special TagDrop syntax required.

This works because of how slugs and page-loading combine:

- A paper's manifest lists files as flat slug strings, which may contain `/`
  (e.g. `images/logo.svg`, `pages/about.html`) to express a directory layout
  (see "No explicit folder hierarchy" below).
- When the Android app displays a file, it loads the page with
  `https://<rootHash-hex>.paper.tagdrop.invalid/<slug>` as the **base URL**
  (via `loadDataWithBaseURL`) instead of as an opaque `data:` URI — the root
  hash is a **subdomain label**, not a path segment. `.invalid` is an
  IANA-reserved TLD (RFC 2606) that never resolves over the network.
- Both ordinary relative URLs (`./about.html`, `../images/logo.svg`,
  `style.css`) and root-relative URLs (a single leading `/`, e.g.
  `/images/logo.svg`) in the HTML/CSS resolve against that base using
  standard URL resolution, producing more
  `https://<rootHash-hex>.paper.tagdrop.invalid/...` URLs — the host (and so
  the root hash) survives either way, because resolving a path never touches
  the host. Putting the root hash in the *path* instead would break
  root-relative links: an absolute-path reference replaces a base URL's
  entire path, not just its last segment, so `/images/logo.svg` resolved
  against `.../<rootHash-hex>/<slug>` would land on `.../images/logo.svg`
  with the root hash gone. The app's `WebViewClient` recognises any
  `*.paper.tagdrop.invalid` host (alongside the `tagdrop://` scheme) and
  resolves the resulting `<rootHash-hex>`/`<slug>` pair through
  `TagDropLinkResolver`, exactly like a `tagdrop://<rootHash>/<slug>` link.

To reference a file on a **different** paper (a different root hash), use an
explicit `tagdrop://<rootHash-hex>/<slug>` link — this can't be relative,
since it's a different content-addressed directory.

Practical effect: a normal static-site folder (HTML + CSS + images with
relative links) can be zipped, fed to the generator, and turned into a set of
QR codes where the relative links keep working once scanned into the app —
no rewriting of the authored HTML required.

### Markdown content (`text/markdown`)

`mime_type` (key 4) is a free-form string — `text/markdown` is rendered as
HTML (via [CommonMark](https://commonmark.org/)) and displayed through the
same WebView/iframe path as `text/html`, so `tagdrop://` links and relative
same-paper links inside the rendered Markdown work identically to the
"Relative links" section above.

**Stylesheet convention:** if the paper a Markdown file belongs to also has a
file entry with slug `style.css` and `mime_type` `text/css`, and that file has
been scanned/cached, its content is inlined as a `<style>` tag in the `<head>`
of the generated HTML document. This is a pure naming convention — no
envelope or wire-format changes — so a Markdown page picks up paper-wide
styling just by the Paper listing a `style.css` file alongside the
`.md` files. Markdown files that don't belong to any scanned paper (standalone
single-code scans) render without a stylesheet.

### Sets and slugs

Papers can belong to named **sets** (trails, networks, exhibitions). Within a set, each paper has a unique `slug`. This enables relative addressing:

```
A paper with set="sunset-trail", slug="oak-tree"
links to another paper with slug="letterbox" in the same set.
```

The full navigation URI for the letterbox paper's index file would be:
```
tagdrop://<letterbox-paper-root-hash-hex>/index
```

Root hashes are permanent because paper is immutable. If a paper is updated, it gets a new hash — the old one continues to work as long as the old paper exists physically.

### Domains

A 16-character hex root hash is precise but not memorable. The optional
`domain` field (key 53, text) lets a paper claim a short, human-readable
name instead — `helloworld`, say — for use in navigation links. If a paper
doesn't declare `domain`, its `slug` (key 14) is used as a fallback domain,
so a paper that's already part of a named set gets a memorable address for
free with no extra field.

Like `collection_id` ("Collections" below), a domain is **unilateral, not
coordinated**: any author can claim any name, there's no registry, and
nothing stops two unrelated papers from claiming the same one. This is a
deliberate trade-off — useful names stay short — and is resolved at lookup
time rather than prevented at authoring time (see "Resolving a domain
link" below).

#### Domain links

A navigation link may use a domain name in place of the root hash:

```
tagdrop://<domain-name>/<slug>
```

The TagDrop app intercepts this exactly like a `tagdrop://<rootHash-hex>/<slug>`
link, finds the file's `cache_id` in the resolved paper's directory, and
loads it from the found-caches database.

#### Resolving a domain link

A domain name and a root hash occupy the same position in the link, and a
domain name made only of hex digits is indistinguishable from a root hash
by shape alone. Resolvers MUST therefore:

1. Try an exact `root_hash` lookup first (the database's primary key for
   papers, so this is cheap regardless of whether the host turns out to be
   hex-shaped).
2. Only if that lookup misses — whether because the host isn't hex-shaped at
   all, or because it is but no paper with that exact hash is known — fall
   back to a domain lookup: scan known papers for `domain` (or, absent that,
   `slug`) case-insensitively matching the host.

This "exact match wins" order means a real root hash can never be shadowed
by a same-looking domain name, while a hex-shaped domain name (e.g.
`deadbeef`) still resolves normally on any device that hasn't *also* scanned
a paper with that literal root hash.

**Picking the closest match.** Because domains are uncoordinated, more than
one known paper can match the same name — this is expected, not an error.
When it happens, the app resolves to a single candidate using:

1. If the device has a current position fix, and at least one candidate has
   a known location (declared, or resolved from a live GPS fix when it was
   scanned — §4.2's location/priority rules), pick the candidate nearest to
   the device.
2. Otherwise — no device position, or no candidate has a location — pick
   the most recently scanned candidate.

This favours "the one you're standing next to" when that's knowable, and
"the one you saw most recently" otherwise, both better guesses than an
arbitrary pick. If no known paper matches the name under either field, the
app reports the domain as not found (with the requested `slug`, so the UI
can still show what was being looked for) rather than treating it as
invalid input — the name may simply not have been scanned yet.

**Searchability:** since `domain` (and its `slug` fallback) is meant to be
memorable, it's included alongside `label`/`set`/`slug` in the app's
collection search, so a paper can be found by name without needing its
exact link.

### Collections (ad-hoc grouping)

`set`/`slug` require a named, coordinated trail — every paper in the set
agrees on the set name and a unique slug, and is addressed via its
content-derived `root_hash`. That's the right model for a curated trail or
exhibition.

For looser groupings — a handful of stickers scattered by the same person, a
single-file drop that's part of a bigger scavenger hunt, or any case where
there's no shared directory to scan first — the optional `collection_id`
field (key 17, 8 random bytes) provides a lighter-weight mechanism:

- The author generates one random `collection_id` and stamps it into every
  QR code (Content or Paper) that should be grouped together. There's no
  central directory listing what belongs to the
  collection — it's **distributed**: each code is independently
  self-contained, and membership is discovered purely by what's been scanned.
- The app groups everything it has found that shares the same
  `collection_id` into one "collection" on the home screen, alongside papers
  (grouped by `root_hash` + `files[]`) and standalone single-file scans
  (each of which is its own one-page collection). The collection grows as
  more pieces are scanned — there's no fixed membership list and no
  "complete" state.
- `collection_id` is independent of `set`/`slug`: a paper can belong to both
  a named set (for trail navigation between papers) and an ad-hoc collection
  (for home-screen grouping with unrelated loose scans).

Unlike `root_hash`, `collection_id` is **not** content-addressed — it's
arbitrary random bytes chosen once by the author, since its only purpose is
grouping in the finder's app, not identity or integrity.

#### Naming a collection

Two optional text fields give a collection a human identity, independent of
its random `collection_id`:

- `collection_label` (key 18) — a human-readable name for the collection
  (e.g. `"Spring 2026 Sticker Hunt"`), shown as the title of the collection
  card on the home screen. The author repeats the same label on every code
  that shares the `collection_id`; the app can display it as soon as it sees
  the first one.
- `collection_tag` (key 19) — a short, hashtag-style string (e.g.
  `"springtrail2026"`) for cross-referencing **separate** collections that
  belong to a larger event or theme. Unlike `collection_id`, a tag is not a
  grouping key by itself — multiple distinct `collection_id`s (e.g. several
  independent trails) can share the same `collection_tag` to indicate they're
  part of the same city-wide event, without merging them into one collection.

Both fields are optional and purely cosmetic — omitting them just means the
app falls back to a generated title (e.g. derived from the first scanned
item's hint or filename).

### Replies and threading

`collection_id` is deliberately undirected — every code sharing the same ID
is just "in the bag," with no parent/child structure. Some uses need the
opposite: a code that responds to one specific earlier code, the way a
forum post or an email replies to exactly one prior message, forming a
thread as replies accumulate.

The optional `in_reply_to` field (key 50, 8 bytes) carries the `cache_id`
(if the parent is a Content payload) or `root_hash` (if the parent is a
Paper) of the single code this one is replying to. Absent, this is a new,
root message — not part of any thread. Present, it's a directed pointer to
exactly one parent, resolved the same way any other content-addressed
reference resolves: the app looks `in_reply_to` up against whatever it has
already scanned and cached, locally, with no central server.

A reply can itself be replied to — set the new code's own `in_reply_to` to
the reply's `cache_id`/`root_hash` — chaining into an arbitrarily deep
thread that the app reconstructs by walking `in_reply_to` pointers backward
through its local cache. The parent doesn't need to have been scanned yet
for a reply to be valid: a finder can scan a reply before its parent (or
never find the parent at all), the same "discovered, not required"
tolerance the rest of TagDrop's offline model already assumes elsewhere
(e.g. a `related[]` hint to a not-yet-scanned paper, §4.3). Unlike
`related[]`, though, `in_reply_to` is a single mandatory-shape pointer, not
an array of navigation hints — it names the one thing this code is talking
to, not a list of places to look.

`in_reply_to` carries no authentication on its own — anyone who can read a
parent's `cache_id`/`root_hash` (visible once that code is scanned, or
printed alongside it) can author a reply that points to it, the same base
assumption that already applies to every other TagDrop field. Pair with
Verified Authorship (§10) if a thread needs to resist forged replies.

### Postcards

A common shape combining the above: a short message, optionally with one
or more attachments, optionally directed at an earlier drop as a reply —
otherwise it's a new conversation. This needs no new wire structure beyond
`title` (key 51) and widening `description` (key 40) from Paper-only to
both payload kinds; a "postcard" is just an ordinary Content or Paper
payload, composed from fields that already exist:

- **Subject line:** `title` (key 51, optional) — a short caption, kept
  separate from `hint`/`label`'s existing role (§4.2/§4.3) so a postcard's
  subject doesn't have to share a field with that pre-existing meaning.
- **Message, no attachment:** a Content payload whose `content` bytes
  *are* the message (`mime_type` `text/plain` or `text/html`) — keeping the
  message in `content` rather than `description` matters here, since
  `cache_id` is content-addressed (§4.4, `sha256(content)`) and every
  empty-`content` postcard would otherwise hash to the same `cache_id`
  regardless of what its `title`/`description` said.
- **Message with one attachment:** a Content payload where
  `content`/`filename`/`mime_type` carry the attachment (a photo, a voice
  clip) — `cache_id` is then content-addressed over the attachment, which
  is the thing worth deduplicating by — and `description` (key 40, now
  valid for Content, not just Paper) carries the message instead, since the
  content slot is spoken for.
- **Longer message with attachment(s):** a Paper payload, with `description`
  carrying the message and `files[]` listing the attachment(s) — each
  attachment is its own small Content payload the author creates alongside
  the Paper.
- **A reply to any of the above:** the same shape, with `in_reply_to` set to
  the parent's `cache_id`/`root_hash`. Omit it for a new, unprompted
  postcard.

There is no `postcard` type or flag in the CBOR — any Content or Paper
payload is a postcard exactly when its author composes it this way, the
same way `collection_id` turns ordinary payloads into a collection purely
by convention (above) rather than by introducing a new payload kind.
Threads of postcards are just chains of `in_reply_to` pointers, readable
and extendable by anyone who finds them, with no server or account needed
to keep the conversation going.

### Icons

`icon` (key 24) is an optional text field — typically a single emoji — that
authors can stamp onto a Content or Paper payload to give a page or
collection a visual identity (e.g. "🌳" for a trail stop under a tree,
"📖" for a story page). The TagDrop app shows it in a small icon slot on the
Collections, History, and collection-detail screens.

For an ad-hoc collection (`collection_id`), the app uses the icon from the
first scanned item that has one — the same "first wins" pattern used for
`collection_label`.

Key 25 is reserved for a future small embedded image icon (raw bytes), as an
alternative for authors who want a custom image instead of an emoji. The
icon slot in the app's UI is designed to host either form.

## 8. Compression

| `content_compression`/`bulky_meta_compression` value | Algorithm |
|---|---|
| 0 (or absent) | None |
| 1 | DEFLATE, zlib-wrapped (RFC 1950) |
| 2–255 | Reserved |

Compression is per-item, not whole-stream: `content_compression` (key 12)
governs `content`; `bulky_meta_compression` (key 45) governs
`bulky_meta_item`. They're independent — a payload may compress one, both,
or neither. `core_meta_item` is never compressed (§4.2), since it must stay
cheaply parseable before a decoder knows anything else about the payload.
`content_sha256`/`bulky_meta_sha256` (§3) are each over their own bytes
**as transmitted** — compressed, if that item is compressed — so integrity
can be verified before decompression.

DEFLATE typically achieves 50–70% size reduction on HTML and text, effectively doubling QR capacity for textual content.

---

## 9. Encryption

A Content payload's `hint` (3), `mime_type` (4), `content` (5), and
`filename` (11) — `hint`/`mime_type`/`filename` living in `core_meta_item`,
`content` being the reassembled stream's own third item (§4.2) — may
optionally be shadowed by a hidden, encrypted **override map**, independently
of compression (§8). Unlike most of this format, the override map's presence
is **never required to be declared** — see "Discovery, not declaration"
below. A code with no declared `encryption` and an unremarkable
`core_meta_item`/`content` can still be carrying one. This mechanism is
Content-only: a Paper's `content` slot stays empty and is always
content-addressed via `root_hash` (§4.4), with no override-map exception.

| `encryption` value | Algorithm |
|---|---|
| 0 (or absent) | None declared |
| 1 | AES-256-GCM |
| 2–255 | Reserved |

`encryption` (key 28) is an **optional hint**, not a precondition: a code MAY
set it to `1` to advertise "scan a key to unlock more" (e.g. for a "🔒
Locked" badge in the UI). Its absence does NOT mean the code has no hidden
override map.

| Key | Field | Type | Lives in |
|---|---|---|---|
| 28 | `encryption` | uint (opt) | `core_meta_item`; Content only |
| 30 | `key_material` | bytes (32, opt) | `core_meta_item` (Content or Paper), or a `related` entry (key 16) |
| 31 | `retain_key` | bool (opt, default `true`) | wherever `key_material` appears |

Key 29 is reserved and unused: the GCM nonce travels embedded in the override
map's ciphertext itself (below), so a separate `core_meta_item` `nonce` field
would only add bulk and a second (always-matching, or suspiciously-not) value
to cross-check — without it, there's simply nothing nonce-shaped in
`core_meta_item` at all.

### Encrypted override map

A hidden **override map** is a CBOR map using the same key numbers as
`core_meta_item`'s preview fields, plus `content`'s own key — 3 (`hint`), 4
(`mime_type`), 5 (`content`), 11 (`filename`):

```
override map {
  3: "treasure map",          // hint — optional
  4: "image/png",              // mime_type
  5: h'<real content bytes>',  // content
  11: "map.png",               // filename — optional
}
```

Its CBOR bytes are compressed (§8, if `content_compression != 0`) and then
AES-256-GCM-encrypted (see below) to a single **self-contained blob**:

```
nonce(12 bytes) || ciphertext || tag(16 bytes)
```

The nonce travels with the blob — nothing in `core_meta_item` is needed to
locate or interpret it.

**Where this blob lives:** the reassembled stream's `content` item (§4.2) —
the same slot a Content payload's cache normally occupies. Once
`core_meta_item` and `bulky_meta_item` have been parsed off the front of the
reassembled stream (§5 steps 3–4), whatever remains — if its length is ≥ 28
(the minimum possible blob: 12-byte nonce + 16-byte tag, for an empty
plaintext) — is a candidate, *in addition to* whatever it decodes to as plain
`content` per `content_compression`. §5 step 5 tries both. This works the
same way regardless of `sector_count`: assembly happens first, so a
multi-sector payload's hidden blob is no different from a single-sector
one's.

**Producing the final view:** for each candidate `key_material` the app
holds, try AES-256-GCM decryption of the candidate blob using its first 12
bytes as the nonce. If the authentication tag checks out, decompress the
remaining plaintext (if `content_compression != 0`) and CBOR-decode it as
the override map, then merge it onto `core_meta_item` — its keys 3/4/11 are
overridden by the override map's same-numbered keys, and the override map's
key 5 becomes the final `content`, replacing whatever the content slot
decoded to plainly. If no key has yet succeeded (or the code carries no such
blob at all), `core_meta_item`'s fields and the plainly-decoded `content`
*are* the final view, exactly as in §4.1/§4.2.

```
part_meta { 2: h'<8 random bytes>', 42: 0, 43: 1, 7: <n> }  // cache_id — random, see below
core_meta_item {
  12: 1,  // content_compression — applies to the content slot's plain
          //   reading, and to the override map's CBOR bytes before encryption
  28: 1,  // encryption — optional "🔒 Locked" hint
}
bulky_meta_item {}
// + content slot:
//   h'<12-byte nonce>' || h'<ciphertext of (compressed) override map>' || h'<16-byte tag>'
```

**Cover stories, or no story at all:** `core_meta_item`'s `hint` (3),
`mime_type` (4), and `filename` (11), plus whatever the content slot decodes
to plainly, are shown (and used) until a matching key is found. They MAY be
a generic "locked" placeholder, a believable **decoy** (different hint/MIME/
content/filename than what's really there), or — since `encryption` need not
be declared — genuine, unremarkable content with no relation to the hidden
override map at all. A code can look, scan, and behave exactly like any
other TagDrop code while still carrying a hidden layer in its content slot.
Once a matching `key_material` is found, the override map's same-numbered
fields replace the plain ones — the displayed content, hint, MIME type, and
filename **self-correct** to the real ones.

For the fully-undeclared case to actually be deniable, `core_meta_item` and
the content slot need genuine, unremarkable content of their own — an empty
or trivially-placeholder one is itself a tell ("why would this code exist at
all?").

**Order of operations:** compress (§8) first, then encrypt — encrypted bytes
are high-entropy and don't compress further, so encryption is always the
last transform applied before transmission, and the first reversed on
receipt. What gets compressed-then-encrypted is the override map's CBOR
bytes. `content_sha256` (key 8, §3) continues to cover `content` exactly as
transmitted, i.e. after compression *and* encryption, so a partially- or
incorrectly-assembled multi-sector payload can be detected before a
decryption key is even available.

**`cache_id` for a code carrying a hidden override map is random, not
content-addressed.** §4.4 defines `cache_id = SHA-256(uncompressed
content)[0:8]` so that identical content always gets the same ID — useful for
deduplication, but exactly the wrong property here: it would let anyone
compute the `cache_id` of the content slot's own plain reading (cover story
or not) and check whether any code in the wild carries it, linking that code
to a known document regardless of what's hidden inside. An author embedding
a hidden override map MUST set `cache_id` (key 2, in `part_meta`) to 8 random
bytes, independent of both the content slot's own plain reading and the
override map's real `content`.

**AES-256-GCM:** the 12-byte nonce prefixing the blob MUST be unique for
every encryption performed under a given key — a reused nonce breaks
AES-GCM's confidentiality entirely. The (compressed) override map's CBOR
bytes are encrypted to `ciphertext || 16-byte authentication tag` (tag
appended) — the default output of both `javax.crypto.Cipher`
("AES/GCM/NoPadding") on Android and `SubtleCrypto.encrypt()` in browsers —
then prefixed with the nonce to form the blob above, used as the reassembled
stream's content slot (§4.2).

### Decryption keys

A decryption key is **32 raw bytes** (`key_material`, key 30) — an
AES-256-GCM key, used directly with no passphrase or key-derivation step. It
can appear:

- in any payload's `core_meta_item`, Content or Paper, as a top-level field —
  "this code also carries a key for other content," independent of whatever
  `content` (if any) the code itself carries; or
- on an element of a Paper's `related` array (key 16, §3) — "scanning this
  paper reveals a key for the related paper," for trails meant to be
  discovered in sequence.

A Content payload carrying `key_material` may omit `content` and `mime_type`
entirely — a code can be *just a key*, with no displayable content of its
own:

```
part_meta { 42: 0, 43: 1, 7: <n> }  // no cache_id — see below
core_meta_item {
  30: h'<32-byte AES-256 key>',
  31: false,                 // retain_key — use once against what's cached now, then forget
}
bulky_meta_item {}
content: (empty)
```

Note what's *not* here: no `cache_id`, no pointer to the content this key
unlocks. That's deliberate — a `key_material` carries no reference to which
code(s) it applies to, so the same key MAY be the right key for many
different cached codes (e.g. one trail-wide secret that unlocks a hidden
layer on every sticker in the trail, not just one). "Try this key against
everything cached" isn't a fallback for when a targeted lookup is
unavailable — it's the only mechanism there is, and it's the right one for
a key that's reused across many tags. A key-only code typically omits
`cache_id` (key 2, in `part_meta`) too, since it references no content of
its own to be deduplicated or cached against.

`retain_key` (key 31, default `true`) is the author's recommendation for
whether the app should remember this key for future matches across scanning
sessions (`true`), or use it only against content already cached *right now*
and then discard it (`false`). It's a recommendation, not an enforceable
guarantee — an app or user can always choose to remember a key regardless.

**Discovery, not declaration:** no field says which content a given
`key_material` decrypts, and — per above — `encryption` (key 28) is at most
a hint, not a precondition. Instead, whenever the app learns a new
`key_material`, it tries AES-256-GCM decryption — using the candidate blob's
own embedded 12-byte nonce — against the content slot of every cached
Content payload that is ≥ 28 bytes and hasn't already been opened. A
successful authentication-tag check is the match; the app decompresses (if
`content_compression != 0`) and CBOR-decodes the result as the override map
(§9), then merges it onto `core_meta_item` — refreshing the displayed
`hint`/`mime_type`/`content`/`filename` to their real values. Symmetrically,
whenever a new code is cached, its content slot's bytes (if ≥ 28 bytes) are
tried against every previously-seen `key_material` (subject to that key's
`retain_key`). This is cheap — AES-GCM decryption of a few KB against a
handful of candidates is negligible, with a false-positive authentication
rate of ~2⁻¹²⁸ — so trying one key against an entire trail's worth of cached
codes costs nothing measurable, and means **scan order doesn't matter**: the
key first, the content first, or either in a later session, the app
reconciles them whichever order they arrive in.

### Privacy properties

A few of the choices above double as standard **plausible deniability**
measures — the inability for an observer to distinguish "this contains
something hidden" from "this is just opaque data," even with some of the
format in plain view. The same property underlies things like
[VeraCrypt](https://www.veracrypt.fr/)'s hidden volumes or
[OTR](https://otr.cypherpunks.ca/)'s deniable authentication.

- **Ciphertext is indistinguishable from random.** AES-GCM ciphertext with a
  fresh nonce is computationally indistinguishable from random bytes. Without
  the right key, only `core_meta_item`'s fields and `content`'s plain reading
  are visible — the envelope (`version`, `type`), `part_meta`
  (`cache_id`/`root_hash`, sector fields), `content_compression`, optionally
  `encryption`, whichever optional collection/icon fields the author
  included, and whatever `hint`/`mime_type`/`content`/`filename` (§9) the
  author chose: a placeholder, a decoy, or genuine unremarkable content with
  no relation to what's hidden. A hidden override map living in the content
  slot reveals nothing about the real values, or whether any particular
  `key_material` unlocks them — and genuine cover content is
  indistinguishable from "this is simply the (unencrypted) content, full
  stop."
- **Decoders tolerate trailing bytes.** A CBOR Sequence (RFC 8742, §2) is
  self-delimiting — a decoder reads exactly as many items as it expects and
  stops. TagDrop decoders MUST NOT treat additional bytes after a complete,
  valid 4-item envelope sequence (`version`/`type`/`part_meta`/`sector_bytes`,
  §2) as an error. This lets a sector's transmitted bytes be followed by a
  second, wholly independent envelope sequence — its own
  `version`/`type`/`part_meta`/`sector_bytes` — encrypted under a different
  key, indistinguishable from padding or noise to a decoder that stops after
  the first one. This is separate from the content-slot override map above
  (which lives *inside* `sector_bytes`, not after it, since `content` has no
  declared length of its own — §4.2); the two mechanisms can be combined for
  two independent layers of hiding on one physical code.
- **Keys and content are the same shape.** A `key_material`-only code and a
  small code carrying a hidden override map both look like a `part_meta` +
  `core_meta_item` of high-entropy byte strings, optionally followed by a
  content slot that's equally high-entropy. Nothing marks "this is a key,"
  "this is locked content," or "this is just a normal code with some
  padding."
- **Ephemeral-by-default caching is recommended.** Implementations SHOULD
  NOT persist encrypted content they cannot yet decrypt beyond the current
  session, unless a `key_material` scanned alongside it has
  `retain_key = true`. Storage with no record of "content I can't open"
  reveals less than storage that has one. Note that `retain_key` defaults to
  `true` (§9, "Decryption keys") as a usability default for the common
  treasure-hunt case; privacy-sensitive authors SHOULD explicitly set
  `retain_key: false` on key codes distributed in contexts where deniability
  matters.

None of this is mandatory, and most uses of TagDrop (sticker trails,
treasure hunts) won't need any of it — but the format doesn't preclude it,
and each property above is a deliberate small design choice rather than an
afterthought. These are properties of the *format*; an implementation that
logs scan history, retains keys against a user's wishes, or makes network
requests undermines them regardless of what the bytes on the wire look like.

### Passphrase-based key derivation

Instead of a separate key code, an author MAY derive the AES-256-GCM key from
a shared passphrase using PBKDF2-HMAC-SHA256. Three optional fields in
`core_meta_item` signal this:

| Key | Field | Type | Description |
|---|---|---|---|
| 37 | `kdf_alg` | uint | KDF algorithm: `1` = PBKDF2-HMAC-SHA256 |
| 38 | `kdf_salt` | bytes (16) | Random salt; unique per encryption |
| 39 | `kdf_iters` | uint | PBKDF2 iteration count; default `100000` if absent |

When `kdf_alg = 1` is present in `core_meta_item` alongside a candidate
override blob (the content slot, §4.2 — see "Where this blob lives" above),
the reader MUST:

1. Prompt the user for a passphrase.
2. Derive a 32-byte AES-256-GCM key:
   `PBKDF2-HMAC-SHA256(passphrase, kdf_salt, kdf_iters, 32 bytes)`
   — WebCrypto: `SubtleCrypto.deriveKey({name:"PBKDF2", ...})`;
   Android: `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`.
3. Try the derived key against the override blob exactly as for a
   `key_material` key — authentication-tag failure means wrong passphrase.
4. On success: the derived 32-byte key MAY be retained (same
   `retain_key` semantics as a scanned key code — see above). Retaining
   the derived key, not the passphrase itself, is RECOMMENDED: it avoids
   storing plaintext passphrases while still skipping the PBKDF2 round on
   the next scan of the same content.

`kdf_salt` MUST be unique per encryption (16 random bytes). A reused salt
under the same passphrase reduces security to the equivalent of reusing an
AES-GCM nonce. The `kdf_iters` value SHOULD be omitted when equal to
`100000` (the default saves two CBOR bytes).

Passphrase and `key_material` modes are mutually exclusive per code: a
passphrase-encrypted code has `kdf_alg`/`kdf_salt` in its `core_meta_item` but
no `key_material` field and no separate key QR; a key-code-encrypted code has
neither `kdf_alg` nor `kdf_salt` and is unlocked by a separately distributed
key code. If a code anomalously carries both `kdf_alg` and `key_material`, a
reader SHOULD attempt `key_material` first (no user interaction required)
before falling back to the passphrase prompt. The trial-decryption mechanism
works identically once a key is in hand — the derivation step is simply the
extra work the passphrase path adds before that.

---

## 10. Verified Authorship (Signatures)

A payload may optionally be **signed**, proving "the holder of a particular
private key produced this exact payload." Signing is orthogonal to
encryption (§9) and to content-addressing (§3, §4.4) — a signed code has the
same `cache_id` / `root_hash` / `content_sha256` / `bulky_meta_sha256` as its
unsigned equivalent, and signing is **opt-in**: most TagDrop codes (stickers,
treasure hunts, paper backups) need no signature at all.

| `signature_algorithm` value | Algorithm |
|---|---|
| 0 (or absent) | Unsigned |
| 1 | ML-DSA-44 (Dilithium2, FIPS 204) |
| 2–255 | Reserved |

| Key | Field | Type | Lives in |
|---|---|---|---|
| 32 | `signature_algorithm` | uint (opt) | `core_meta_item` |
| 33 | `signature` | bytes (2420, opt) | `bulky_meta_item` |
| 34 | `signer_pubkey` | bytes (1312, opt) | `bulky_meta_item` |
| 35 | `signer_id` | bytes (8, opt) | `core_meta_item` |
| 36 | `signer_label` | text (opt) | `core_meta_item` |

**Implementation status:** specified for forward-compatibility, not yet
implemented in either reference implementation (§15). ML-DSA-44 is not
available in `java.security`/Android's crypto providers or in
`SubtleCrypto` (Web Crypto), so adding it requires a new dependency in both
— e.g. [BouncyCastle](https://www.bouncycastle.org/) for the Kotlin app, and
a pure-JS PQC library (such as
[`@noble/post-quantum`](https://github.com/paulmillr/noble-post-quantum))
for the browser tools, which currently depend on nothing but a QR CDN
script. Readers that don't recognise keys 32–36 ignore
them per §3's forward-compatibility rule and treat the code as unsigned.

**Why post-quantum, not ECDSA/Ed25519?** Shor's algorithm breaks the
discrete-log and elliptic-curve problems outright — a future quantum
computer doesn't just weaken ECDSA/Ed25519, it forges signatures under those
schemes entirely. By contrast, Grover's algorithm only *halves* AES's
effective key length, which is why AES-256-GCM (§9) needs no change for
quantum resistance. A signature scheme adopted now should not be one that a
sufficiently large quantum computer invalidates retroactively for every code
ever signed with it. ML-DSA-44 ([Dilithium2](https://pq-crystals.org/dilithium/), NIST security category 2, [FIPS
204](https://csrc.nist.gov/pubs/fips/204/final), standardized 2024) is a lattice-based scheme with no known efficient
quantum attack, and its sizes are **fixed regardless of message length** —
2420-byte signature, 1312-byte public key, 2560-byte private key (never
transmitted). For small codes (a few hundred bytes) that's significant
overhead; for content already spanning multiple chunks (§4.2) — e.g. an
essay of a few KB — a constant ~2.4 KB signature is proportionally minor,
and the public key (§ below) is amortized across an entire trail or
collection.

**Signed message:** the full (untruncated) `SHA-256(core_meta_item ||
bulky_meta_item || content)`, with keys 32–36 (this section's own fields)
absent from `bulky_meta_item` — i.e. the SHA-256 of the exact reassembled
stream (§4.2) an unsigned payload would produce. `§4.4` already specifies
this exact hash for `root_hash`, just truncated to its first 8 bytes;
signing instead uses the full 32 bytes, since a signature scheme needs a
full-length digest, not an 8-byte one. For a Paper, this means computing
`root_hash` and computing the signed message are **one and the same SHA-256
call** — there's no separate bootstrapping step or ordering question ("hash
first, then sign over the hash"): compute it once, take the first 8 bytes
for `part_meta`'s `root_hash`, and feed the full 32 bytes to `Sign`/`Verify`.
For a Content payload the same formula applies even though there's no
`root_hash` to double up with (`part_meta` carries `cache_id` instead, hashed
only over `content` per §4.4) — the signed-message formula doesn't change
shape between the two payload kinds. In both cases, signing happens last and
feeds back into nothing — `cache_id`/`root_hash`/`content_sha256`/
`bulky_meta_sha256` are identical whether or not keys 32–36 are subsequently
added.

**Verification:** a verifier strips keys 32–36 from `bulky_meta_item`,
recomputes the same full SHA-256 over `core_meta_item || bulky_meta_item ||
content`, and checks `signature` (key 33) against that hash using
`signer_pubkey` (key 34) via ML-DSA-44 `Verify`. `signer_id` (key 35) =
`SHA-256(signer_pubkey)[0:8]` — the same truncated-SHA-256-prefix convention
as `cache_id`/`collection_id`/`paper_id` (§3).

**Key caching (amortizing the ~3.7 KB first-use cost):** `signer_id` is
present on every signed payload, but `signer_pubkey` (1312 bytes) only needs
to be included on the *first* signed code an app encounters from a given
`signer_id` — the app caches `signer_id → (signer_pubkey, signer_label)`.
Subsequent codes from the same signer omit `signer_pubkey` and cost only
`signature` + `signer_id` (~2428 bytes). If a code omits `signer_pubkey` and
the verifier has no cached entry for its `signer_id`, the signature can't
yet be checked — it's held pending, and verified retroactively once a code
carrying that `signer_pubkey` is scanned, the same "complete opportunistically,
in any order" pattern as sector assembly (§5) and key matching (§9).

**Trust model:** trust-on-first-use (TOFU), like SSH host keys — there is no
PKI, certificate authority, or revocation. A verified signature proves "the
same private key signed this as everything else cached under this
`signer_id`," not a real-world identity. `signer_label` (key 36, free text)
lets an author attach a human-readable name (e.g. "City Parks Dept.
Trail"); it is self-asserted and meaningful only as a consistent label
across that signer's codes, exactly like a comment in `~/.ssh/known_hosts`.

**Downgrade:** stripping keys 32–36 from a signed code yields a valid
unsigned code with the same `cache_id`/`root_hash`/`content_sha256`/
`bulky_meta_sha256` — content-addressing doesn't distinguish "never signed"
from "signature removed." This is an accepted
limitation: a signature can be *removed* but not *forged* or *retargeted*
(ML-DSA verification ties `signature`, `signer_pubkey`, and the exact
payload bytes together), so the only thing an attacker can do is strip an
authorship claim, never add or substitute one.

**Interaction with §9's privacy properties:** a signature is an explicit,
persistent identity marker — the opposite of plausible deniability. Authors
relying on §9's privacy properties (encrypted content, key-only codes,
deniable framing) SHOULD NOT sign that content: a repeated `signer_id`
links codes to the same author even when their content is unreadable. Worse,
signing is **non-repudiable and retroactive** — a private key seized later
(device confiscation, coercion) lets an adversary prove authorship of every
code ever signed with it, including ones distributed long before the key was
compromised. For an author whose safety depends on deniability, a signing
key is a standing liability with no way to "take back" past signatures.
Verified Authorship and §9's privacy properties are intended as alternative
use cases of the same format, not a combination.

```
part_meta { 2: h'<8 random bytes>', 42: 0, 43: 1, 7: <n> }  // cache_id
core_meta_item {
  4: "text/markdown",
  32: 1,                           // signature_algorithm: ML-DSA-44
  35: h'<8-byte signer_id>',
  36: "Alice's Trail",              // optional human-readable label
}
bulky_meta_item {
  33: h'<2420-byte signature>',
  34: h'<1312-byte public key>',   // only on first code from this signer
}
content: h'<content bytes>'
```

---

## 11. Backward Compatibility: Legacy `data:` URIs

Codes containing a raw `data:` URI (without the `tagdrop:` scheme) are recognised and handled in **legacy mode**:

- A single code: the data URI is opened directly in the WebView viewer.
- Multiple codes: fragments are **dumb-appended** in scan order (the original V1 behaviour). The assembled string is interpreted as a `data:` URI.

New content should use the `tagdrop:` scheme. Legacy support will be maintained indefinitely.

---

## 12. NFC Transport (future)

The CBOR sequence (`version`, `type`, `part_meta`, `sector_bytes` — §2; the same bytes that get Base41-encoded into the `tagdrop:` URI) can be stored directly in an NFC NDEF record with:

- **TNF:** `0x02` (MIME Media type)
- **Type:** `application/vnd.tagdrop`
- **Payload:** the raw CBOR sequence bytes (no Base41 encoding needed for NFC binary storage)

Because `version` and `type` are carried in the payload bytes themselves (§2), one permanent MIME type covers every TagDrop format version — no per-version MIME subtypes needed, and the `tagdrop:<base41>` and raw-NDEF decoders share the same CBOR-sequence parsing, differing only in the Base41 step.

This lets the same physical sticker carry both a QR code (for camera scanning) and an NFC tag (for tap-to-read), with identical content. Android dispatches `application/vnd.tagdrop` NDEF records to the TagDrop app via intent filter.

A NFC-NDEF capable multi-tag sequence would use the same sectoring scheme (§4.1), where each NFC tag holds one sector's CBOR sequence. (NFC Type 2 tags at 1 KB are suitable for single-sector payloads; 8 KB tags can hold payloads needing more sectors.)

---

## 13. Alternative Carriers

The format is carrier-agnostic. Any medium that can carry a UTF-8 string supports the `tagdrop:` URI form. Any medium that carries raw bytes supports the raw CBOR sequence form.

| Carrier | Form | Notes |
|---|---|---|
| QR code | `tagdrop:` URI, alphanumeric mode; or raw CBOR sequence, byte mode | Primary target. Byte mode avoids Base41 overhead — denser, but not human-typable; best for non-initial sectors, which are always camera-scanned |
| Aztec code | `tagdrop:` URI | Higher density than QR at small sizes |
| Data Matrix | `tagdrop:` URI | Better damage resistance |
| JABCode (color) | `tagdrop:` URI or raw CBOR sequence | ~4× capacity of QR; see [jabcode/jabcode](https://github.com/jabcode/jabcode) |
| NFC NDEF tag | Raw CBOR sequence, MIME type | No Base41 overhead; supports tapping |
| Plain URL | `tagdrop:` as deep-link | QR of a URL that deep-links to app |

---

## 14. Version Negotiation

`version` is the first item of the envelope sequence (§2) — a single CBOR integer, decodable independently of everything that follows it. A reader encountering an unsupported `version` should stop immediately and show a human-readable "unsupported format version" message, without attempting to decode `type`, `part_meta`, or `sector_bytes` — a future version is free to redefine any of them, even to something other than CBOR.

**Additive fields vs. version bumps (issue #37):** §3's "unknown keys are
ignored" rule gives forward compatibility for fields that add *optional*
information an old parser can simply not act on (e.g. `description`, issue
#35; a future hash-commitment field, issue #36). It does **not** cover
fields that would change whether data an old parser already understands is
*complete* — an old parser that ignores an unrecognized "more sectors exist"
flag would silently treat a truncated `bulky_meta_item` or an incompletely
assembled reassembled stream (§4.2) as the whole thing, which is worse than
refusing outright (silent data loss vs. a visible error). Any *future*
mechanism that changes what "complete" means for a payload an old parser
already understands MUST therefore be gated by a version bump, not an
additive key, so old parsers stop per the rule above rather than silently
truncate.

*(The sectoring and redundancy mechanism in §4–§5 is itself exactly this
kind of change, but didn't need a version bump: it was introduced while
version 1 was still an undeployed draft — see the Status line at the top of
this document — so it defines what version 1 **is**, rather than being a
breaking change applied *to* an already-deployed version 1. The rule above
governs changes made *after* a version has shipped real-world codes; it
doesn't apply retroactively to a still-Draft version.)*

Version history:

**Version 1** (initial release, current)

- `version`/`type`/`part_meta`/`sector_bytes` envelope as a 4-item CBOR
  sequence (§2); `type` 0–1 for Content / Paper. Every payload — one code or
  many — is one or more **sectors**, addressed by `part_meta`'s
  `sector_index`/`sector_count` (§4.1); there is no separate manifest/chunk
  distinction, and a single-code payload is simply `sector_count` 1.
- The reassembled stream (§4.2), built by concatenating every sector's
  `sector_bytes` in order, is `core_meta_item || bulky_meta_item || content`
  — small/plain identity and declaration fields, then whatever's bulky or
  worth compressing, then raw content bytes with no declared length.
- Payload map integer keys 2–19, 20–24, 26–28, 30–40, 42–52. Keys 1, 6, 9, 10
  retired (superseded by the envelope and by `part_meta`'s sector fields —
  §3). Key 25 reserved for a future binary image icon; key 29 reserved,
  unused (§9).
- Base41 URI encoding: `tagdrop:<base41>`. DEFLATE compression, independently
  for `content` (key 12) and `bulky_meta_item` (key 45). Content-addressed
  IDs via SHA-256: `cache_id` for Content payloads, `root_hash` for Paper
  payloads (§4.4).
- Paper (`type` 1) with file directories, `set`/`slug` navigation, and
  `related` paper hints with optional `lat`/`lng` placeholder coordinates
  (keys 26/27). No practical size limit on `files[]`/`related[]` (issue #37,
  §4.3) now that `bulky_meta_item` can span as many sectors as it needs.
  TagDropNet relative-link and `tagdrop://` navigation (§7).
- Author-declared location for a Content/Paper's own physical placement,
  reusing `lat`/`lng` (keys 26/27) at `core_meta_item` level — distinct from
  a `related` entry's hint-location use of the same keys. `radius_m`
  (key 48) adds a circle-of-uncertainty radius in meters, valid wherever
  `lat`/`lng` appears (core-level or inside a `related` entry).
  `prefer_declared_location` (key 49, default `false`) lets the author's
  declared coordinates take priority over the device's live GPS fix at scan
  time, for placements where the scanning device may lack a GPS lock or the
  author's coordinates are known to be more reliable (§4.2).
- Single-loss erasure coding (issue #37): a full-XOR parity sector
  (`parity_scheme` 1) at `sector_index == sector_count` reconstructs exactly
  one lost data sector per payload (§5).
- Ad-hoc collections via `collection_id`/`collection_label`/`collection_tag` (keys 17–19). Emoji `icon` (key 24).
- Content-teaser `description` (key 40, both payload kinds) and per-file `description` (key 41, in `files[]` entries) — distinct from `label`/`hint` and from the short-caption `title` (key 51, both payload kinds) (§4.3).
- AES-256-GCM hidden override maps (§9), Content payloads only: self-contained `nonce||ciphertext||tag` blob carried in the reassembled stream's `content` slot, applied after compression. Optional non-binding `encryption` hint (key 28). `key_material`/`retain_key` (keys 30/31) matched by trial decryption ("discovery, not declaration"). PBKDF2-HMAC-SHA256 passphrase derivation via `kdf_alg`/`kdf_salt`/`kdf_iters` (keys 37–39).
- ML-DSA-44 post-quantum signatures (§10): `signature_algorithm`/`signature`/`signer_pubkey`/`signer_id`/`signer_label` (keys 32–36), additive and not affecting `cache_id`/`root_hash`/`content_sha256`/`bulky_meta_sha256`. Specified for forward-compatibility; not yet implemented in reference implementations.
- Author-declared `created_at` (key 52, both payload kinds): optional Unix timestamp (seconds) recording when the payload was authored, taken from the authoring device's clock at encode time — self-declared like `lat`/`lng`, not a verified/trusted timestamp.

---

## 15. Reference Implementations

- **Android app:** `app/src/main/java/com/github/mofosyne/tagdrop/data/format/`
  - `TagDropCodec.kt` — encode/decode both payload types; `contentId()`, `createContentSectors()`, `createPaper()`
  - `Base41.kt` — TagDrop's own alphabet, packed like RFC 9285 Base45 (§2)
  - `MiniCbor.kt` — minimal CBOR encoder/decoder; supports arrays (major 4), nested maps, float64 (major 7), and top-level CBOR sequences (RFC 8742) for the version/type envelope
  - `SectorAssembler.kt` — multi-sector assembly with SHA-256 verification; tracks any number of in-flight `(type, cache_id)` groups concurrently
  - `TagDropLinkResolver.kt` — resolves `tagdrop://<rootHash>/<slug>` navigation links; also locates the `style.css` sibling for `text/markdown` content (§7)
  - `MarkdownRenderer.kt` — renders `text/markdown` content to HTML (§7) via CommonMark

- **Android database:** `app/src/main/java/com/github/mofosyne/tagdrop/data/db/`
  - `FoundCache.kt` — Room entity for scanned file caches
  - `ScannedPaper.kt` — Room entity for scanned paper manifests
  - `AppDatabase.kt` — Room database with migrations

---

## 16. Design Notes and Alternatives Considered

**Why not extend `data:` URI syntax?** (issues #2, #4, #13) Adding parameters like `;seq-id=`, `;seq-total=`, `;crc=` to the data URI was the original approach. It fails because data: URIs are opaque to QR readers — there's no way to route them to the app by scheme. The `tagdrop:` scheme gives us OS-level routing and a clean separation between the envelope and payload.

**Why a version/type envelope instead of URI path segments or per-map keys?** An earlier draft put `v1/<type>/` in the URI path and a `version` key inside each payload map. That works for QR, but raw-byte carriers (NFC NDEF, JABCode raw — §12/§13) have no URI wrapper, so type/version information would either be lost or have to be guessed from which map keys happen to be present — fragile, and ambiguous for future payload types. Prefixing every sector with a CBOR Sequence (RFC 8742) led by `CBOR(version) || CBOR(type)`, 1 byte each for the foreseeable range of values — makes the same bytes self-describing on every carrier: Base41-encode them for `tagdrop:<base41>`, or store them raw in an NDEF record, with identical decode logic either way. It also lets the URI collapse to `tagdrop:<base41>` (no `//`, no `/<type>/` segment), gives a clean disambiguation rule against `tagdrop://<rootHash>/<slug>` navigation links (§2), and — being a sequence rather than a CBOR array — costs one less byte than an equivalent `[version, type, part_meta, sector_bytes]` array and doesn't require everything after `version`/`type` to be CBOR-wrapped, leaving room for raw non-CBOR bytes in a future version. `version` lives *only* in the envelope, not redundantly inside `part_meta` too: two fields claiming to describe the same fact can disagree, forcing a reader to pick which one to trust — the same class of ambiguity RFC 9112 §6.3 closes off by forbidding conflicting `Content-Length`/`Transfer-Encoding` framing in HTTP/1.1. CBOR's own self-describing-data convention (the tag-55799 "magic number", RFC 8949 §3.4.5.3) is likewise an external prefix rather than a duplicated internal field, reinforcing that self-description belongs in the envelope.

**Why not NDEF as the primary format?** (issue #16) NDEF is a memory-layout format for NFC chips with a specific capability container. Adapting it for QR codes adds complexity without benefit — the QR code already handles error correction and binary framing. We use NDEF only as a transport option for NFC tags (§12).

**Binary mode vs alphanumeric Base41:** Raw binary QR codes store 8 bits/char. Alphanumeric Base41 stores 2 bytes in 3 characters at 5.5 bits/char = ~8.25 bits/byte of original data. The tiny efficiency loss is worth the interoperability gain for most codes: alphanumeric QR codes are more reliably decoded by all readers, and the `tagdrop:` prefix is human-readable/typable. Non-initial sectors are the exception — they're always camera-scanned, never hand-typed or shared as text — so the Android reference reader also accepts a QR byte-mode segment carrying the raw CBOR sequence directly (no `tagdrop:`/Base41 wrapper), per §13. The web generator (`tools/generator/index.html`) splits content too large for one QR into a Manifest + Chunks and, by default, renders Chunk QR codes in binary mode (toggleable back to alphanumeric `tagdrop:` URIs); the Manifest QR is always alphanumeric, since it must stay human-typable/shareable like a Single. The Android app's only multi-sector generator, `ShareQrActivity` (re-sharing an already-cached item too large for one QR), still emits alphanumeric `tagdrop:` URIs for every sector.

**Compression:** DEFLATE was chosen over LZMA (issue #2) because it is available in every Java/Android standard library (`java.util.zip`), requiring no dependency. LZMA achieves better ratios for larger payloads but is a future extension (compression value 2).

**Structured append in QR spec:** QR's built-in structured append (up to 16 codes) is not portable across barcode formats and is poorly supported by many readers. Our sector approach (§4.1, §5) works with any 2D barcode type and supports up to 2^32 sectors.

**No explicit folder hierarchy:** Papers list files as flat slug strings. Slugs that contain `/` (e.g. `images/photo.jpg`) create virtual path conventions without requiring a tree structure in the CBOR or the database. String equality on the full slug is the only lookup operation needed. This keeps the directory format simple and avoids directory-traversal edge cases.

**Non-HTML content types (images, audio, MIDI):** The cache stores raw bytes for any MIME type, and the Android WebView can serve them all via `WebViewClient.shouldInterceptRequest`. When a loaded HTML page contains `<img src="tagdrop://...">`, `<audio src="tagdrop://...">`, or any other subresource reference, `shouldInterceptRequest` intercepts the fetch, looks up the slug in the local DB, and returns a `WebResourceResponse` with the cached bytes — no network involved. MIDI files require a JS player library embedded in the same HTML file (the MIDI bytes are served as `audio/midi` via the same mechanism). Purely binary payloads (a standalone image, a MIDI file) are displayed/played by wrapping them in a minimal HTML page that references the tagdrop:// URI. The navigation flow (`shouldOverrideUrlLoading`) and the subresource flow (`shouldInterceptRequest`) are independent: the former fires when the user clicks a link and loads a new top-level page; the latter fires for every embedded asset on the current page. Both resolve through the same `TagDropLinkResolver`.
