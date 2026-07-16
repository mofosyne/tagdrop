# TagDrop Encoding Specification

**Version:** 7 (byte-mode QR/JABCode framing now declares a namespace,
correcting a carrier-scoping gap in version 6's Type ID safety claim; see
§14 "Version history")
**Status:** Draft — no real-world deployments yet (no printed or
distributed codes), so it may still change incompatibly without a version
bump. Once the first real code ships, that freeze point ends: breaking
changes from then on require a version bump (§14). Feedback welcome via
GitHub issues.

**Relationship to QDEF:** TagDrop's envelope is built on the Record/Wrapper
primitives defined by [QDEF](https://github.com/mofosyne/qdef)
(`docs/QDEF-SPEC.md` there) — a general-purpose binary container for
multi-action barcodes/NFC, developed alongside TagDrop but meant to be a
reusable, TagDrop-independent standard. TagDrop registers four QDEF Record
Types (§3) and uses QDEF's Split/Compress Wrapper Records (§5) instead of a
bespoke sectoring/compression scheme. This is *not* the same as wrapping
TagDrop's ASCII QR codes in QDEF's binary container — see §2's carrier
table for why the `tagdrop:` URI path and the byte-mode/NFC path share the
same Record bytes but differ in outer framing.

---

## 1. Overview

TagDrop encodes self-contained digital content into one or more 2D barcodes (QR, Aztec, Data Matrix) for physical placement — on walls, along trails, inside objects. A finder scans the code(s) with the TagDrop app (or any QR reader that understands the URI scheme) to retrieve the content.

The format is designed to:

- Fit rich content (HTML pages, images, audio snippets, plain text) into one or more standard QR codes
- Support **geographic distribution**: chunks of a large payload can be placed at different physical locations, so finding all pieces is part of the experience
- Be **future-proof**: versioned, binary-compact, with per-field forward compatibility (§3's even/odd criticality rule) instead of only whole-version compatibility
- Remain **backward-compatible** with the legacy `data:` URI approach
- Be **transport-agnostic**: the same Record bytes can be carried by QR codes, NFC NDEF tags, or other 2D barcode formats (Aztec, JABCode)

---

## 2. Wire Framing

Every TagDrop code carries a **CBOR Sequence** ([RFC 8742](https://www.rfc-editor.org/rfc/rfc8742) — concatenated CBOR data items, no enclosing array) of one or two **Records** (QDEF terminology — each Record is a flat CBOR map, routed by its own `0` key, per QDEF-SPEC.md §3). A code always carries a **Preview** Record; a payload with a body (§4) also carries either the complete **Body** Record (if it fits alongside Preview in one code) or one **Split-Wrapper**-wrapped Body fragment (§5, multi-code case).

**Outer framing differs by carrier — the Record Sequence bytes themselves do not:**

| Carrier | Framing | Why |
|---|---|---|
| `tagdrop:` URI (QR alphanumeric mode) | `tagdrop:` + Base41(Record Sequence bytes) | The `tagdrop:` scheme itself is the dispatch signal — no QDEF magic header needed (QDEF-SPEC.md §1: "any application that already defines its own text/URI scheme should encode its envelope directly under that scheme, not wrap it in QDEF"). |
| Byte-mode QR / other 2D barcode | QDEF magic (4 bytes) + a declared-namespace discriminator (5 bytes) + Record Sequence bytes | No scheme, no prior dispatch signal exists — unlike the other two carriers, nothing external isolates this carrier's Type IDs from an unrelated app's, so it MUST declare TagDrop's namespace (§2.1a) rather than use the discriminator's cheapest "no namespace" value. |
| NFC NDEF | Record Sequence bytes only, as the payload of an `application/vnd.tagdrop` MIME record | NDEF's own MIME-type field is the dispatch signal, same reasoning as the URI case — carrying QDEF's magic header here would be redundant, matching QDEF-SPEC.md §2's own guidance for NFC. |

This is the "near 1:1" property: an encoder builds the Record Sequence bytes once, then picks a framing based only on which carrier it's targeting — Base41-encode them, prefix them with the QDEF magic, or hand them to NDEF raw. Nothing about the Records' own structure changes per carrier.

### 2.1 Record Types

TagDrop registers four QDEF Record Type IDs, each with its own independent key namespace (QDEF-SPEC.md §3.1's Type-ID routing gives every registered Type its own field table for free — no more "valid in Content only" / "Paper only" footnotes sharing one key space, as the old design needed). IDs are small self-allocated values in QDEF's `32768`+ "First Come First Served" tier (QDEF-SPEC.md §4):

| Type ID | Record | Contains |
|---|---|---|
| `48250` | Content-Preview | Small, always-plain fields — identity, hint, location, collection, encryption/signing metadata. See §3.1. |
| `56990` | Content-Body | `content` bytes, plus the large signature fields. See §3.2. |
| `34456` | Paper-Preview | Small, always-plain fields — identity, `set`/`slug`/`domain`, location, collection. See §3.3. |
| `58984` | Paper-Body | `files[]`/`related[]` directory data, plus the large signature fields. See §3.4. |

All four Type IDs are deliberately **even** — QDEF-SPEC.md §3.1 classifies an even-uint Type ID as "Standard record type," always globally interpreted **regardless of whether a namespace is declared or not**, versus an odd-uint Type ID ("Scoped record type"), which requires a preceding namespace declaration or must be treated as an abort. Staying even is what lets these four Type IDs work unmodified across every carrier (§2.1a) — the Record Sequence bytes are identical whether or not the carrier they're framed in happens to declare a namespace.

**Why small values, not large random ones.** These were originally 64-bit CSPRNG values (collision-avoidance for a Type ID that might appear in a shared, generic QDEF container alongside other apps' Records). QDEF-SPEC.md §2/§3.5 now formalizes that an application carrying QDEF content under its own URI scheme — as TagDrop's `tagdrop:` carrier already does — has no need for that collision margin at all on that carrier specifically: the scheme itself is a recognition boundary no other app's decoder crosses, so a small, self-allocated even Type ID from the `32768`+ tier is exactly as collision-safe *on that carrier* as a 64-bit value would be, at roughly a third of the byte cost (4 bytes per occurrence vs. 10). **This safety argument is carrier-specific, not a property of the Type ID values themselves** — the `32768`+ tier's width alone (roughly 32,767 possible even values) is nowhere near collision-safe for a carrier with no external isolation; see §2.1a for why byte-mode QR/JABCode needs a declared namespace to use these same small values safely. Content-Preview/Content-Body and Paper-Preview/Paper-Body were all re-minted to this tier for version 6 (§14); Paper-Preview/Paper-Body had already been re-minted once before, for version 4, to fix an unrelated odd/even parity violation — that fix is superseded by this one.

A **key-only** code (§9, "Decryption keys") is a Content-Preview Record with no accompanying Body at all — carrying `key_material` but no content, exactly as today's key codes do; nothing about that case changes.

### 2.1a Namespace declaration (byte-mode QR/JABCode only)

`tagdrop:` URI and NFC NDEF both carry external dispatch context — the
scheme, the MIME type — before a decoder ever looks at a Type ID, so
declaring a namespace on those carriers would be pure overhead for no
safety gain (§2's carrier table). Byte-mode QR/JABCode has no such
context: it's exactly the "otherwise-unidentified byte stream" case
QDEF's magic header exists for, so a decoder scanning one has nothing
telling it "this is TagDrop" before it looks at a Type ID — an unrelated
app that also self-allocated Type ID `48250` in the same `32768`+ tier
would be indistinguishable from TagDrop's own Content-Preview without a
declared namespace to disambiguate. This carrier therefore MUST declare
one, following QDEF-SPEC.md §3.5's populated-discriminator shape.

TagDrop's namespace is the first 4 bytes of `SHA-256("io.github.mofosyne.tagdrop")`:

```
namespace = SHA-256("io.github.mofosyne.tagdrop")[0:4] = 89 D4 14 E0
```

encoded as a bare CBOR byte string — the discriminator item itself,
immediately after the magic bytes, with no wrapping map (QDEF-SPEC.md
§3.5's `byte string` discriminator shape): `h'89d414e0'`, 5 bytes total
(1-byte CBOR header + 4-byte value). No recoverable-name field is
included — the value is self-certifying (recompute the hash from the
name above and compare) and this document itself is the record of what
the value means, so a redundant on-wire name would only cost bytes for
no benefit here. The reverse-domain-qualified source string (rather than
a bare `"tagdrop"`) follows QDEF-SPEC.md §4's guidance to avoid
hash-of-generic-word collisions.

Content-Preview/Content-Body/Paper-Preview/Paper-Body's Type IDs
themselves are unchanged on this carrier — they stay the same small even
values used everywhere else (§2.1), since an even Type ID is valid with
or without a declared namespace. Only the outer framing differs: byte-mode
QR/JABCode's discriminator carries this namespace value instead of the
"no namespace" bare `0` that `tagdrop:` URI and NFC never even need to
carry in the first place (§2's carrier table).

### 2.2 Even/odd key criticality

TagDrop's own Record fields (§3) are, in this version, **entirely odd-numbered (optional)** — every field defined below is safe for an old decoder to ignore if it doesn't recognize it, degrading gracefully rather than misinterpreting anything. Even key numbers are deliberately left unused in every TagDrop Record Type for now, reserved as headroom for a future field that genuinely needs must-understand-or-abort semantics (QDEF-SPEC.md §3.2's even/odd rule) — a real capability the old single-namespace, ignore-everything-unknown design didn't have (tracked as tagdrop#63, now resolved by adopting QDEF's rule directly instead of inventing an equivalent). Key `0` (the Type ID itself) is the one mandatory exception, per QDEF's own rule.

For values 0–23, a CBOR unsigned integer is exactly **one byte** (RFC 8949 major type 0). Record Type IDs (§2.1) cost 4 bytes each (a `32768`+ value plus its map key) — still more than the old 1-byte `version`/`type` pair, but paid once per code, same order of magnitude as before; see the QDEF byte-overhead discussion this project relayed back upstream (mofosyne/qdef PR #2 and the own-URI-scheme isolation guidance, §2.1) for the actual numbers.

### Navigation links (not QR payloads)

HTML pages embedded in TagDrop caches can link to other files and papers using one of three forms:

```
tagdrop://<domain>/<slug>
tagdrop://@<rootHash-hex>/<slug>
tagdrop://<domain>@<rootHash-hex>/<slug>
```

- `tagdrop://<domain>/<slug>` — **floating**: resolved by name (§7 "Domains"); the paper behind a domain can change over time.
- `tagdrop://@<rootHash-hex>/<slug>` — **pinned**: resolved to one exact, immutable paper.
- `tagdrop://<domain>@<rootHash-hex>/<slug>` — both: the hash is authoritative for resolution, `domain` is a decorative label only.

`rootHash` is the Paper's `root_hash` (§4.4) — the 8-byte SHA-256 of its Preview and Body, signature fields stripped (`Preview' || Body'`, §4.4) — lowercase-hex-encoded (16 characters). `domain` is a human-readable name a paper claims for itself (§7 "Domains") — unlike `rootHash`, it is never unique, and is resolved by lookup rather than exact match. `slug` is the file's identifier within the resolved paper. The TagDrop app intercepts these links in its WebView and resolves them against the local scanned-paper database — no network needed.

These three forms reuse standard URI **authority** syntax (`[userinfo "@"] host`, RFC 3986 §3.2.1) instead of inventing TagDrop-specific punctuation: `domain` occupies the userinfo slot, `rootHash` the host slot. The `@` is what decides resolution, never the shape of the text around it:

- No `@` → the entire host is `domain`, looked up by name — **never** attempted as a hash, even if it happens to be the same length and shape as one.
- `@` present → whatever follows it is always `rootHash`, looked up by exact match; whatever precedes it (possibly nothing) is an optional, purely cosmetic label, never used for resolution.

See "Resolving a domain link" in §7 for why this needed to be a syntactic split rather than a lookup-order rule, and §16 for other markers considered.

**Why hex, not Base41, for `rootHash`?** Base41's alphabet includes `:`, which is fine inside the *path* of an opaque URI like `tagdrop:<base41-cbor-sequence>` but not inside a URL's *authority* (host[:port]) component — a `:` there starts the port subcomponent per the WHATWG URL Standard, and anything after it that isn't a bare port number is a hard parse failure for the whole URL. Since `rootHash` sits in the authority position of a `tagdrop://` link, a Base41-encoded root hash containing `:` (roughly 1 in 4, since `:` is 1 of 41 alphabet characters) would make the link unparseable. Plain lowercase hex has no such character, at the cost of 4 extra characters (16 hex vs 12 Base41 for 8 bytes) — cheap, since navigation links are clicked/typed, not scanned from a QR code. This is the same hazard that ruled out `:` as the `domain`/`rootHash` marker (§16) — just relocated from inside the hash to between domain and hash.

**Disambiguation from encoding URIs:** encoding payloads never contain `//` — `tagdrop:<base41-cbor-sequence>` has no authority component. Navigation links always do — `domain`/`rootHash` serve as the link's authority. Base41's alphabet has no `/` character at all, so it can never appear anywhere in a Base41-encoded string, let alone right after the scheme. Encoding URIs and navigation links are therefore unambiguously distinguishable by whether `//` follows the scheme, independent of the `@` marker.

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

Each of TagDrop's four Record Types (§2.1) has its own independent key
namespace — a field number means nothing outside the Record Type it's
listed under (QDEF-SPEC.md §3.1). Per §2.2, every field below is odd
(optional/ignorable); even numbers are unused, reserved headroom.

### 3.1 Content-Preview (Type `48250`)

Always plain, unwrapped, present on every code carrying this payload
(§5.1). `cache_id` is the content-addressed identity used for
dedup/caching across authors — see the note after this table for why it's
scoped differently from Paper's `root_hash`.

| Key | Field | Type | Notes |
|---|---|---|---|
| 1 | `cache_id` | bytes (8, opt) | `SHA-256(content bytes)[0:8]` — absent for a key-only code (§9), which has no content to hash |
| 3 | `hint` | text (opt) | |
| 5 | `mime_type` | text (opt) | |
| 7 | `filename` | text (opt) | |
| 9 | `title` | text (opt) | |
| 11 | `description` | text (opt) | |
| 13 | `collection_id` | bytes (8, opt) | |
| 15 | `collection_label` | text (opt) | |
| 17 | `collection_tag` | text (opt) | |
| 19 | `icon` | text (opt) | |
| 21 | `pixel_art` | bool (opt, default `false`) | Render with nearest-neighbour scaling; see §7 "Pixel art" |
| 23 | `lat` | float64 (opt) | Author-declared location; see §4.2 |
| 25 | `lng` | float64 (opt) | Longitude, same scope as `lat` |
| 27 | `radius_m` | float64 (opt) | Circle-of-uncertainty radius in meters |
| 29 | `prefer_declared_location` | bool (opt, default `false`) | |
| 31 | `location_label` | text (opt) | |
| 33 | `key_material` | bytes (32, opt) | See §9 |
| 35 | `retain_key` | bool (opt, default `true`) | See §9 |
| 37 | `encryption` | uint (opt) | Non-binding hint; see §9 "Discovery, not declaration" |
| 39 | `kdf_alg` | uint (opt) | See §9 |
| 41 | `kdf_salt` | bytes (16, opt) | See §9 |
| 43 | `kdf_iters` | uint (opt, default `100000`) | See §9 |
| 45 | `signature_algorithm` | uint (opt) | See §10 |
| 47 | `signer_id` | bytes (8, opt) | See §10 |
| 49 | `signer_label` | text (opt) | See §10 |
| 51 | `in_reply_to` | bytes (8, opt) | `cache_id`/`root_hash` of the single parent this replies to; see §7 |
| 53 | `created_at` | uint (opt) | Author-declared Unix timestamp; not independently verified |
| 55 | `source_url` | text (opt) | See §17 |

### 3.2 Content-Body (Type `56990`)

Optionally Compress-wrapped (QDEF-SPEC.md §4.1 Type 3) and/or
Split-wrapped (§5) when it doesn't fit alongside Preview in one code.

| Key | Field | Type | Notes |
|---|---|---|---|
| 1 | `content` | bytes | The payload bytes — may be a hidden encrypted override map (see below) |
| 3 | `signature` | bytes (2420, opt) | See §10 |
| 5 | `signer_pubkey` | bytes (1312, opt) | See §10 |

**Encrypted override map.** `content` may be a self-contained AES-256-GCM
blob (§9) that, once decrypted, is itself a small CBOR map — its own
independent local namespace, unrelated to Content-Preview's numbering —
overlaying Content-Preview's same-purpose fields:

| Key | Field | Type |
|---|---|---|
| 1 | `hint` | text (opt) |
| 3 | `mime_type` | text (opt) |
| 5 | `content` | bytes (opt) |
| 7 | `filename` | text (opt) |

`encryption` (Content-Preview key 37), if present and non-zero, is an
optional hint that this is the case; its absence does NOT mean there's no
hidden override map — see §9, "Discovery, not declaration." Content-Preview's
own `hint`/`mime_type`/`filename`, if present, are the values shown before
(or without) a matching override key.

### 3.3 Paper-Preview (Type `34456`)

Always plain, unwrapped, present on every code carrying this payload
(§5.1).

| Key | Field | Type | Notes |
|---|---|---|---|
| 1 | `root_hash` | bytes (8) | `SHA-256(Preview canonical bytes \|\| Body canonical bytes)[0:8]` — see §4.3 for the exact formula and why this is scoped differently from Content's `cache_id` |
| 3 | `hint` | text (opt) | |
| 5 | `set` | text (opt) | |
| 7 | `slug` | text (opt) | |
| 9 | `domain` | text (opt) | See §7 "Domains" |
| 11 | `step` | uint (opt) | This paper's absolute position within its `set` trail; see §4.3 "Trail steps and forks" |
| 13 | `collection_id` | bytes (8, opt) | |
| 15 | `collection_label` | text (opt) | |
| 17 | `collection_tag` | text (opt) | |
| 19 | `icon` | text (opt) | |
| 21 | `lat` | float64 (opt) | |
| 23 | `lng` | float64 (opt) | |
| 25 | `radius_m` | float64 (opt) | |
| 27 | `prefer_declared_location` | bool (opt, default `false`) | |
| 29 | `location_label` | text (opt) | |
| 31 | `signature_algorithm` | uint (opt) | See §10 |
| 33 | `signer_id` | bytes (8, opt) | See §10 |
| 35 | `signer_label` | text (opt) | See §10 |
| 37 | `in_reply_to` | bytes (8, opt) | See §7 |
| 39 | `created_at` | uint (opt) | |
| 41 | `source_url` | text (opt) | See §17 |
| 43 | `title` | text (opt) | |
| 45 | `description` | text (opt) | |
| 47 | `key_material` | bytes (32, opt) | Decryption key for other content (§9) — **Note: uses key 47 here, not key 33 as on Content-Preview.** Paper-Preview's key 33 is `signer_id` (§10), so `key_material`/`retain_key` occupy the next available odd slots (47/49) to avoid collision. See §9 for the full encryption design. |
| 49 | `retain_key` | bool (opt, default `true`) | Whether the app should remember `key_material` across sessions (§9) |

### 3.4 Paper-Body (Type `58984`)

Optionally Compress-wrapped and/or Split-wrapped, same as Content-Body. A
Paper has no `content` of its own — its body is entirely directory data.

| Key | Field | Type | Notes |
|---|---|---|---|
| 1 | `files` | bytes | `CBOR([...])`-encoded array of `files[]` sub-maps (below) — carried as a byte string, not a bare field-level array, per QDEF-SPEC.md §3.2's field-value-shape rule |
| 3 | `related` | bytes | `CBOR([...])`-encoded array of `related[]` sub-maps (below), same reasoning |
| 5 | `signature` | bytes (2420, opt) | See §10 |
| 7 | `signer_pubkey` | bytes (1312, opt) | See §10 |

**Why `cache_id` and `root_hash` are scoped differently, and neither is
"replaced" by Split's `group_id`.** Content's `cache_id` is deliberately
`SHA-256(content)` alone, not the whole Preview+Body — this is what lets
two authors who drop the identical bytes under different
hints/titles/collections dedup to the same `cache_id` (§4.4). Paper's
`root_hash` covers everything (there's no bare "content" to isolate a
Paper's identity to). QDEF's Split Wrapper, when used (§5), separately
computes its own `group_id` — a content hash of the *wrapped Body Record's*
bytes, verified by the decoder purely for reassembly integrity. `group_id`
happens to have the same *scope* as `root_hash` for a multi-code Paper, but
it is not the same field and must not be conflated in an implementation:
`group_id` doesn't exist for single-code payloads (nothing to reassemble),
while `cache_id`/`root_hash` are always present. `content_sha256`/
`bulky_meta_sha256` — the old design's own multi-sector integrity
hashes — are gone entirely, superseded by `group_id`'s mandatory
decoder-side verification (QDEF-SPEC.md FINDINGS.md #5) whenever Split is
actually in use.

**`files[]` entry keys** (each element decoded from Paper-Body's `files`):

| Key | Field | Type | Notes |
|---|---|---|---|
| 1 | `slug` | text | URL-safe name for this file within the paper |
| 2 | `mime_type` | text | MIME type of the file |
| 3 | `file_id` | bytes (8) | `cache_id` of the file's root code |
| 4 | `description` | text (opt) | Content teaser, e.g. "A poem to read" |
| 5 | `pixel_art` | bool (opt, default `false`) | Render with nearest-neighbour scaling; see §7 "Pixel art" |

**`related[]` entry keys** (each element decoded from Paper-Body's `related`):

| Key | Field | Type | Notes |
|---|---|---|---|
| 1 | `hint` | text | Human-readable label for this related paper |
| 2 | `set` | text (opt) | Collection set identifier |
| 3 | `slug` | text (opt) | Slug of the related paper |
| 4 | `paper_id` | bytes (8, opt) | Root hash of the related paper |
| 5 | `lat` | float64 (opt) | Location of the related paper |
| 6 | `lng` | float64 (opt) | Longitude, same scope as `lat` |
| 7 | `radius_m` | float64 (opt) | Circle-of-uncertainty radius in meters |
| 8 | `key_material` | bytes (32, opt) | Decryption key for that paper's content (§9) |
| 9 | `retain_key` | bool (opt, default `true`) | See §9 |
| 10 | `step` | uint (opt) | Absolute position of the related paper within its `set` trail; see §4.3 "Trail steps and forks" |

These sub-map keys are unchanged from the pre-QDEF design — `files[]`/
`related[]` already had their own independent local namespaces (CBOR map
keys are scoped per-map); moving them one level deeper, into Paper-Body's
byte-string-encoded `files`/`related` fields, doesn't affect their own
numbering.

---

## 4. Payload Types

### 4.1 Preview and Body

Every payload (Content or Paper) is exactly one **Preview** Record plus, if
it has one, one **Body** Record (§2.1, §3). Preview is always small, always
plain (never Compress- or Split-wrapped), and — per §5.1 — repeated on
every physical code that carries this payload, so a single isolated scan
always identifies what it found and shows a usable preview, regardless of
whether the Body has been fully reassembled yet. Body carries whatever
doesn't need to be in that early preview: `content` (Content) or
`files[]`/`related[]` (Paper), plus the large signature fields — optionally
Compress-wrapped (QDEF-SPEC.md §4.1 Type 3), and Split-wrapped (§5) when it
doesn't fit alongside Preview in one code.

This replaces the old design's single three-part `core_meta_item ||
bulky_meta_item || content` stream and its bespoke `part_meta` sectoring —
Preview/Body are two separate QDEF Records with their own Type IDs (§2.1)
rather than concatenated items inside one bespoke envelope, and multi-code
spanning is QDEF's generic Split Wrapper (§5) rather than a TagDrop-specific
`sector_index`/`sector_count` scheme. A payload that fits in one code is
just a code carrying both Records, unwrapped — there's no separate
"single-sector" shape to special-case, mirroring the old design's own
"no separate Single type" principle, just one layer up.

### 4.2 Preview fields

**Declared location and priority:** Preview may carry `lat`/`lng` — the
*author's declared* coordinates for this Content's or Paper's own physical
placement, useful when the scanning device lacks a GPS lock, or simply to
record where the code was placed regardless of whether it does. This is
distinct from a `related` entry's `lat`/`lng` (§4.3), which hints at a
*different*, not-yet-scanned paper's location rather than this payload's
own. An optional `radius_m` (float64) gives a circle-of-uncertainty radius
in meters around the point — valid wherever `lat`/`lng` appears, whether at
Preview level or inside a `related` entry. By default, a live GPS fix at
scan time takes priority over the declared location when both are
available — the declared location is only a fallback for when GPS is
unavailable. Setting `prefer_declared_location` (bool, default `false`)
flips that priority so the declared coordinates win even when a live GPS
fix is available, for placements where the author's coordinates are known
to be more reliable than whatever fix the scanning device manages (e.g.
deep indoors, under tree cover, in a basement). Implementations are
expected to resolve and store only the single effective `(lat, lng,
radius_m)` triple after applying this priority, not both candidate
locations.

**Explicit no fixed point.** Some drops have no single coordinate worth
recording at all — e.g. an item mailed to a recipient whose address the
author never geocoded, or one carried on a moving vehicle ("🚋 Tram 40")
rather than left at a point. Two ways to say so:

- `prefer_declared_location` (Content-Preview key 29, Paper-Preview key 27)
  set `true` while `lat`/`lng` are both absent. Ordinarily this key only
  reorders *priority* between two candidate locations (declared vs. live),
  but with no declared coordinates to prioritize there is nothing for it
  to prefer — so this combination is instead read as an explicit author
  assertion that this payload has no reliable fixed point, and a live GPS
  fix at scan time (which would otherwise fill the gap by default, per the
  priority rule above) MUST NOT be substituted for it.
- `location_label` (Content-Preview key 31, Paper-Preview key 29, text,
  optional) — a human-readable, non-coordinate
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

**Body's `content`** is the actual bytes (Content-Body key 1): a Content
payload's cache (raw or Compress-wrapped), or absent entirely for a Paper,
which has no content of its own — Paper-Body only ever carries `files`/
`related`. For a Content payload, `content` may also be a hidden encrypted
override map instead of plain content — see §9; this doesn't extend to
Paper, whose Body never carries anything content-shaped (`root_hash` is
always content-addressed over the whole Preview+Body, with no
random-`cache_id`-style exception).

Example — a Content payload, single code:

```
Content-Preview {
  3: "under the bridge",     // hint
  5: "text/html",            // mime_type
  7: "poem.html",            // filename
  13: h'<8 random bytes>',   // collection_id — optional, see §7 Collections
  15: "Spring Sticker Hunt", // collection_label — optional, see §7 Collections
  17: "springtrail2026",     // collection_tag — optional, see §7 Collections
  19: "🌳",                   // icon — optional, see §7 Icons
  1: h'<cache_id>',          // = SHA-256(content)[0:8], §4.4
}
Content-Body {
  1: h'<page bytes, raw or Compress-wrapped>',
}
```

Example — a Paper, single code:

```
Paper-Preview {
  3: "Trail Stop 3 — Oak Tree",  // hint
  45: "Day 2 of the sunset trail: a poem and a hand-drawn map", // description
  5: "sunset-trail",             // set
  7: "oak-tree",                 // slug
  13: h'<8 random bytes>',       // collection_id — optional, see §7 Collections
  15: "Spring Sticker Hunt",     // collection_label — optional, see §7 Collections
  17: "springtrail2026",         // collection_tag — optional, see §7 Collections
  19: "🌳",                       // icon — optional, see §7 Icons
  21: -33.8688,                   // lat — optional, author-declared location of this paper
  23: 151.2093,                   // lng — optional, author-declared location of this paper
  25: 25.0,                       // radius_m — optional, circle of uncertainty in meters
  11: 3,                          // step — this paper is stop 3 of the "sunset-trail" set
  1: h'<root_hash>',              // §4.4
}
Paper-Body {
  1: h'<CBOR([                   // files — directory of codes on this paper (local keys, see §3)
    {1: "index", 2: "text/html",    3: h'<file_id>', 4: "A poem to read"},
    {1: "map",   2: "image/svg+xml", 3: h'<file_id>', 4: "A hand-drawn map"},
  ])>',
  3: h'<CBOR([                   // related — hints to other papers (local keys, see §3)
    {1: "Next stop: the red letterbox 200m north", 2: "sunset-trail", 3: "letterbox",
     4: h'<paper_id>', 5: -33.8688, 6: 151.2093, 7: 50.0, 10: 4},
    {1: "Start of trail: town square notice board", 2: "sunset-trail", 10: 1},
  ])>',
}
```

A payload too large for one code Split-wraps its Body Record (§5) —
Preview stays whole and unwrapped, repeated on every code in the group, and
any code (not just a "first" one) can be scanned in any order or session.

### 4.3 Paper (Paper-Preview / Paper-Body)

A Paper is the **directory payload** for a physical paper (A4 sheet, sticker
board, poster). Think of it as a floppy disk's FAT: it lists every file on
the paper and can point toward related papers at other locations. Its
Preview/Body shape is shown in §4.2 above; Paper-Body never carries a
`content`-shaped field.

**`hint` vs. `description` vs. `title` (issue #35):** `hint` is the paper's
*name or location* — "Trail Stop 3 — Oak Tree" tells you where you are, not
what's on it (the same field name is used for a Content payload's own
teaser too — same field, same meaning, just describing a different kind of
payload, §4.2). `description` (optional, valid for both Record kinds) is a
content teaser or message body: for a Paper, the same role `hint` plays for
a Content payload, but shown once the directory is already being browsed
rather than as a "should I look for this" decision; for a Content payload,
free text alongside the cache's own bytes, or — when the content slot is
occupied by an attachment instead — standing in as the message itself (see
Postcards below). A per-file `description` (local key 4, optional, in each
`files[]` entry alongside `slug`/`mime_type`) plays the analogous role for
an individual file, e.g. "A poem to read" or "A hand-drawn map" — letting a
finder choose among files they can already see listed, before scanning
each one's own code. `title` (optional, valid for both payload kinds) is a
short subject/caption, deliberately kept separate from `hint` so a caption
never has to share a field with "where this is" or "should I look for
this". All three fields are optional; omitting them just means the
directory or preview shows filenames/MIME types with no caption or teaser.

**Located related papers:** A `related` entry (local key 5/6) may include
`lat`/`lng` — the approximate coordinates of that related paper, if the
author knows them — and an optional `radius_m` (local key 7)
circle-of-uncertainty radius in meters around that point, the same field
and semantics as Preview-level declared location (§4.2). The app shows
these as a "❓" placeholder pin (plus an uncertainty circle when `radius_m`
is set) on the map for related papers that haven't been scanned yet,
helping the finder navigate toward them. Once that paper is scanned, its
own `ScannedPaper` location (resolved from the device's live GPS fix
and/or that paper's own declared location, per §4.2's priority rule)
replaces the placeholder.

**Trail steps and forks:** `step` (Paper-Preview key 11; local key 10 in a
`related` entry) is an optional absolute ordinal — "this is stop N" —
scoped by `set`, not globally: two papers' `step` values are only
comparable when their `set` strings match. `step` is **1-based**: the
first stop in a trail is `step: 1`, not `0`. A paper declares its own
position via its own Preview's `step`; a `related` entry declares the position
of the paper it points to, so a finder can see "stop 4 of the sunset-trail"
before ever scanning stop 4. There is no declared trail length anywhere in
the format — a decoder only knows about the `step` values it has actually
seen mentioned (in a scanned paper's own `step`, or in a `related` entry
pointing at one), so "stop 4" can be shown with confidence but "stop 4 of
9" cannot unless every paper 1 through 9 happens to have been referenced
somewhere; a decoder MUST NOT assume the highest `step` value it has seen
is the trail's true last stop. Absolute numbering (rather than a relative
"next"/"previous" offset) is still the right choice despite this: it keeps
degrading gracefully when a stop is missing — a decoder can still place a
newly-scanned paper at its correct position among whatever other steps it
already knows about, gaps and all — and it doesn't require knowing your own
position to interpret a pointer, unlike a relative offset, which breaks the
moment one hop is scanned out of order or lost.

A **fork** is just two or more `related` entries that share both the same
`set` and the same `step` — the app presents all of them as alternative
next stops rather than picking one. Entries that share a `step` but *not*
the same `set` are not a fork; they're two unrelated trails that happen to
cross at the same physical hub paper (e.g. a noticeboard that is stop 4 on
"sunset-trail" and, separately, stop 2 on "history-trail"), and are shown as
separate trails with separate progress. `step` is deliberately not required
to be unique per `related[]` array for exactly this reason.

**Missing-tag resilience:** because a trail is just a chain of `related`
pointers, a single missing or destroyed stop can strand a finder who only
ever listed the immediate next one. There's no protocol-level fix for
this — the wire format has no notion of "skip" — so authors SHOULD list
more than one stop ahead (e.g. both stop 5 and stop 6 from stop 4), or a
link back to a known hub/index stop, giving a finder a way around a single
gone-missing tag. This is authoring guidance, not a new mechanism: it's the
same `related[]` array already used for the single-pointer case, just
populated with an extra entry.

**Navigation:** HTML files on the paper can link to other files using:
```html
<a href="tagdrop://@<paper-root-hash-hex>/map">See the map</a>
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

**File IDs (`cache_id`, Content-Preview key 1):**
```
cache_id = SHA-256(uncompressed content)[0:8]
```
Two payloads encoding the same content bytes will have the same `cache_id`,
regardless of Preview's other fields (`hint`/`title`/collection fields/etc.)
— this enables deduplication across multiple papers and payloads made by
different authors. `content` here means Content-Body's own `content` field
(key 1), decompressed if it was Compress-wrapped — never the compressed or
Split-fragmented wire bytes, so the same logical content always produces
the same `cache_id` regardless of which DEFLATE implementation, level, or
Split chunking happened to carry it. `cache_id` lives in a *different*
Record (Content-Preview) than the bytes it's computed over (Content-Body),
so — unlike `root_hash` below — there's no self-reference to worry about.
As before, `cache_id` MUST be random instead, when a hidden override map
might be present (§9).

**Paper root hashes (`root_hash`, Paper-Preview key 1):**
```
root_hash = SHA-256(Preview' || Body')[0:8]
```
where `Preview'` is Paper-Preview's own canonical CBOR bytes with key 1
(`root_hash` itself) **and** §10's Preview-side signature fields
(`signature_algorithm`/`signer_id`/`signer_label`, Paper-Preview keys
31/33/35) omitted, and `Body'` is Paper-Body's own canonical CBOR bytes
with §10's Body-side signature fields (`signature`/`signer_pubkey`, keys
5/7) omitted — whether or not the paper ends up signed, in both cases.
Both use the **logical** (decompressed) bytes if Body was Compress-wrapped
— never the compressed or Split-fragmented wire bytes, mirroring exactly
why `cache_id` above is defined over uncompressed content. Stripping the
signature fields too (not just `root_hash` itself) is what makes `root_hash`
and §10's signed message **the same SHA-256 call, just truncated** — see
§10, which restates this formula and MUST NOT be read as a second,
independent hash.

**This is a genuine self-reference, unlike `cache_id`, and MUST be handled
with the same placeholder-then-strip discipline §10 already uses for
signatures — not built as two independently-produced passes.** Unlike the
old single-map design, where `root_hash` lived in `part_meta`, *outside*
the structure it was computed over, moving `root_hash` into Preview itself
(so a single scan of Preview alone always yields a payload's identity, §4.1)
means Preview's own encoded bytes now depend on a hash computed *over*
Preview. The fix is identical in kind to what §10 already requires for
`signature`: build Preview with key 1 (and, if this payload will be signed,
the signature fields too) omitted — not zero-filled, simply absent, since
removing a field never changes any other field's encoded position, unlike a
same-length-placeholder trick — compute `SHA-256(Preview' || Body')`, then
encode the final Preview with `root_hash` (and, if signing, the signature
fields) now included. Nothing else about Preview's encoding may depend on
`root_hash`'s presence (e.g. no field whose own bytes shift meaning based
on whether key 1 exists) — this is exactly the "signing must feed back into
nothing else" property §10 already established for signature fields, now
also required of `root_hash`. For a Paper, Body never carries `content`, so
in practice `Body'` is `files`/`related` (and signature fields, if present
and stripped) alone. The root hash is the paper's permanent, immutable
address. Because a paper is inherently immutable (you can't update a
sticker), this is fine — a new revision gets a new root hash.

**Three-level hierarchy:**

```
Paper (root_hash, Paper-Preview key 1)
  └─ Files (cache_id, Content-Preview key 1)
       └─ Codes (Preview repeated on every code carrying a payload, §5.1)
```

---

## 5. Multi-Code Assembly Protocol

Splitting and reassembly are QDEF's generic mechanism (QDEF-SPEC.md §4.1's
Split Wrapper), not a TagDrop-specific scheme — this section states how
TagDrop uses it, not a new protocol.

### 5.1 What goes on which code

- **Preview is always whole and unwrapped**, and — for a multi-code
  payload — MUST be repeated identically on **every** code in the group,
  not just a "first" one. This is what makes cross-code correlation work
  with no extra field: a decoder scanning any single code already has
  Preview's `cache_id`/`root_hash` (§4.4) and therefore already knows which
  payload group this code belongs to and can show a usable preview,
  regardless of scan order or how much of Body has arrived. (This is a
  strict improvement over the old design, where only a sector actually
  carrying `core_meta_item` — usually, but not reliably, sector 0 — gave a
  usable preview.) The byte cost is the same kind of small, deliberate
  overhead the old `part_meta` already accepted on every sector for a
  similar reason.
- **Body travels alone or Split-wrapped.** If Body fits on the same code as
  Preview, that code carries both Records (a plain two-item CBOR Sequence).
  If not, Body is Split-wrapped (QDEF-SPEC.md §4.1 Type 2) into `count`
  fragments, and each of those codes carries Preview plus one fragment.
  `group_id` is a content hash of the fully reassembled, unwrapped Body
  Record's bytes — decoders MUST verify it after reassembly (QDEF-SPEC.md
  FINDINGS.md #5) and reject a mismatch, exactly as the old design required
  for `content_sha256`/`bulky_meta_sha256`, which `group_id` supersedes.

### 5.2 Reassembly

1. **Scan codes** in any order, any session. Each code's Preview identifies
   the payload (§4.4, §5.1) regardless of which code arrives first.
2. If a code's second Record is a plain Body (not Split-wrapped), Body is
   already complete — skip to step 4.
3. Otherwise, track Split fragments by `group_id` until all `count` are
   collected (QDEF-SPEC.md §4.1's fixed `chunkLen = ceil(total_bytes/count)`
   chunking rule), independently of any fragment at index `≥ count`
   (parity — see below). When complete, reassemble and verify `group_id` as
   required above.
4. Content: Body's `content` may be a hidden encrypted override map — if
   `content` is ≥ 28 bytes, try AES-256-GCM decryption as `nonce(12) ||
   ciphertext || tag(16)` (§9) against every known `key_material`. If one
   authenticates, CBOR-decode the plaintext as the override map (§9) —
   merge it onto Preview (override map's keys win) to get the final
   `hint`/`mime_type`/`content`/`filename`. Otherwise, Preview's fields are
   final as-is.
5. Deliver MIME-typed content to the viewer (Content payloads), or render the
   directory (Paper payloads, §4.3).

**Redundancy / erasure coding (issue #37):** for `parity_scheme = 1`
(QDEF-SPEC.md §4.1), a parity fragment's bytes are the byte-wise XOR of
every data fragment (index `0` through `count − 1`), each zero-padded to
the length of the longest data fragment before XOR-ing. If exactly one data
fragment is missing, reconstruct it: XOR the parity fragment against every
*other* data fragment already held, then truncate using `total_bytes` to
discard any padding on a reconstructed final fragment. Verify the
reconstructed Body against `group_id` (§5.1) before treating it as good —
if verification fails (e.g. the parity fragment itself was the one that was
corrupted), discard the attempt and fall back to waiting for the real
missing fragment. Exactly one parity fragment recovers from exactly one
lost fragment; additional copies of the same parity fragment would be
redundant, not additional protection. `parity_scheme` values `2` and up are
reserved for future schemes tolerating more than one loss (e.g.
Reed-Solomon over GF(256)) — not yet defined; until one is, authors needing
more than single-loss protection should fall back to physical redundancy
(duplicate a
sticker) as before.

**Resumability (issue #14):** Because assembly only needs each code's
Preview and whatever Split fragment it carries, a partially-collected
payload can be saved to a database and resumed later. Every code carries
Preview (§5.1), so the app can match newly-scanned codes to a pending
payload by `cache_id`/`root_hash` regardless of scan order.

**Geographic distribution:** Each code is an independent, self-contained
scan. Codes can be placed at geographically separate locations (along a
trail, in different rooms, across a city). The finder accumulates them over
time. Because Preview is required on every code (§5.1, a stronger guarantee
than the old design's "usually sector 0"), its `hint` can describe the
treasure hunt as soon as *any* code has been scanned — not just a lucky
first one.

**Fragment-index forward compatibility (issue #37):** Step 3 above only
ever reads fragment index values `0` through `count − 1` — reference
implementations (§15) build the reassembled byte array by indexing exactly
that range, never by iterating "every fragment seen so far." This is the
mechanism the redundancy scheme above relies on: a fragment at any other
index (`≥ count`) is already, by construction, ignored by anything that
isn't specifically looking for parity at that index, with no explicit
discriminator needed beyond the index itself. Reusing an index *within*
`0..count-1` for anything other than that exact data fragment would not be
safe — that range is reserved for the data fragments, in that order, with
no exceptions.

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
Location A: [ Code 0: tagdrop:<base41> ]  ┐
Location B: [ Code 1: tagdrop:<base41> ]  │   Preview repeated on every code (§5.1)
Location C: [ Code 2: tagdrop:<base41> ]  │   Split fragments in index order (§5)
Location D: [ Code 3: tagdrop:<base41> ]  ┘   → the reassembled Body (§4.1)
```

Codes can be scanned in any order, any session (§5) — there's no
designated "start here" code the way an old Manifest used to be. Any code
alone is enough to identify the payload (Preview, with `cache_id`/
`root_hash`, is on every one, §5.1) and to show its `hint` as soon as it's
scanned, even before the rest arrive.

**Code size recommendation:** Target ~600 bytes per Body fragment (decoded),
which encodes to ~900 Base41 characters and fits in a QR Version 17 that
prints cleanly at 3cm × 3cm and scans without zooming in on most phones.

**Redundancy (issue #37):** Add one parity fragment (`parity_scheme` 1, §5)
at fragment index `== count` and the set tolerates losing **any one**
physical sticker — a data sticker or the parity sticker — without stranding
the rest: the missing data fragment is reconstructed by XOR-ing the
parity fragment against the rest. This costs one extra code per payload and is
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
| Disk label / volume name | `hint` field in the Paper's Preview |
| FAT (file allocation table) | the Paper payload (Paper-Preview + Paper-Body) |
| Sectors | one physical QR code (Preview + Body or a Body fragment, §4.1, §5); the Paper itself or any one of its files can each span several |
| Directory | `files` field in Paper-Body |
| Volume serial number | `root_hash` (content-addressed, permanent) |

A recommended layout for an A4 paper:

```
+-------------------------------------------+
| [ Paper QR ]      Trail Stop 3 — Oak Tree |
|                                           |
| [ index.html ]    [ map.svg ]             |
|                                           |
| Next: letterbox 200m north                |
+-------------------------------------------+
```

Scan the Paper code first to get the directory, then scan whichever file you want — you don't have to scan everything.

### Navigation links

HTML files can link across the TagDropNet using one of three forms (§2):
```
tagdrop://<domain>/<slug>
tagdrop://@<rootHash-hex>/<slug>
tagdrop://<domain>@<rootHash-hex>/<slug>
```

When the TagDrop WebView encounters such a link, it:
1. Resolves the host to a single paper — an exact `root_hash` lookup for the `@<rootHash-hex>` forms, or a domain lookup for the bare `<domain>` form (see "Domains" below).
2. Finds the `slug` in that paper's file directory.
3. Looks up the file's `cache_id` in the found-caches database.
4. If found: loads the file. If not: shows a "not yet scanned" message with the location hint.

This gives the experience of browsing a website, but entirely offline and made of physical paper.

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
  `TagDropLinkResolver`, exactly like a `tagdrop://@<rootHash>/<slug>` link.

To reference a file on a **different** paper (a different root hash), use an
explicit `tagdrop://@<rootHash-hex>/<slug>` link — this can't be relative,
since it's a different content-addressed directory.

Practical effect: a normal static-site folder (HTML + CSS + images with
relative links) can be zipped, fed to the generator, and turned into a set of
QR codes where the relative links keep working once scanned into the app —
no rewriting of the authored HTML required.

### Markdown content (`text/markdown`)

`mime_type` (Content-Preview key 5, or `files[]`'s local key 2 for a
Paper's file entries, §3.1/§3.4) is a free-form string — `text/markdown` is rendered as
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

### Active content containment (`text/html` / `text/markdown`)

`text/html` and `text/markdown` are deliberately rendered as **live,
script-executing HTML documents**, not escaped/displayed as text — this is a
feature (interactive pages, not just static notes), but it means TagDrop's
threat model must assume **any scanned code can run arbitrary attacker JS**,
since anyone can encode anything into a QR code. Both reference
implementations contain that the same way:

- **No storage/DOM access.** Rendered content cannot read or write anything
  belonging to the reader/app itself — not cached scans, not the local
  signing identity (§10), not retained decryption keys (§9). Web: an
  `<iframe sandbox="allow-scripts" srcdoc="...">` with **no**
  `allow-same-origin` — per the HTML sandboxing model this forces the
  document into an opaque, storage-less origin regardless of what real
  origin the reader itself is served from. Android: the WebView exposes no
  `addJavascriptInterface` bridge into app code, so scanned JS has no
  programmatic path to the Room database or the Keystore-encrypted signing
  key.
- **No silent network egress.** A `sandbox` attribute alone does not stop
  markup- or script-driven network loads (an `<img src="https://.../leak.gif">`
  or a `fetch()` call still hits the real network from inside a sandboxed
  frame) — scanned content could otherwise silently phone home on every
  scan, turning a passive dead-drop into an active tracker of whoever finds
  it (their IP, rough geolocation, and a scan timestamp, correlated back to
  wherever the code was physically planted). Web: a Content-Security-Policy
  meta tag is injected into the rendered document (`connect-src`, `img-src`
  other than `data:`, `frame-src`, `media-src`, `object-src`, `font-src`,
  `form-action` all `'none'`) before any of the scanned content's own
  markup. Android: `WebSettings.blockNetworkLoads = true` refuses any
  subresource fetch that isn't served locally by the app's own
  `shouldInterceptRequest` handler (used for `tagdrop://` and same-paper
  relative links).
- **Explicit navigation still works, deliberately differently from silent
  requests.** An author-authored page linking out to a normal website is a
  reasonable, common case — unlike an automatic background request, a user
  *tapping a link* is visible and consensual, so it's handed off to the
  device's real, unsandboxed default browser (`Intent.ACTION_VIEW` on
  Android; `window.open()` on the web, gated by the browser's own
  popup-blocker since it only fires from a genuine click) rather than being
  silently blocked or attempted in-place. The receiving side validates the
  URL scheme is `http`/`https` before doing this — a `javascript:`/`data:`
  URL smuggled through the same relay message must never reach `window.open`
  or an `Intent`, since (unlike the sandboxed srcdoc origin) a window opened
  this way starts out same-origin with the reader page itself.
- **Not defended against: UI spoofing.** A scanned page can render anything
  it wants within its own display area — a fake dialog, a fake login form,
  a visual impersonation of the reader's own UI. Nothing above prevents
  that; it's an accepted, inherent limit of "render arbitrary rich content"
  as a feature, the same tradeoff any browser or app that displays untrusted
  HTML makes.

### Sets and slugs

Papers can belong to named **sets** (trails, networks, exhibitions). Within a set, each paper has a unique `slug`. This enables relative addressing:

```
A paper with set="sunset-trail", slug="oak-tree"
links to another paper with slug="letterbox" in the same set.
```

The full navigation URI for the letterbox paper's index file would be:
```
tagdrop://@<letterbox-paper-root-hash-hex>/index
```
(or, with a decorative label, `tagdrop://letterbox@<letterbox-paper-root-hash-hex>/index` — see "Domains" below.)

Root hashes are permanent because paper is immutable. If a paper is updated, it gets a new hash — the old one continues to work as long as the old paper exists physically.

### Domains

A 16-character hex root hash is precise but not memorable. The optional
`domain` field (Paper-Preview key 9, text, §3.3) lets a paper claim a
short, human-readable name instead — `helloworld`, say — for use in
navigation links. If a paper doesn't declare `domain`, its `slug`
(Paper-Preview key 7) is used as a fallback domain,
so a paper that's already part of a named set gets a memorable address for
free with no extra field.

Like `collection_id` ("Collections" below), a domain is **unilateral, not
coordinated**: any author can claim any name, there's no registry, and
nothing stops two unrelated papers from claiming the same one. This is a
deliberate trade-off — useful names stay short, and a domain can be
**floating**: re-pointed to a new paper just by printing a fresh one that
claims the same name, the same way updating a website means pointing a DNS
name at new content, or moving a `latest`-tagged container image to a new
build. A `root_hash`, by contrast, is always **pinned** — content-addressed
and immutable; the only way to "change" what it points to is to scan a
different hash. Navigation links can ask for either property — see "Domain
and pinned links" below.

#### Domain and pinned links

§2 splits `tagdrop://` links into three syntactic forms so that "look this
name up" and "use this exact hash" are never ambiguous:

```
tagdrop://<domain>/<slug>
tagdrop://@<rootHash-hex>/<slug>
tagdrop://<domain>@<rootHash-hex>/<slug>
```

- `tagdrop://<domain>/<slug>` — a **floating** reference: resolved by domain
  lookup (below), tracking whichever paper currently claims that name.
- `tagdrop://@<rootHash-hex>/<slug>` — a **pinned** reference: resolved by
  exact `root_hash` lookup, always the same paper.
- `tagdrop://<domain>@<rootHash-hex>/<slug>` — pinned, with `domain` carried
  along purely as a decorative label (e.g. for display in a UI or a printed
  caption). Resolution uses `rootHash` only — `domain` here is never checked
  against the resolved paper's actual declared domain, validated, or used as
  a fallback if the hash lookup misses. It's exactly as unverified as
  `label`/`hint`, or the `domain` field itself, always are. An author who
  wants this label to be *trustworthy*, not just descriptive, needs §10
  signatures, not this field.

The TagDrop app intercepts all three forms exactly alike, finds the file's
`cache_id` in the resolved paper's directory, and loads it from the
found-caches database.

#### Resolving a domain link

Whether a link is a domain lookup or a pinned hash lookup is decided by
syntax alone — the presence of `@` — never by the shape of the host text:

- No `@`: the whole host is `domain`. Resolvers MUST scan known papers for
  `domain` (or, absent that, `slug`) case-insensitively matching it —
  **never** as a `root_hash` lookup, even if the text happens to be 16 hex
  characters.
- `@` present: whatever follows it is `rootHash`, resolved by an exact
  `root_hash` lookup only. Whatever precedes the `@` (possibly nothing) is
  discarded for resolution purposes — see "Domain and pinned links" above.

This syntactic split exists specifically so a same-looking `domain` can
never be confused with, or shadow, a real `root_hash`, regardless of which
one a device happens to have scanned first. An earlier draft tried to get
the same guarantee from lookup order instead (try `root_hash` first, fall
back to a domain scan only on a miss) — see §16 for why that's
order-dependent and insufficient on its own, and for other markers
considered before `@`.

**Picking the closest match.** Because domains are uncoordinated, more than
one known paper can match the same name — this is expected, not an error.
When a bare `tagdrop://<domain>/<slug>` link resolves to more than one
candidate, the app picks a single one using:

1. If the device has a current position fix, and at least one candidate has
   a known location (declared, or resolved from a live GPS fix when it was
   scanned — §4.2's location/priority rules), pick the candidate nearest to
   the device.
2. Otherwise, among candidates that declare `created_at` (Paper-Preview
   key 39), pick the
   one with the newest author-declared timestamp, breaking ties by the
   greater `root_hash` — a deterministic rule (borrowed from the
   [Willow protocol](https://willowprotocol.org)'s entry-ordering: newest
   timestamp wins, hash as a stable tie-break) so that two devices which
   scanned the same set of candidates agree on the pick regardless of the
   order they scanned them in. Skipped entirely if no candidate declares
   `created_at`.
3. Otherwise — no device position, no candidate location, and no candidate
   declares `created_at` — pick the most recently *scanned* candidate.

This favours "the one you're standing next to" when that's knowable, then
"the one the author most recently made" when that's declared, and "the one
you personally saw most recently" as a last resort — each a better guess
than an arbitrary pick, and each weaker than the one before it: rule 2 is
still just an unverified, self-declared clock (like `created_at` always is,
§3), and rule 3 reflects this device's own discovery order, not the
content's actual freshness. If no known paper matches the name under either
field, the app reports the domain as not found (with the requested `slug`,
so the UI can still show what was being looked for) rather than treating it
as invalid input — the name may simply not have been scanned yet. A future,
backward-compatible refinement could prefer, among several candidates, the
one whose §10 `signer_id` the device has already trusted under this domain
— pinning *authority* over a name without giving up the name's
human-readable convenience — but no such tie-break exists yet, and nothing
here requires signatures today.

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
field (key 13 on both Content-Preview and Paper-Preview, 8 random bytes,
§3.1/§3.3) provides a lighter-weight mechanism:

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

- `collection_label` (key 15 on both Content-Preview and Paper-Preview) —
  a human-readable name for the collection
  (e.g. `"Spring 2026 Sticker Hunt"`), shown as the title of the collection
  card on the home screen. The author repeats the same label on every code
  that shares the `collection_id`; the app can display it as soon as it sees
  the first one.
- `collection_tag` (key 17 on both Content-Preview and Paper-Preview) — a short, hashtag-style string (e.g.
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

The optional `in_reply_to` field (Content-Preview key 51, Paper-Preview
key 37, 8 bytes) carries the `cache_id`
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
`title` (Content-Preview key 9, Paper-Preview key 43) and `description`
(Content-Preview key 11, Paper-Preview key 45) being valid on both payload
kinds; a "postcard" is just an ordinary Content or Paper
payload, composed from fields that already exist:

- **Subject line:** `title` (optional) — a short caption, kept
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
  is the thing worth deduplicating by — and `description` (Content-Preview
  key 11) carries the message instead, since the content slot is spoken
  for.
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

`icon` (key 19 on both Content-Preview and Paper-Preview) is an optional text field — typically a single emoji — that
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

### Pixel art

`pixel_art` (Content-Preview key 21) is an optional boolean, default `false`, Content only —
an author's declaration that this image's bytes should be rendered with
nearest-neighbor (no smoothing) scaling rather than a renderer's default
bilinear/smooth scaling. It exists for pixel art whose native resolution is
large enough that a renderer's own size-based heuristic (below) wouldn't
otherwise catch it.

Decoders SHOULD also independently disable smoothing for any raster image
whose native pixel dimensions are small (the TagDrop app's own threshold is
64×64 or smaller) and is being upscaled for display, regardless of whether
`pixel_art` is set — most pixel art is small by nature, and requiring every
author to remember a flag for it would be a poor default. `pixel_art` exists
only to cover what the heuristic alone would miss, e.g. a larger pixel-art
image that's still meant to be shown at hard pixel edges rather than
smoothed. Vector image content (e.g. `image/svg+xml`) has no native pixel
grid to smooth or sharpen, so neither the flag nor the heuristic applies to
it.

This field has no effect on decoding or content fidelity — it's a rendering
hint only, like `icon`. A decoder that ignores it entirely (e.g. always
smoothing, or never smoothing) still decodes the content correctly.

## 8. Compression

Compression is QDEF's Compress Wrapper (QDEF-SPEC.md §4.1 Type 3,
DEFLATE, zlib-wrapped RFC 1950), applied to a Body Record's encoded bytes
as a whole when an author chooses to — not a TagDrop-specific field.
Preview is never wrapped (§4.1), since it must stay cheaply parseable
before a decoder knows anything else about the payload. There is no longer
a separate `content_compression`/`bulky_meta_compression` split, and no
explicit compressed-length field — a Wrapper Record's payload is a
definite-length CBOR byte string, already self-delimiting regardless of
what's inside it (QDEF-SPEC.md §3.2), which is what let the old design's
`bulky_meta_compressed_bytes` field (the one explicit length field
anywhere in the old stream) be dropped entirely.

DEFLATE typically achieves 50–70% size reduction on HTML and text, effectively doubling QR capacity for textual content.

---

## 9. Encryption

A Content payload's `hint`, `mime_type`, and `filename` (Content-Preview
keys 3/5/7), plus `content` (Content-Body key 1, §4.1) — may optionally be
shadowed by a hidden, encrypted **override map**, independently of
compression (§8). Unlike most of this format, the override map's presence
is **never required to be declared** — see "Discovery, not declaration"
below. A code with no declared `encryption` and an unremarkable
Content-Preview/`content` can still be carrying one. This mechanism is
Content-only: a Paper's Body never carries anything content-shaped and is
always content-addressed via `root_hash` (§4.4), with no override-map
exception.

| `encryption` value | Algorithm |
|---|---|
| 0 (or absent) | None declared |
| 1 | AES-256-GCM |
| 2–255 | Reserved |

`encryption` (Content-Preview key 37) is an **optional hint**, not a
precondition: a code MAY set it to `1` to advertise "scan a key to unlock
more" (e.g. for a "🔒 Locked" badge in the UI). Its absence does NOT mean
the code has no hidden override map.

| Key | Field | Type | Lives in |
|---|---|---|---|
| 37 | `encryption` | uint (opt) | Content-Preview only |
| 33 | `key_material` | bytes (32, opt) | Content-Preview (key 33) or a `related` entry (local key 8) |
| 35 | `retain_key` | bool (opt, default `true`) | wherever `key_material` appears |

**Paper-Preview note:** Paper-Preview's key 33 is `signer_id` (§10), not
`key_material`. To avoid collision, Paper-Preview uses key 47 for
`key_material` and key 49 for `retain_key` (see §3.3). The semantics are
identical — the same "try this key against everything cached" design
applies — only the key numbers differ. This is because Content-Preview and
Paper-Preview have independent key namespaces (CBOR map keys are scoped
per-map), so key 33 in one record type does not conflict with key 33 in
another; the different numbering is a consequence of Paper-Preview
allocating keys 31/33/35 for its own signing fields (`signature_algorithm`/
`signer_id`/`signer_label`), which Content-Preview places at 45/47/49
instead.

The GCM nonce travels embedded in the override map's ciphertext itself
(below), so there's simply nothing nonce-shaped in Content-Preview at all —
a separate clear-text nonce field would only add bulk and a second
(always-matching, or suspiciously-not) value to cross-check.

### Encrypted override map

A hidden **override map** is a small CBOR map with its own independent
local key namespace (§3.2) — 1 (`hint`), 3 (`mime_type`), 5 (`content`), 7
(`filename`):

```
override map {
  1: "treasure map",          // hint — optional
  3: "image/png",              // mime_type
  5: h'<real content bytes>',  // content
  7: "map.png",                // filename — optional
}
```

Its CBOR bytes are compressed (§8, if wrapped) and then
AES-256-GCM-encrypted (see below) to a single **self-contained blob**:

```
nonce(12 bytes) || ciphertext || tag(16 bytes)
```

The nonce travels with the blob — nothing in Content-Preview is needed to
locate or interpret it.

**Where this blob lives:** Content-Body's `content` field (key 1, §4.1) —
the same slot a Content payload's cache normally occupies. Once Body has
been reassembled (§5) and any Compress Wrapper unwrapped, `content`'s bytes
— if their length is ≥ 28 (the minimum possible blob: 12-byte nonce +
16-byte tag, for an empty plaintext) — are a candidate, *in addition to*
whatever they decode to as plain content. §5.2 step 4 tries both. This
works the same way regardless of whether Body was Split-wrapped: reassembly
happens first, so a multi-code payload's hidden blob is no different from a
single-code one's.

**Producing the final view:** for each candidate `key_material` the app
holds, try AES-256-GCM decryption of the candidate blob using its first 12
bytes as the nonce. If the authentication tag checks out, decompress the
remaining plaintext (if it was compressed before encryption) and CBOR-decode
it as the override map, then merge it onto Content-Preview — its
`hint`/`mime_type`/`filename` are overridden by the override map's
same-purpose keys, and the override map's `content` becomes the final
content, replacing whatever Body's `content` field decoded to plainly. If no
key has yet succeeded (or the code carries no such blob at all),
Content-Preview's fields and the plainly-decoded `content` *are* the final
view, exactly as in §4.1.

```
Content-Preview {
  1: h'<8 random bytes>',  // cache_id — random, see below
  37: 1,                   // encryption — optional "🔒 Locked" hint
}
Content-Body {
  1: h'<12-byte nonce>' || h'<ciphertext of (compressed) override map>' || h'<16-byte tag>',
}
```

**Cover stories, or no story at all:** Content-Preview's `hint`,
`mime_type`, and `filename`, plus whatever `content` decodes to plainly,
are shown (and used) until a matching key is found. They MAY be a generic
"locked" placeholder, a believable **decoy** (different hint/MIME/
content/filename than what's really there), or — since `encryption` need
not be declared — genuine, unremarkable content with no relation to the
hidden override map at all. A code can look, scan, and behave exactly like
any other TagDrop code while still carrying a hidden layer in `content`.
Once a matching `key_material` is found, the override map's same-purpose
fields replace the plain ones — the displayed content, hint, MIME type, and
filename **self-correct** to the real ones.

For the fully-undeclared case to actually be deniable, Content-Preview and
`content` need genuine, unremarkable content of their own — an empty or
trivially-placeholder one is itself a tell ("why would this code exist at
all?").

**Order of operations:** compress (§8) first, then encrypt — encrypted bytes
are high-entropy and don't compress further, so encryption is always the
last transform applied before transmission, and the first reversed on
receipt. What gets compressed-then-encrypted is the override map's CBOR
bytes. When Body is Split-wrapped, `group_id` (§5.1) continues to cover
`content` exactly as transmitted, i.e. after compression *and* encryption,
so a partially- or incorrectly-assembled multi-code payload can be detected
before a decryption key is even available.

**`cache_id` for a code carrying a hidden override map is random, not
content-addressed.** §4.4 defines `cache_id = SHA-256(uncompressed
content)[0:8]` so that identical content always gets the same ID — useful for
deduplication, but exactly the wrong property here: it would let anyone
compute the `cache_id` of `content`'s own plain reading (cover story or not)
and check whether any code in the wild carries it, linking that code to a
known document regardless of what's hidden inside. An author embedding a
hidden override map MUST set `cache_id` (Content-Preview key 1) to 8 random
bytes, independent of both `content`'s own plain reading and the override
map's real `content`.

**AES-256-GCM:** the 12-byte nonce prefixing the blob MUST be unique for
every encryption performed under a given key — a reused nonce breaks
AES-GCM's confidentiality entirely. The (compressed) override map's CBOR
bytes are encrypted to `ciphertext || 16-byte authentication tag` (tag
appended) — the default output of both `javax.crypto.Cipher`
("AES/GCM/NoPadding") on Android and `SubtleCrypto.encrypt()` in browsers —
then prefixed with the nonce to form the blob above, used as Content-Body's
`content` field (§4.1).

### Decryption keys

A decryption key is **32 raw bytes** (`key_material`) — an AES-256-GCM key,
used directly with no passphrase or key-derivation step. It can appear:

- in any payload's Preview, Content or Paper, as a top-level field — "this
  code also carries a key for other content," independent of whatever
  `content` (if any) the code itself carries (Content-Preview key 33,
  Paper-Preview key 47 — see note in the table above); or
- on an element of a Paper's `related` array (local key 8, §3.4) —
  "scanning this paper reveals a key for the related paper," for trails
  meant to be discovered in sequence.

A Content payload carrying `key_material` may omit `mime_type` entirely,
and carries no Body at all — a code can be *just a key*, with no
displayable content of its own:

```
Content-Preview {
  33: h'<32-byte AES-256 key>',
  35: false,   // retain_key — use once against what's cached now, then forget
}
```

Note what's *not* here: no `cache_id`, no pointer to the content this key
unlocks. That's deliberate — a `key_material` carries no reference to which
code(s) it applies to, so the same key MAY be the right key for many
different cached codes (e.g. one trail-wide secret that unlocks a hidden
layer on every sticker in the trail, not just one). "Try this key against
everything cached" isn't a fallback for when a targeted lookup is
unavailable — it's the only mechanism there is, and it's the right one for
a key that's reused across many tags. A key-only code typically omits
`cache_id` too, since it references no content of its own to be
deduplicated or cached against, and carries no Body Record at all (§2.1).

`retain_key` (default `true`) is the author's recommendation for whether the
app should remember this key for future matches across scanning sessions
(`true`), or use it only against content already cached *right now* and then
discard it (`false`). It's a recommendation, not an enforceable guarantee —
an app or user can always choose to remember a key regardless.

**Discovery, not declaration:** no field says which content a given
`key_material` decrypts, and — per above — `encryption` is at most a hint,
not a precondition. Instead, whenever the app learns a new `key_material`,
it tries AES-256-GCM decryption — using the candidate blob's own embedded
12-byte nonce — against the `content` field of every cached Content payload
that is ≥ 28 bytes and hasn't already been opened. A successful
authentication-tag check is the match; the app decompresses (if it was
compressed before encryption) and CBOR-decodes the result as the override
map (§9), then merges it onto Content-Preview — refreshing the displayed
`hint`/`mime_type`/`content`/`filename` to their real values. Symmetrically,
whenever a new code is cached, its `content` field's bytes (if ≥ 28 bytes)
are tried against every previously-seen `key_material` (subject to that
key's `retain_key`). This is cheap — AES-GCM decryption of a few KB against
a handful of candidates is negligible, with a false-positive authentication
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
  fresh nonce is computationally indistinguishable from random bytes.
  Without the right key, only Content-Preview's fields and `content`'s
  plain reading are visible — the Record framing itself, Content-Preview's
  `cache_id`, optionally `encryption`, whichever optional collection/icon
  fields the author included, and whatever `hint`/`mime_type`/`content`/
  `filename` (§9) the author chose: a placeholder, a decoy, or genuine
  unremarkable content with no relation to what's hidden. A hidden override
  map living in `content` reveals nothing about the real values, or whether
  any particular `key_material` unlocks them — and genuine cover content is
  indistinguishable from "this is simply the (unencrypted) content, full
  stop."
- **Decoders tolerate trailing bytes.** A CBOR Sequence (RFC 8742, §2) is
  self-delimiting — a decoder reads exactly as many Records as it expects
  and stops. TagDrop decoders MUST NOT treat additional bytes after a
  complete, valid Record Sequence (§2) as an error. This lets a code's
  transmitted bytes be followed by a second, wholly independent Record
  Sequence — its own Preview (and Body, if any) — encrypted under a
  different key, indistinguishable from padding or noise to a decoder that
  stops after the first one. This is separate from the `content`-field
  override map above (which lives *inside* Content-Body's own bytes, not
  after the whole Sequence); the two mechanisms can be combined for two
  independent layers of hiding on one physical code.
- **Keys and content are the same shape.** A `key_material`-only code and a
  small code carrying a hidden override map both look like a Preview of
  high-entropy byte strings, optionally followed by a `content` field
  that's equally high-entropy. Nothing marks "this is a key," "this is
  locked content," or "this is just a normal code with some padding."
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
Content-Preview signal this:

| Key | Field | Type | Description |
|---|---|---|---|
| 39 | `kdf_alg` | uint | KDF algorithm: `1` = PBKDF2-HMAC-SHA256 |
| 41 | `kdf_salt` | bytes (16) | Random salt; unique per encryption |
| 43 | `kdf_iters` | uint | PBKDF2 iteration count; default `100000` if absent |

When `kdf_alg = 1` is present in Content-Preview alongside a candidate
override blob (Content-Body's `content` field — see "Where this blob
lives" above), the reader MUST:

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
passphrase-encrypted code has `kdf_alg`/`kdf_salt` in its Content-Preview
but no `key_material` field and no separate key QR; a key-code-encrypted
code has neither `kdf_alg` nor `kdf_salt` and is unlocked by a separately
distributed key code. If a code anomalously carries both `kdf_alg` and
`key_material`, a reader SHOULD attempt `key_material` first (no user
interaction required) before falling back to the passphrase prompt. The
trial-decryption mechanism works identically once a key is in hand — the
derivation step is simply the extra work the passphrase path adds before
that.

**Note on QDEF's Encrypt Wrapper (QDEF-SPEC.md §4.1 Type 4):** TagDrop's
own encryption stays entirely as described above and does not use it — the
mechanisms are deliberately incompatible. Wrapping a Record in any QDEF
Wrapper Record is an inherent public declaration ("this is compressed" /
"this is encrypted"), visible to any QDEF-aware parser even if it can't
decode the payload — directly against "discovery, not declaration" above,
which requires an encrypted code to be indistinguishable from an ordinary
one. This is a genuine scope boundary, not a gap in either spec — see
mofosyne/qdef's FINDINGS.md #13.

---

## 10. Verified Authorship (Signatures)

A payload may optionally be **signed**, proving "the holder of a particular
private key produced this exact payload." Signing is orthogonal to
encryption (§9) and to content-addressing (§3, §4.4) — a signed code has the
same `cache_id`/`root_hash` as its unsigned equivalent, and signing is
**opt-in**: most TagDrop codes (stickers, treasure hunts, paper backups)
need no signature at all.

| `signature_algorithm` value | Algorithm |
|---|---|
| 0 (or absent) | Unsigned |
| 1 | ML-DSA-44 (Dilithium2, FIPS 204) |
| 2–255 | Reserved |

| Field | Content-Preview key | Paper-Preview key | Content-Body key | Paper-Body key |
|---|---|---|---|---|
| `signature_algorithm` | 45 | 31 | — | — |
| `signer_id` | 47 | 33 | — | — |
| `signer_label` | 49 | 35 | — | — |
| `signature` | — | — | 3 | 5 |
| `signer_pubkey` | — | — | 5 | 7 |

`signature_algorithm`/`signer_id`/`signer_label` are small and go in
Preview; `signature`/`signer_pubkey` are large and go in Body — the same
size-based placement principle the old `core_meta_item`/`bulky_meta_item`
split already used, just now expressed as two separate Record Types
instead of two concatenated items.

**Implementation status:** the web tools (generator and reader) now sign
and verify Content using the new QDEF key layout described above —
Preview signature fields (keys 45/47/49) are stripped for hashing via
`contentSignedMessageHash()`, and the Compress/Split Wrapper layer sits
outside the signed region. The Kotlin Android app still signs using the
old key numbering (32–36 in a shared `core_meta_item`/`bulky_meta_item`)
and will need updating when its Content codec is ported to QDEF Records.
The rest of this implementation-status note describes what's already
proven to work, which remains true of the underlying mechanism (ML-DSA-44
sign/verify, TOFU caching) even though the field layout it operates on
differs between implementations: the web tools do real ML-DSA-44
sign/verify via
[`@noble/post-quantum`](https://github.com/paulmillr/noble-post-quantum),
dynamically imported from a CDN like the generator's other optional
dependencies (qrcode, jsPDF) — no build step, no bundled dependency: the
generator's "Single File" tab can sign a Content code with a
browser-local signing identity (an ML-DSA-44 keypair persisted in
`localStorage`, generated on first use) — exportable as a passphrase-
protected backup file and re-importable in another browser/computer, since
`localStorage` alone doesn't survive switching either (a new identity, and
a new `signer_id`, would otherwise be generated there instead) — and the
reader verifies any signed code it scans, caching each `signer_pubkey`
under its `signer_id` (TOFU, key caching per below) in IndexedDB and
showing a verified/invalid/pending badge. Paper signing is supported by
the same codec functions but has no generator UI yet (Single File tab only
so far). The Kotlin app also does real ML-DSA-44 sign/verify, via
[BouncyCastle](https://www.bouncycastle.org/) (`bcprov-jdk18on`): `CreateActivity`
can sign a Content code with a device-local signing identity (an ML-DSA-44
keypair persisted in an `EncryptedSharedPreferences` file, generated on first
use), and `ReceiveActivity` verifies any signed code it scans, caching each
`signer_pubkey` under its `signer_id` (TOFU, key caching per below) in a
Room table and showing a verified/invalid/pending badge. Paper signing is
supported by the same codec functions but has no `CreatePaperActivity` UI
yet, mirroring the web generator's own gap. Readers that don't recognise
these fields ignore them per §2.2's forward-compatibility rule (all odd,
optional) and treat the code as unsigned.

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
overhead; for content already spanning multiple codes (§5) — e.g. an essay
of a few KB — a constant ~2.4 KB signature is proportionally minor, and the
public key (§ below) is amortized across an entire trail or collection.

**Signed message:** `SHA-256(Preview' || Body')` (full, untruncated 32
bytes), where `Preview'` is Preview's own canonical CBOR bytes with
`signature_algorithm`/`signer_id`/`signer_label` (this section's own
Preview-side fields) omitted, and `Body'` is Body's canonical CBOR bytes
with `signature`/`signer_pubkey` (this section's own Body-side fields)
omitted — i.e. the SHA-256 of exactly what an unsigned payload's Preview
and Body would contain. **This is the identical computation §4.4 defines
for `root_hash`/`cache_id`'s own scope** — for a Paper, `root_hash` already
strips exactly these same fields (both this section's and its own key 1)
before hashing, so computing `root_hash` and computing the signed message
are **one and the same SHA-256 call**: compute it once, take the first 8
bytes for `root_hash`, and feed the full 32 bytes to `Sign`/`Verify`. There
is no separate bootstrapping step or ordering question ("hash first, then
sign over the hash") beyond what §4.4 already requires for `root_hash`
alone. For a Content payload, `cache_id` is a *narrower* hash (`content`
only, §4.4) and does *not* coincide with the signed message the way
`root_hash` does — the signed-message formula still applies to Content's
full Preview+Body, it's just a separate computation from `cache_id`, not a
truncation of it. In both cases, signing happens last and feeds back into
nothing — `cache_id`/`root_hash` are identical whether or not this
section's fields are subsequently added, exactly as before.

**Building a signed payload — same placeholder discipline as `root_hash`,
now applied together:** because `root_hash` (Paper) and this section's
fields all get stripped from the *same* hash computation, a Paper build
must, in order: (1) build Preview/Body with both `root_hash` and this
section's fields absent, (2) compute the shared hash once, (3) fill in
`root_hash` (first 8 bytes) and — if signing — `signature`/`signer_pubkey`/
etc. (computed by signing the full 32 bytes) into the final encoding. This
is the same "placeholder-then-fill, never build unsigned and signed as two
independently-produced passes" discipline this project's own history
(CLAUDE.md) already requires — now covering two fields' worth of
self-reference (`root_hash` and the signature) with one shared build step
instead of treating them as separate concerns.

**Verification:** a verifier strips this section's fields from Preview and
Body, recomputes the same full SHA-256, and checks `signature` against
that hash using `signer_pubkey` via ML-DSA-44 `Verify`. `signer_id` =
`SHA-256(signer_pubkey)[0:8]` — the same truncated-SHA-256-prefix
convention as `cache_id`/`collection_id`/`paper_id` (§3).

**Key caching (amortizing the ~3.7 KB first-use cost):** `signer_id` is
present on every signed payload, but `signer_pubkey` (1312 bytes) only needs
to be included on the *first* signed code an app encounters from a given
`signer_id` — the app caches `signer_id → (signer_pubkey, signer_label)`.
Subsequent codes from the same signer omit `signer_pubkey` and cost only
`signature` + `signer_id` (~2428 bytes). If a code omits `signer_pubkey` and
the verifier has no cached entry for its `signer_id`, the signature can't
yet be checked — it's held pending, and verified retroactively once a code
carrying that `signer_pubkey` is scanned, the same "complete opportunistically,
in any order" pattern as multi-code assembly (§5) and key matching (§9).

**Trust model:** trust-on-first-use (TOFU), like SSH host keys — there is no
PKI, certificate authority, or revocation. A verified signature proves "the
same private key signed this as everything else cached under this
`signer_id`," not a real-world identity. `signer_label` (free text) lets an
author attach a human-readable name (e.g. "City Parks Dept. Trail"); it is
self-asserted and meaningful only as a consistent label across that
signer's codes, exactly like a comment in `~/.ssh/known_hosts`.

**Downgrade:** stripping this section's fields from a signed code yields a
valid unsigned code with the same `cache_id`/`root_hash` — content-addressing
doesn't distinguish "never signed" from "signature removed." This is an
accepted limitation: a signature can be *removed* but not *forged* or
*retargeted* (ML-DSA verification ties `signature`, `signer_pubkey`, and
the exact payload bytes together), so the only thing an attacker can do is
strip an authorship claim, never add or substitute one. This applies
whether the fields are stripped from a single unwrapped code or from every
code in a Split-wrapped group — see also QDEF-SPEC.md §9's own
"strippable-but-not-forgeable" note, which cites this exact property as
precedent for a similar detached-signature mechanism it's considering.

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
Content-Preview {
  5: "text/markdown",
  1: h'<cache_id>',
  45: 1,                            // signature_algorithm: ML-DSA-44
  47: h'<8-byte signer_id>',
  49: "Alice's Trail",               // optional human-readable label
}
Content-Body {
  1: h'<content bytes>',
  3: h'<2420-byte signature>',
  5: h'<1312-byte public key>',     // only on first code from this signer
}
```

---

## 11. Backward Compatibility: Legacy `data:` URIs

Codes containing a raw `data:` URI (without the `tagdrop:` scheme) are recognised and handled in **legacy mode**:

- A single code: the data URI is opened directly in the WebView viewer.
- Multiple codes: fragments are **dumb-appended** in scan order (the original V1 behaviour). The assembled string is interpreted as a `data:` URI.

New content should use the `tagdrop:` scheme. Legacy support will be maintained indefinitely.

---

## 12. NFC Transport (future)

The Record Sequence (§2; the same bytes that get Base41-encoded into the
`tagdrop:` URI) can be stored directly in an NFC NDEF record with:

- **TNF:** `0x02` (MIME Media type)
- **Type:** `application/vnd.tagdrop`
- **Payload:** the raw Record Sequence bytes — no Base41 encoding, and no
  QDEF magic prefix either: NDEF's own MIME-type field is the dispatch
  signal, so adding QDEF's header here would be redundant (§2's carrier
  table).

Because each Record carries its own QDEF Type ID (§2.1) in its own bytes,
one permanent MIME type covers every TagDrop Record Type this document
ever defines — no per-Type MIME subtypes needed, and the `tagdrop:<base41>`
and raw-NDEF decoders share the same Record-Sequence parsing, differing
only in the Base41 step.

This lets the same physical sticker carry both a QR code (for camera scanning) and an NFC tag (for tap-to-read), with identical content. Android dispatches `application/vnd.tagdrop` NDEF records to the TagDrop app via intent filter.

An NFC-NDEF capable multi-tag group uses the same Split Wrapper mechanism
as a multi-code QR group (§5), where each NFC tag holds Preview plus one
fragment's CBOR bytes. (NFC Type 2 tags at 1 KB are suitable for
single-code payloads; 8 KB tags can hold payloads needing more codes.)

### Optional standard record, for non-TagDrop readers

A tag may optionally carry a **second, preceding** NDEF record — at record index 0, ahead of the `application/vnd.tagdrop` record described above — matching the content's real type: a Well-Known URI record for link-shaped text, Well-Known Text for other text, or a MIME record with the real `mime_type` and raw bytes otherwise. This is purely additive: old readers (including TagDrop versions that predate this paragraph) ignore the extra record and single-record tags remain valid as before. (This is the same graceful-degradation idea QDEF-SPEC.md §4.2 later generalized into a reusable Fallback Hint stdlib Record Type — TagDrop's own version here predates and is specific to NDEF, not routed through QDEF.)

The point is survivability and first contact: a phone with no TagDrop installed gets *something* useful from the tap (a page opens, a note displays, a file viewer launches) instead of nothing, and the tag's core content outlives the app.

This only applies cleanly to a standalone single-code payload (Preview and, if present, a complete unwrapped Body) — a Split-wrapped fragment of a multi-tag group isn't an openable file on its own — and it must not be combined with an Android Application Record (AAR): an AAR present anywhere in the message overrides normal dispatch and force-launches that app regardless of record order, defeating the point of putting a standard record first.

Order matters, and so does a real limitation it introduces: Android resolves `ACTION_NDEF_DISCOVERED`'s dispatch type from **record 0 only**, for both a cold-start manifest `<intent-filter>` match and an already-running app's `enableForegroundDispatch` filter match alike. Putting the standard record first is what makes the tag dispatchable to a generic non-TagDrop handler — but by the same rule, **TagDrop's own automatic dispatch no longer matches such a tag either**, whether TagDrop is closed (cold start) or already open. A tag written this way only reaches TagDrop if some other path reads it; tapping it does not auto-launch TagDrop the way a single-record tag does.

---

## 13. Alternative Carriers

The format is carrier-agnostic. Any medium that can carry a UTF-8 string
supports the `tagdrop:` URI form. Any medium that carries raw bytes
supports the raw Record Sequence form (with or without QDEF's magic
prefix, per §2's carrier table).

| Carrier | Form | Notes |
|---|---|---|
| QR code | `tagdrop:` URI, alphanumeric mode; or QDEF-framed Record Sequence, byte mode | Primary target. Byte mode avoids Base41 overhead but pays QDEF's 9-byte magic+namespace-discriminator cost instead (§2.1a) — denser than Base41 either way, but not human-typable; best for non-initial codes in a multi-code group, which are always camera-scanned |
| Aztec code | `tagdrop:` URI | Higher density than QR at small sizes |
| Data Matrix | `tagdrop:` URI | Better damage resistance |
| JABCode (color) | `tagdrop:` URI or QDEF-framed Record Sequence | ~4× capacity of QR; see [jabcode/jabcode](https://github.com/jabcode/jabcode) |
| NFC NDEF tag | Raw Record Sequence, no QDEF prefix, MIME type | No Base41 overhead and no magic-header overhead either — NDEF's own MIME type is the dispatch signal (§12) |
| Plain URL | `tagdrop:` as deep-link | QR of a URL that deep-links to app |

---

## 14. Version Negotiation

**No carrier framing has a physical version byte.** QDEF's own outer
framing (the magic header used by the byte-mode QR/JABCode carrier, §2)
never had a version field; the `tagdrop:` URI and NFC carriers never had
any outer framing at all, since their own dispatch signal (the scheme, the
MIME type) already does that job. TagDrop's own versioning lives in three
other mechanisms instead, each covering a different kind of change: this
document's own `Version:` field (top of file — a human-facing marker
bumped whenever this document's own Record/field definitions change
incompatibly, see the changelog below), each Record's own QDEF Type ID
(§2.1 — a reader that doesn't recognize a Type ID simply skips that whole
Record rather than guessing at its shape, the same graceful degradation
QDEF gives any unrecognized Record, carrier-independent), and §2.2's
even/odd key criticality (per-field forward compatibility inside an
already-recognized Record Type, without needing a version bump for every
additive change — see below).

**Magic-header carriers pay for a mandatory namespace discriminator; every
other carrier pays nothing.** QDEF-SPEC.md §3.5's container discriminator
is always exactly one CBOR item, immediately after the magic bytes, on any
carrier that uses the magic header — today, only TagDrop's byte-mode
QR/JABCode carrier (§2, §13), which isn't implemented in any TagDrop codec
yet. That carrier has no external dispatch signal of its own, so it
declares TagDrop's namespace (§2.1a) rather than using the discriminator's
cheapest "no namespace" value — bringing that carrier's outer framing to
9 bytes total (4-byte magic + 5-byte namespace discriminator). Carriers
that skip the magic header outright — the `tagdrop:` URI and NFC NDEF —
skip the discriminator too, and for a stronger reason than "it would be
cheap to skip": both already told the reader what it's looking at before
any QDEF-specific parsing begins, the same job the magic header and its
discriminator exist to do for an otherwise-unidentified byte stream — so
there's no collision risk for the discriminator to guard against there in
the first place. In short: **required in binary/byte-mode QR framing,
because nothing else identifies it; omitted wherever a private dispatch
signal — a scheme, a MIME type — already does that job.**

**Additive fields vs. version bumps (issue #37):** §2.2's even/odd
criticality rule gives forward compatibility for both optional fields an
old parser can simply not act on (odd keys — e.g. `description`, issue #35)
*and*, now, future must-understand fields (even keys) that cause a clean,
localized abort of just that Record rather than a version bump — a
capability the pre-QDEF single "ignore everything unknown" rule didn't
have. What even/odd does **not** cover is a field that would change
whether data an old parser already understands is *complete* without the
parser knowing to check — e.g. an old parser that doesn't understand a new
Wrapper Type ID simply skips that whole Record (§4.1's Wrapper mechanism is
itself immune to this hazard by construction, per QDEF-SPEC.md's own
reasoning), so this class of risk is narrower under the new design than it
was under the old sectoring scheme, but not zero: any future mechanism that
could make an old parser silently treat incomplete data as complete MUST
still be gated by a version bump, not an additive key.

**Why this document's version number keeps moving even though nothing has
shipped:** every bump since version 1 was, strictly, optional — this
document has been an undeployed Draft the whole time, and the rule above
only governs changes made *after* a version has shipped real-world codes.
Each one was bumped anyway, as a clear, findable marker that the wire
shape changed at that point, not because any deployed content needed
protecting — nothing has been deployed under any version number to date.

Version history — each entry states only its own delta from the version
directly above it:

**Version 7** (current) — corrects a carrier-scoping gap in version 6's
Type ID safety argument. Version 6 justified shrinking all four Type IDs
by "own-URI-scheme isolation" — true for `tagdrop:` URI and NFC NDEF,
both of which have external dispatch context before a decoder ever looks
at a Type ID, but **not** true for byte-mode QR/JABCode, which has no
such context and is exactly the "otherwise-unidentified byte stream" case
QDEF's magic header exists for. That carrier's small even Type IDs, on
their own, sit in only a ~32,767-value space — nowhere near collision-safe
against an unrelated app's Type ID without something disambiguating them.
Fixed by declaring TagDrop's namespace (§2.1a) on that carrier specifically
— a bare 4-byte hash-derived value, `SHA-256("io.github.mofosyne.tagdrop")
[0:4]` = `89d414e0`, as the populated discriminator item (QDEF-SPEC.md
§3.5's `byte string` shape) — bringing that carrier's framing from 5 to
9 bytes. `tagdrop:` URI and NFC NDEF are unaffected (still no magic, no
discriminator, no namespace — nothing to disambiguate there in the first
place). The four Type IDs themselves are unchanged: staying even means
they're valid whether or not a namespace happens to be declared, so only
byte-mode QR's outer framing changes, not the Record Sequence bytes
shared across all three carriers. Byte-mode QR/JABCode isn't implemented
in any TagDrop codec yet, so this is a documentation-only correction with
no code to update — but needed fixing before it became a deployed gap.

**Version 6** — all four Type IDs shrunk from 64-bit CSPRNG
values to small self-allocated even values (`48250`/`56990`/`34456`/
`58984`, §2.1), using QDEF-SPEC.md §2/§3.5's now-formalized guidance that
an application carrying QDEF content under its own URI scheme — as
`tagdrop:` already does — has no need for the collision margin a large
random Type ID exists to buy, since the scheme itself already isolates
TagDrop's Records from every other app's decoder. Saves 6 bytes per
Type-ID occurrence (10 bytes → 4), with no offsetting cost anywhere,
unlike namespace-scoping's per-code Type-0 tax (declined for that reason,
CLAUDE.md's backlog). Value-only change — no field/key changes within any
Record Type's own table (§3.1-§3.4). Also resolves a Kotlin-port blocker
noted in §15: `MiniCbor.kt`'s 32-bit-uint limitation is no longer a gap
for Type IDs specifically, since all four now fit in 16 bits.

**Version 5** — QDEF-SPEC.md §3.5's namespace discriminator
became mandatory on any carrier using the magic header (previously
optional, zero cost when absent). Byte-mode QR/JABCode framing (§2, §13)
grows from 4 to 5 bytes accordingly — coincidentally the same total as
version 2's original magic+version framing, though for an unrelated
reason (see the discriminator note above). `tagdrop:` URI and NFC stay
exempt, as they always have been from the magic header itself. No
Record/field/Type-ID changes.

**Version 4** — checking TagDrop's four Type IDs against QDEF's finalized
Type-ID parity rule (even uint = always global; odd uint = requires a
declared namespace or abort) surfaced that Paper-Preview and Paper-Body
were both odd, by chance, with no namespace ever declared on any TagDrop
carrier — making every Paper payload non-compliant. Fixed by re-minting
both as new even 64-bit values (§2.1), matching Content-Preview/
Content-Body's already-compliant pair. Value-only change — no field/key
changes within Paper-Preview's or Paper-Body's own tables (§3.3, §3.4).
Adopting namespace-scoping itself (shrinking all four Type IDs to small
odd integers behind a declared namespace) was considered and deliberately
deferred — see CLAUDE.md's backlog.

**Version 3** — QDEF dropped its outer-framing version byte entirely:
`[4-byte magic][CBOR Sequence]`, no version field at all. Byte-mode
QR/JABCode framing (briefly) dropped from 5 to 4 bytes. No Record/
field-level changes.

**Version 2** — breaking redesign onto QDEF's Record/Wrapper primitives
(§2's "Relationship to QDEF").

- Four registered QDEF Record Types (Content-Preview, Content-Body,
  Paper-Preview, Paper-Body, §2.1), each with an independent key
  namespace, replace the single shared `core_meta_item`/`bulky_meta_item`
  key space and its "valid in Content/Paper only" annotations.
- Preview (always plain, unwrapped, repeated on every code in a multi-code
  group, §4.1, §5.1) / Body (optionally Compress- and Split-wrapped)
  replaces the old three-part `core_meta_item || bulky_meta_item ||
  content` stream and bespoke `part_meta` sectoring. `content_sha256`/
  `bulky_meta_sha256`/`bulky_meta_compressed_bytes` are gone, superseded by
  QDEF's Split Wrapper `group_id` (mandatory decoder-verified) and a
  Wrapper's already-self-delimiting byte-string framing.
- Every TagDrop-defined key is odd (optional) in this version; even keys
  are reserved headroom for a future must-understand field (§2.2),
  resolving tagdrop#63 by adopting QDEF's even/odd rule directly.
- `root_hash`'s formula gained an explicit self-reference fix (§4.4) after
  moving into Preview itself; the signed-message formula (§10) was
  re-derived to match it exactly (`Preview' || Body'`, one shared hash for
  both), rather than left as two independently-defined computations.
- Outer framing began differing deliberately by carrier (§2): the
  `tagdrop:` URI stays unwrapped (no QDEF magic header — the scheme itself
  already dispatches); byte-mode QR gains QDEF's magic header (5 bytes at
  the time — magic + version byte); NFC stays prefix-free (its own MIME
  type already dispatches). Same Record bytes across all three, so an
  encoder builds them once and picks framing last.
- No code changes shipped yet as of this entry — see the repo's own
  tracking for implementation status; §10's "Implementation status" note
  is explicit about what's actually wired through today versus what this
  document now specifies as the target shape.

**Version 1** (superseded by version 2 above; kept below as historical record)

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
- Content-teaser `description` (key 40, both payload kinds) and per-file `description` (local key 4, in `files[]` entries) — distinct from `label`/`hint` and from the short-caption `title` (key 51, both payload kinds) (§4.3).
- AES-256-GCM hidden override maps (§9), Content payloads only: self-contained `nonce||ciphertext||tag` blob carried in the reassembled stream's `content` slot, applied after compression. Optional non-binding `encryption` hint (key 28). `key_material`/`retain_key` (keys 30/31) matched by trial decryption ("discovery, not declaration"). PBKDF2-HMAC-SHA256 passphrase derivation via `kdf_alg`/`kdf_salt`/`kdf_iters` (keys 37–39).
- ML-DSA-44 post-quantum signatures (§10): `signature_algorithm`/`signature`/`signer_pubkey`/`signer_id`/`signer_label` (keys 32–36), additive and not affecting `cache_id`/`root_hash`/`content_sha256`/`bulky_meta_sha256`. Real sign/verify implemented in both reference codecs: the web tools via `@noble/post-quantum` (generator: both Single File and Paper Layout tabs; reader: any scanned code), and the Kotlin app via BouncyCastle (`CreateActivity`: Single-code screen only; `ReceiveActivity`: any scanned code) — `CreatePaperActivity` is the one place that still lacks a signing checkbox, though the underlying codec functions support it (§10, §15).
- Author-declared `created_at` (key 52, both payload kinds): optional Unix timestamp (seconds) recording when the payload was authored, taken from the authoring device's clock at encode time — self-declared like `lat`/`lng`, not a verified/trusted timestamp.
- Drop source registry (§17): `source_url` (key 56) in `core_meta_item` — a URL pointing to a JSON file listing nearby TagDrop drop locations. When scanned, the app prompts the user before fetching. Source management (add/enable/disable/remove) is explicit and user-controlled; no automatic background fetches.
- Trail steps and forks (§4.3): `step` (key 57 in `core_meta_item`; local key 10 in a `related` entry) is an optional 1-based absolute ordinal scoped by `set` (no declared trail length), letting a decoder show "stop N" and, when two `related` entries share the same `set`/`step`, present a fork of alternative next stops rather than a single pointer.

---

## 15. Reference Implementations

**Status:** the web tools (generator, reader, examples) encode and
decode both Content and Paper using the current QDEF Record wire shape
(Preview/Body split, Compress Wrapper, Split Wrapper, §2-§5) — no wire
format left on the old envelope in any of the three web tools (a small
old-format decode path is deliberately kept in the reader only, for
backward compatibility with codes scanned before the port). The Kotlin
Android app is the one implementation still on version 1's
`core_meta_item`/`bulky_meta_item`/`part_meta` envelope for both payload
types — porting it is tracked follow-up work. Signing (§10) uses the new
QDEF key layout for both Content and Paper in the web tools; the Kotlin
app still signs with the old key layout. File paths and high-level
responsibilities below stay accurate either way.

- **Android app:** `app/src/main/java/com/github/mofosyne/tagdrop/data/format/`
  - `TagDropCodec.kt` — encode/decode both payload types; `contentId()`, `createContentSectors()`, `createPaper()`
  - `Base41.kt` — TagDrop's own alphabet, packed like RFC 9285 Base45 (§2)
  - `MiniCbor.kt` — minimal CBOR encoder/decoder; supports arrays (major 4), nested maps, float64 (major 7), and top-level CBOR sequences (RFC 8742). Currently limited to 32-bit unsigned integers — no longer a gap for QDEF Record Type IDs specifically, now that all four fit in 16 bits (§2.1, version 6); still a real limitation for any future 64-bit-scale field.
  - `SectorAssembler.kt` — multi-sector assembly with SHA-256 verification; tracks any number of in-flight `(type, cache_id)` groups concurrently
  - `TagDropLinkResolver.kt` — resolves `tagdrop://<domain>/<slug>` and `tagdrop://[<domain>]@<rootHash>/<slug>` navigation links; also locates the `style.css` sibling for `text/markdown` content (§7)
  - `MarkdownRenderer.kt` — renders `text/markdown` content to HTML (§7) via CommonMark

- **Android database:** `app/src/main/java/com/github/mofosyne/tagdrop/data/db/`
  - `FoundCache.kt` — Room entity for scanned file caches
  - `ScannedPaper.kt` — Room entity for scanned paper manifests
  - `AppDatabase.kt` — Room database with migrations

---

## 16. Design Notes and Alternatives Considered

**Why not extend `data:` URI syntax?** (issues #2, #4, #13) Adding parameters like `;seq-id=`, `;seq-total=`, `;crc=` to the data URI was the original approach. It fails because data: URIs are opaque to QR readers — there's no way to route them to the app by scheme. The `tagdrop:` scheme gives us OS-level routing and a clean separation between the envelope and payload.

**Why a version/type envelope instead of URI path segments or per-map keys?** (version-1-era reasoning; its conclusion — self-describing bytes independent of carrier — is exactly what §2.1's per-Record Type ID now provides too, via QDEF's key-0 routing instead of a bespoke envelope) An earlier draft put `v1/<type>/` in the URI path and a `version` key inside each payload map. That works for QR, but raw-byte carriers (NFC NDEF, JABCode raw — §12/§13) have no URI wrapper, so type/version information would either be lost or have to be guessed from which map keys happen to be present — fragile, and ambiguous for future payload types. Prefixing every sector with a CBOR Sequence (RFC 8742) led by `CBOR(version) || CBOR(type)`, 1 byte each for the foreseeable range of values — made the same bytes self-describing on every carrier: Base41-encode them for `tagdrop:<base41>`, or store them raw in an NDEF record, with identical decode logic either way. It also let the URI collapse to `tagdrop:<base41>` (no `//`, no `/<type>/` segment), gave a clean disambiguation rule against `tagdrop://<rootHash>/<slug>` navigation links (§2), and — being a sequence rather than a CBOR array — cost one less byte than an equivalent `[version, type, part_meta, sector_bytes]` array. `version` lived *only* in the envelope, not redundantly inside `part_meta` too, for the same reason two fields claiming to describe the same fact can disagree — the same class of ambiguity RFC 9112 §6.3 closes off by forbidding conflicting `Content-Length`/`Transfer-Encoding` framing in HTTP/1.1. Version 2 (§2) kept the "self-description belongs in the outer framing, not duplicated inside" conclusion, but moved self-description onto each Record's own Type ID rather than a TagDrop-specific 4-item envelope; version 3 went further and dropped the outer framing's version byte entirely (§14), since QDEF's magic header now carries no field beyond the magic itself.

**Why `@` to mark a pinned hash, not `:` or triple-slash?** (issue #51) An
earlier draft resolved `domain`/`root_hash` collisions purely by *lookup
order* — try an exact `root_hash` lookup first, fall back to a domain scan
only on a miss. That closes the obvious case but is order-dependent: if a
device scans an attacker-authored paper whose self-declared `domain` is
crafted to exactly match a real paper's `root_hash` hex string *before* it
ever scans the real paper, there's no real `root_hash` registered yet for
the lookup to find first, so the domain claim wins silently — the device
has no way to tell it apart from the genuine paper. Splitting `domain` and
`root_hash` into syntactically distinct positions (rather than relying on
order) needed a marker; alternatives considered and rejected:

- `:` (`tagdrop://hash:<hex>/<slug>`) — rejected outright: `:` is the
  host:port delimiter in URI authority syntax (WHATWG URL Standard), so
  anything after it that isn't a bare port number is a hard parse failure.
  This is the *exact* hazard §2 already documents for why root hashes use
  hex instead of Base41 in this position — reusing `:` as a marker would
  reintroduce it deliberately.
- Triple-slash / empty authority (`tagdrop:///<hash>/<slug>`,
  RFC 3986-idiomatic, like `file:///`) — not adopted: these links are
  clicked inside a real Android WebView, where Chromium's URL canonicalizer
  parses the href before the app's own resolver ever sees it, and whether
  an empty authority survives that pass unmangled wasn't verified.
- Requiring a literal `.` in domain names — rejected as strictly weaker: it
  closes the bare-vs-bare ambiguity but has no way to express "named *and*
  pinned" at once, which the combined form below gets for free.
- Separate sub-schemes (`tagdrop-hash://`, `tagdrop-domain://`) — workable,
  but doubles WebView interception surface and is the most verbose option.

`@` was chosen instead, reusing standard URI authority syntax
(`[userinfo "@"] host`, RFC 3986 §3.2.1) rather than inventing new
TagDrop-specific punctuation: `domain` occupies the userinfo slot,
`rootHash` the host slot. The marker sits on the hash side (`@<hash>`), not
the domain side, because hash strings are always scanned, copy-pasted, or
tool-generated — never hand-typed or memorized — so the punctuation costs a
human nothing there, whereas `domain` exists specifically so links can be
typed/spoken/memorized, and forcing extra punctuation onto it would
undercut that purpose. The two non-bare forms map cleanly onto a
distinction worth keeping on its own merits (§7 "Domains"): a bare
`<domain>` is a **floating** reference that can be silently re-pointed by a
later paper claiming the same name, while `@<rootHash-hex>` is a
**pinned**, immutable one; `<domain>@<rootHash-hex>` gets both at once, with
the leading label purely decorative and the hash always authoritative for
resolution.

**Why not NDEF as the primary format?** (issue #16) NDEF is a memory-layout format for NFC chips with a specific capability container. Adapting it for QR codes adds complexity without benefit — the QR code already handles error correction and binary framing. We use NDEF only as a transport option for NFC tags (§12).

**Binary mode vs alphanumeric Base41:** Raw binary QR codes store 8 bits/char. Alphanumeric Base41 stores 2 bytes in 3 characters at 5.5 bits/char = ~8.25 bits/byte of original data. The tiny efficiency loss is worth the interoperability gain for most codes: alphanumeric QR codes are more reliably decoded by all readers, and the `tagdrop:` prefix is human-readable/typable. Non-initial sectors are the exception — they're always camera-scanned, never hand-typed or shared as text — so the Android reference reader also accepts a QR byte-mode segment carrying the raw CBOR sequence directly (no `tagdrop:`/Base41 wrapper), per §13. The web generator (`tools/generator/index.html`) splits content too large for one QR into a Manifest + Chunks and, by default, renders Chunk QR codes in binary mode (toggleable back to alphanumeric `tagdrop:` URIs); the Manifest QR is always alphanumeric, since it must stay human-typable/shareable like a Single. The Android app's only multi-sector generator, `ShareQrActivity` (re-sharing an already-cached item too large for one QR), still emits alphanumeric `tagdrop:` URIs for every sector.

**Compression:** DEFLATE was chosen over LZMA (issue #2) because it is available in every Java/Android standard library (`java.util.zip`), requiring no dependency. LZMA achieves better ratios for larger payloads but is a future extension (compression value 2).

**Structured append in QR spec:** QR's built-in structured append (up to 16 codes) is not portable across barcode formats and is poorly supported by many readers. Our sector approach (§4.1, §5) works with any 2D barcode type and supports up to 2^32 sectors.

**No explicit folder hierarchy:** Papers list files as flat slug strings. Slugs that contain `/` (e.g. `images/photo.jpg`) create virtual path conventions without requiring a tree structure in the CBOR or the database. String equality on the full slug is the only lookup operation needed. This keeps the directory format simple and avoids directory-traversal edge cases.

**Non-HTML content types (images, audio, MIDI):** The cache stores raw bytes for any MIME type, and the Android WebView can serve them all via `WebViewClient.shouldInterceptRequest`. When a loaded HTML page contains `<img src="tagdrop://...">`, `<audio src="tagdrop://...">`, or any other subresource reference, `shouldInterceptRequest` intercepts the fetch, looks up the slug in the local DB, and returns a `WebResourceResponse` with the cached bytes — no network involved. MIDI files require a JS player library embedded in the same HTML file (the MIDI bytes are served as `audio/midi` via the same mechanism). Purely binary payloads (a standalone image, a MIDI file) are displayed/played by wrapping them in a minimal HTML page that references the tagdrop:// URI. The navigation flow (`shouldOverrideUrlLoading`) and the subresource flow (`shouldInterceptRequest`) are independent: the former fires when the user clicks a link and loads a new top-level page; the latter fires for every embedded asset on the current page. Both resolve through the same `TagDropLinkResolver`.

---

## 17. Drop Source Registry

A drop **source** is a URL pointing to a JSON file that lists TagDrop drop locations — cache IDs, coordinates, and hints for drops placed in the physical world. Sources let communities publish and share lists of drops without changing the wire format of the drops themselves.

### Wire field

`source_url` (text, optional — Content-Preview key 55 or Paper-Preview key
41, §3.1/§3.3) may appear on any Content or Paper payload — typically a
key-only or hint-only code (no Body at all, or a Body with no meaningful
`content`) placed alongside a physical drop or distributed as a sticker.
It names a URL that the finder's app can add as a source. No other
wire-format changes are needed: the existing `hint` names the source for
the user; `lat`/`lng` can locate the physical QR that carries it; `icon`
gives it a visual identity — all already defined per-Preview-type (§3.1,
§3.3).

### App behaviour

When a code carrying `source_url` is scanned the app MUST prompt the user before fetching anything:

> **"Add drop source: \<hint or label\>? [Add] [Skip]"**

The full URL MUST be shown to the user before they confirm. No fetch occurs on Skip. On Add, the URL is stored in the local source list and fetched immediately (if the device is online); the app MUST NOT fetch it silently or in the background without the user having added it first.

A **source management screen** (separate from the map) lists all added sources with:
- Enable / disable toggle (disabled sources contribute no pins to the map)
- Remove (deletes the stored URL and all pins derived from it)
- Last-fetched timestamp and entry count
- Manual refresh button

Re-fetching is user-initiated or on a configurable schedule chosen by the user. The app caches the last-fetched JSON locally so the map works offline after the first fetch.

**Privacy:** fetching a source URL reveals the device's IP address and approximate fetch time to whoever hosts the file. The app SHOULD display this caveat on the Add prompt. Source files SHOULD be hosted on servers whose privacy practices users can assess (e.g. static GitHub Pages).

### Source JSON format

```json
{
  "version": 1,
  "label": "London Dead Drops",
  "updated": "2026-07-03",
  "drops": [
    {
      "id": "a3f8b2c1d4e5f6a7",
      "lat": 51.5007,
      "lng": -0.1246,
      "hint": "Behind the loose brick on the north wall",
      "description": "Pedestrian underpass, north face of the arch, eye level",
      "status": "working",
      "status_updated": "2026-07-03"
    }
  ]
}
```

Field names deliberately mirror Content-Preview's wire format (§3.1) where they overlap — `hint` (key 3), `description` (key 11), `lat`/`lng` (keys 23/25) — so a hint QR scan already contains every entry field except `status` and `status_updated`. A future app feature could auto-generate a drops.json submission from a scanned hint QR with those two fields added by the submitter.

Top-level fields:

| Field | Type | Notes |
|---|---|---|
| `version` | uint (required) | Schema version. Currently `1`. |
| `label` | string (opt) | Human-readable name for the source. |
| `updated` | string (opt) | ISO 8601 date of last content change, e.g. `"2026-07-03"`. |
| `drops` | array (required) | List of drop entries (may be empty). |

Per-drop entry fields:

| Field | Type | Notes |
|---|---|---|
| `id` | string (required) | `cache_id` of the drop's TagDrop code — 16 lowercase hex characters (SHA-256 of content bytes, first 8 bytes, §4.4). Matches Content-Preview key 1. |
| `lat` | number (required) | WGS84 latitude. Matches Content-Preview key 23. |
| `lng` | number (required) | WGS84 longitude. Matches Content-Preview key 25. |
| `hint` | string (opt) | Short human-readable location clue, e.g. `"Behind the loose brick"`. Matches Content-Preview key 3. |
| `description` | string (opt) | Longer location description, e.g. directions or context. Matches Content-Preview key 11. |
| `status` | string (opt) | Operational status: `"working"`, `"unknown"` (default), `"broken"`, or `"removed"`. `"removed"` drops are hidden from the map. No wire format equivalent — community-maintained mutable state, not encoded in the QR. |
| `status_updated` | string (opt) | ISO 8601 date of the last status check, e.g. `"2026-07-03"`. No wire format equivalent. |
| `drop_type` | string (opt) | Content type tag; default `"tagdrop"`. Reserved for future extensibility (e.g. listing non-TagDrop drops in a mixed source). |

The `id` field is the join key between the source list and a locally scanned QR: when the finder scans the physical TagDrop code at that location, its `cache_id` matches `id`, and the app marks that pin as found on the map — no server round-trip needed.

An optional `related_sources` top-level array lets a registry recommend other registries. The app surfaces these as an opt-in prompt after a successful fetch — the user sees a checklist of the recommended sources and can add whichever they want (all disabled by default, matching the behaviour for any newly added source):

```json
{
  "version": 1,
  "label": "London Dead Drops",
  "drops": [ … ],
  "related_sources": [
    {
      "name": "UK Nationwide Drops",
      "url": "https://example.org/uk-drops.json",
      "description": "All registered drops across the UK",
      "maintainer": "UK TagDrop Community"
    }
  ]
}
```

`related_sources` entry fields:

| Field | Type | Notes |
|---|---|---|
| `name` | string (required) | Human-readable name for the recommended source. |
| `url` | string (required) | URL of the recommended registry JSON file. |
| `description` | string (opt) | Short description shown to the user before they add it. |
| `maintainer` | string (opt) | Who maintains this registry. |

### Source directory format

The TagDrop project publishes a curated directory of known community registries at:

```
https://mofosyne.github.io/tagdrop/db/sources.json
```

This uses a parallel schema (top-level `sources` array instead of `drops`) and is the bootstrap discovery point — the app's "Browse recommended sources" action fetches this URL and presents a checklist so users can add any registry they want. Third-party registries can also publish their own directories; any URL that serves this schema can be used.

```json
{
  "version": 1,
  "label": "TagDrop Known Sources",
  "updated": "2026-07-03",
  "sources": [
    {
      "name": "TagDrop Community Drops",
      "url": "https://mofosyne.github.io/tagdrop/db/drops.json",
      "description": "Curated list of community-placed TagDrop codes worldwide.",
      "maintainer": "TagDrop project"
    }
  ]
}
```

### The official TagDrop source

The TagDrop project maintains a curated source at:

```
https://mofosyne.github.io/tagdrop/db/drops.json
```

This file is committed directly to the repository (`docs/db/drops.json`) and served via GitHub Pages. To list a drop, open a pull request adding an entry to that file. The entry's `id` must be the `cache_id` of the actual TagDrop code you have placed (computable from the generator or the app's share/inspect screen).
