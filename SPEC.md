# TagDrop Encoding Specification

**Version:** 1  
**Status:** Stable — feedback welcome via GitHub issues

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

`<base41-cbor-sequence>` is a **Base41** encoding of a short **CBOR Sequence** ([RFC 8742](https://www.rfc-editor.org/rfc/rfc8742) — concatenated CBOR data items, no enclosing array or map):

```
CBOR(version) || CBOR(type) || CBOR(payload)
```

| Item | Type | Meaning |
|---|---|---|
| `version` | uint | Format version. Currently `1`. |
| `type` | uint | Payload kind — see table below. |
| `payload` | map | The payload's fields — integer-keyed CBOR map, see §3/§4. |

| `type` | Payload |
|---|---|
| 0 | Single — complete cache in one code |
| 1 | Manifest — header for a multi-code cache |
| 2 | Chunk — one geographic fragment |
| 3 | PaperManifest — directory of files on a physical paper |

For values 0–23, a CBOR unsigned integer is exactly **one byte** (RFC 8949 major type 0, value packed into the initial byte). So the `version`/`type` envelope costs just **2 bytes** total — including for Chunks, which previously carried no type information of their own.

### Navigation links (not QR payloads)

HTML pages embedded in TagDrop caches can link to other files and papers using:

```
tagdrop://<rootHash-hex>/<slug>
```

`rootHash` is the 8-byte SHA-256 of the paper manifest's CBOR sequence bytes, lowercase-hex-encoded (16 characters). `slug` is the file's identifier within that paper. The TagDrop app intercepts these links in its WebView and resolves them against the local scanned-paper database — no network needed.

**Why hex, not Base41, here?** Base41's alphabet includes `:`, which is fine inside the *path* of an opaque URI like `tagdrop:<base41-cbor-sequence>` but not inside a URL's *authority* (host[:port]) component — a `:` there starts the port subcomponent per the WHATWG URL Standard, and anything after it that isn't a bare port number is a hard parse failure for the whole URL. Since the root hash sits in the authority position of a `tagdrop://` link, a Base41-encoded root hash containing `:` (roughly 1 in 4, since `:` is 1 of 41 alphabet characters) would make the link unparseable. Plain lowercase hex has no such character, at the cost of 4 extra characters (16 hex vs 12 Base41 for 8 bytes) — cheap, since navigation links are clicked/typed, not scanned from a QR code.

**Disambiguation:** encoding payloads never contain `//` — `tagdrop:<base41-cbor-sequence>` has no authority component. Navigation links always do — the root hash serves as the link's authority. Base41's alphabet has no `/` character at all, so it can never appear anywhere in a Base41-encoded string, let alone right after the scheme. Encoding URIs and navigation links are therefore unambiguously distinguishable by whether `//` follows the scheme.

**Why Base41?** QR codes have an alphanumeric mode (charset 0–9, A–Z, space, `$%*+-./:`, 45 characters) that stores 5.5 bits per character vs 8 bits per character in binary mode. RFC 9285's Base45 uses that full 45-character set, encoding 2 bytes → 3 alphanumeric characters (~3% overhead over QR's alphanumeric capacity) — far better than Base64 (33% overhead, and forces binary mode since Base64 needs lowercase letters). TagDrop uses **Base41**: the same 2-bytes-to-3-characters packing as Base45, but over a 41-character subset of the QR alphanumeric set — `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$*-.:` — that drops the 4 characters which cause trouble outside QR codes: space and `%` aren't valid unescaped in a URI (RFC 3986), and `+`/`/` carry special meaning in URLs (`+` as a space in query strings, `/` as a path separator). 41 is the smallest alphabet for which 3 characters can still represent every 16-bit value (41³ = 68921 ≥ 65536; 40³ = 64000 does not), so this costs nothing — Base41 output is exactly the same length as Base45 output would be for the same bytes. The result is a string that's always a strictly valid, unescaped URI component, with no percent-encoding step needed on either side. This alphabet is also a safe (if non-optimal) subset of Data Matrix's C40 mode and Aztec's Upper/Digit modes, so the same encoded string stays reasonably dense on those carriers too (§13).

Credit: this is the "BYOA" (bring-your-own-alphabet) variant of Philippe Majerus' [Base41 scheme](https://github.com/sveljko/base41), using his QR/URL-safe alphabet. There's no RFC for this specific alphabet — TagDrop defines it here as its own encoding, reusing Base45's well-understood packing algorithm.

**Case:** encoders MUST emit uppercase letters only. Decoders MUST accept lowercase letters as equivalent to their uppercase counterparts (case-insensitive decode) — this tolerates content that's been manually retyped (e.g. a `tagdrop://` link copied by hand), since `$*-.:` and the digits have no case to confuse and the QR alphanumeric mode itself is uppercase-only by convention.

**Why CBOR?** CBOR (RFC 8949) is binary JSON: self-describing, compact, standardised, and easy to parse without a schema. It is 20–50% smaller than JSON for typical payloads. Integer map keys (used here) are 1 byte each. CBOR Sequences (RFC 8742) let the `version`/`type` envelope reuse the same compact integer encoding, with no extra framing.

---

## 3. CBOR Map Keys

The `payload` map — the third item of the envelope sequence (§2) — uses **integer keys**. Unknown keys must be ignored (forward compatibility).

| Key | Field | Type | Used in |
|---|---|---|---|
| 2 | `cache_id` / `root_hash` | bytes (8) | S, M, C, P |
| 3 | `hint` / `label` | text (opt) | S, M, P |
| 4 | `mime_type` | text | S, M |
| 5 | `content` | bytes | S |
| 6 | `chunk_count` | uint | M |
| 7 | `total_bytes` | uint | M |
| 8 | `sha256` | bytes (32) | M |
| 9 | `chunk_index` | uint | C |
| 10 | `chunk_data` | bytes | C |
| 11 | `filename` | text (opt) | S, M |
| 12 | `compression` | uint (opt) | S, M |
| 13 | `set` | text (opt) | P |
| 14 | `slug` | text (opt) | P |
| 15 | `files` | array | P |
| 16 | `related` | array | P |
| 17 | `collection_id` | bytes (8, opt) | S, M, P |
| 18 | `collection_label` | text (opt) | S, M, P |
| 19 | `collection_tag` | text (opt) | S, M, P |
| 24 | `icon` | text (opt) | S, M, P |
| 28 | `encryption` | uint (opt) | S, M |
| 30 | `key_material` | bytes (32, opt) | S, M, P |
| 31 | `retain_key` | bool (opt, default `true`) | S, M, P |
| 32 | `signature_algorithm` | uint (opt) | S, M, P |
| 33 | `signature` | bytes (2420, opt) | S, M, P |
| 34 | `signer_pubkey` | bytes (1312, opt) | S, M, P |
| 35 | `signer_id` | bytes (8, opt) | S, M, P |
| 36 | `signer_label` | text (opt) | S, M, P |
| 37 | `kdf_alg` | uint (opt) | S, M |
| 38 | `kdf_salt` | bytes (16, opt) | S, M |
| 39 | `kdf_iters` | uint (opt, default 100000) | S, M |

**S** = Single, **M** = Manifest, **C** = Chunk, **P** = PaperManifest

Key 25 is reserved for a future small embedded image icon (raw bytes), as an
alternative to the emoji `icon` field above. Keys 28, 30, and 31 are defined
in §9 (Encryption); keys 32–36 are defined in §10 (Verified Authorship); keys
37–39 are defined in §9 (Passphrase-based key derivation). Key 29 is reserved
and unused — see §9 for why an encrypted override map's nonce doesn't need its
own clear-map field.

**Codes may carry a hidden, encrypted override map.** A Single's trailing
bytes (after its 3-item CBOR Sequence) or a Manifest's assembled chunk bytes
(§5) may be a self-contained, encrypted **override map** — using key numbers
3 (`hint`), 4 (`mime_type`), 5 (`content`, Single only), and 11 (`filename`)
— that overlays the payload/manifest map (the **clear map**) once decrypted.
`encryption` (key 28), if present and non-zero, is an optional hint that this
is the case; its absence does NOT mean the code has no hidden override map —
see §9, "Discovery, not declaration." The clear map's own keys 3/4/5/11, if
present, serve as the values shown before (or without) a matching key. See
§9.

### File entry sub-keys (elements of key 15)

Each element is a CBOR map:

| Key | Field | Type |
|---|---|---|
| 20 | `slug` | text |
| 21 | `mime_type` | text |
| 22 | `file_id` | bytes (8) — `cache_id` of the file's root QR |

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
| 30 | `key_material` | bytes (32, opt) — decryption key for the related paper's content, see §9 |
| 31 | `retain_key` | bool (opt, default `true`) — see §9 |

---

## 4. Payload Types

### 4.1 Single-Code Cache (`type` = 0)

The entire payload fits in one code. Recommended for content ≤ ~800 bytes decoded.

```
envelope: version=1, type=0

payload map {
  2: h'<8 random bytes>', // cache_id — unique identifier
  3: "under the bridge",  // hint — optional, for the finder
  4: "text/html",         // mime_type
  5: h'<content bytes>',  // content — raw or compressed
  11: "poem.html",        // filename — optional
  12: 1,                  // compression — omit if none (0)
  17: h'<8 random bytes>',// collection_id — optional, see §7 Collections
  18: "Spring Sticker Hunt", // collection_label — optional, see §7 Collections
  19: "springtrail2026",  // collection_tag — optional, see §7 Collections
  24: "🌳",                // icon — optional, see §7 Icons
}
```

`content` is the raw payload bytes (after any decompression). If `compression` is present and non-zero, decompress before use.

The example above shows just the 3-item envelope+payload sequence. The code
MAY additionally carry **raw trailing bytes** after this sequence — a hidden,
encrypted override map (§9) that overlays keys 3, 4, 5, and 11 of this map
once decrypted. `encryption` (key 28) is at most an optional hint that this
is the case; its absence doesn't rule it out (§9). Keys 3, 4, 5, and 11
present here serve as the values shown before (or without) a matching
key — see §9.

### 4.2 Manifest (`type` = 1)

The manifest code is placed at the **start** of a multi-code cache (or can be a separate marker — see §6). It announces how many chunks exist, their total size, and a SHA-256 hash for integrity verification.

```
envelope: version=1, type=1

payload map {
  2: h'<8 random bytes>',   // cache_id — same across all codes in this set
  3: "location hint",
  4: "text/html",
  11: "story.html",
  12: 1,                    // compression applied to assembled chunks
  6: 4,                     // chunk_count: 4 codes to collect
  7: 3200,                  // total_bytes: assembled (pre-decompression if compressed)
  8: h'<32-byte sha256>',   // hash of assembled raw chunk data (before decompression)
  17: h'<8 random bytes>',  // collection_id — optional, see §7 Collections
  18: "Spring Sticker Hunt", // collection_label — optional, see §7 Collections
  19: "springtrail2026",    // collection_tag — optional, see §7 Collections
  24: "🌳",                  // icon — optional, see §7 Icons
}
```

The assembled chunk bytes (§5) MAY themselves *be* a hidden, encrypted
override map (§9) which, once decrypted, overlays keys 3, 4, and 11 of this
map (plus `content`, key 5, which doesn't otherwise appear in a Manifest) —
distinguished from plain assembled content only by whether a candidate key
successfully decrypts them (§9, "Discovery, not declaration"). `encryption`
(key 28) is at most an optional hint that this is the case; its absence
doesn't rule it out. Keys 3, 4, and 11 present here serve as the values shown
before (or without) a matching key — see §9.

### 4.3 Chunk (`type` = 2)

Each chunk carries a slice of the raw payload. Chunks are **order-independent** — the app collects them in any order and sorts by `chunk_index` before assembly.

```
envelope: version=1, type=2

payload map {
  2: h'<same cache_id>',    // links this chunk to its manifest
  9: 2,                     // chunk_index: 0-based
  10: h'<chunk bytes>',
}
```

The payload map intentionally omits hint, mime, and filename to minimise size — just the 2-byte envelope (§2) plus these three fields.

### 4.4 Paper Manifest (`type` = 3)

A paper manifest is the **directory code** for a physical paper (A4 sheet, sticker board, poster). Think of it as a floppy disk's FAT: it lists every file on the paper and can point toward related papers at other locations.

```
envelope: version=1, type=3

payload map {
  2: h'<8-byte root hash>',         // SHA-256(envelope+payload)[0:8] — paper's permanent address
  3: "Trail Stop 3 — Oak Tree",     // label — human-readable paper name (optional)
  13: "sunset-trail",               // set — which network/trail (optional)
  14: "oak-tree",                   // slug — this paper's address within the set (optional)
  15: [                             // files — directory of codes on this paper
    {20: "index",   21: "text/html",  22: h'<file_id>'},
    {20: "map",     21: "image/svg+xml", 22: h'<file_id>'},
  ],
  16: [                             // related — hints to other papers
    {3: "Next stop: the red letterbox 200m north", 14: "letterbox", 23: h'<paper_id>',
     26: -33.8688, 27: 151.2093},   // lat/lng — optional, for a placeholder map pin
    {3: "Start of trail: town square notice board"},
  ],
  17: h'<8 random bytes>',          // collection_id — optional, see §7 Collections
  18: "Spring Sticker Hunt",        // collection_label — optional, see §7 Collections
  19: "springtrail2026",            // collection_tag — optional, see §7 Collections
  24: "🌳",                          // icon — optional, see §7 Icons
}
```

`root_hash` (key 2) is computed externally after the rest of the sequence is finalised — see §4.5.

**Located related papers:** A `related` entry (key 16) may include `lat`/`lng`
(keys 26/27) — the approximate coordinates of that related paper, if the
author knows them. The app shows these as a "❓" placeholder pin on the map
for related papers that haven't been scanned yet, helping the finder navigate
toward them. Once that paper is scanned, its own `ScannedPaper` location (set
from the device's GPS at scan time) replaces the placeholder.

**Navigation:** HTML files on the paper can link to other files using:
```html
<a href="tagdrop://<paper-root-hash-hex>/map">See the map</a>
```
The TagDrop app intercepts these links and resolves them from the local database.

### 4.5 Content-Addressed IDs (IPFS-inspired)

TagDrop uses **content-addressed identifiers** — the same content always gets the same ID, regardless of who created it or where it was found.

**File IDs (`cache_id`):**
```
cache_id = SHA-256(uncompressed content)[0:8]
```
Two QR codes encoding the same bytes will have the same `cache_id`. This enables deduplication across multiple papers and papers made by different authors.

**Paper root hashes:**
```
root_hash = SHA-256(paper manifest's CBOR sequence bytes)[0:8]
```
A paper's root hash is computed from its manifest's CBOR sequence (envelope + payload map, §2), which includes the root hash field itself (key 2). This is the same chicken-and-egg situation IPFS solves the same way: encode the manifest *without* the hash, compute the hash, insert it, then re-encode. The root hash is the paper's permanent, immutable address. Because paper is inherently immutable (you can't update a sticker), this is fine — a new revision gets a new root hash.

**Three-level hierarchy:**

```
Paper (root hash)
  └─ Files (cache_id)
       └─ Chunks (cache_id of manifest, linked by chunk codes)
```

---

## 5. Multi-Code Assembly Protocol

1. **Scan manifest** (any time — before, between, or after chunks).
2. **Scan chunks** in any order. The app tracks which `chunk_index` values have been collected.
3. When `chunks_received == chunk_count`: assemble in ascending `chunk_index` order → concatenate bytes.
4. Verify: `SHA-256(assembled) == sha256` from manifest. Reject on mismatch.
5. If the assembled bytes are ≥ 28 bytes, try AES-256-GCM decryption as
   `nonce(12) || ciphertext || tag(16)` (§9) against every known
   `key_material`. If one authenticates, decompress the plaintext if
   `compression != 0` and CBOR-decode it as the override map (§9) — merge it
   onto the manifest map (override map's keys win) to get the final
   `hint`/`mime_type`/`content`/`filename`. Otherwise, decompress the
   assembled bytes if `compression != 0`; the manifest map's fields are final
   as-is, with `content` being the (decompressed) assembled bytes.
6. Deliver MIME-typed content to the viewer.

**Resumability (issue #14):** Because assembly only needs the CBOR map stored in each QR, a partially-collected set can be saved to a database and resumed later. Each chunk carries `cache_id` so the app can match newly-scanned chunks to a pending collection.

**Geographic distribution:** Each chunk is an independent, self-contained QR code. Chunks can be placed at geographically separate locations (along a trail, in different rooms, across a city). The finder accumulates them over time. The hint in the manifest can describe the treasure hunt.

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
Location A: [ Manifest: tagdrop:<base41> ]   ← start here
Location B: [ Chunk 0:  tagdrop:<base41> ]
Location C: [ Chunk 1:  tagdrop:<base41> ]
Location D: [ Chunk 2:  tagdrop:<base41> ]
```

Or the manifest can be omitted from the field and provided out-of-band (e.g. posted online, given at registration). In that case all codes in the field are chunks — the app will queue them and complete assembly once the manifest is provided.

**Chunk size recommendation:** Target ~600 bytes per chunk (decoded), which encodes to ~900 Base41 characters and fits in a QR Version 15 (M error correction) that prints cleanly at 3cm × 3cm.

---

## 7. TagDropNet — The Offline Paper Web

A collection of physical papers with paper manifests forms a **TagDropNet**: an offline, content-addressed hypertext web made of paper, with no server, no internet connection, and no central authority.

### Paper as floppy disk

Each A4 sheet is analogous to a floppy disk:

| Floppy disk concept | TagDrop equivalent |
|---|---|
| Disk label / volume name | `label` field in paper manifest |
| FAT (file allocation table) | paper manifest QR code |
| Sectors | individual file QR codes (or chunk codes for large files) |
| Directory | `files` array in paper manifest |
| Volume serial number | `root_hash` (content-addressed, permanent) |

A recommended layout for an A4 paper:

```
┌─────────────────────────────────────────────┐
│  [ Manifest QR ]   Trail Stop 3 — Oak Tree  │
│                                             │
│  [ index.html ]    [ map.svg ]              │
│                                             │
│  Next: letterbox 200m north                 │
└─────────────────────────────────────────────┘
```

Scan the manifest first to get the directory, then scan whichever file you want — you don't have to scan everything.

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
envelope or payload-map changes — so a Markdown page picks up paper-wide
styling just by the paper manifest listing a `style.css` file alongside the
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

### Collections (ad-hoc grouping)

`set`/`slug` require a named, coordinated trail — every paper in the set
agrees on the set name and a unique slug, and (for paper manifests) is
addressed via its content-derived `root_hash`. That's the right model for a
curated trail or exhibition.

For looser groupings — a handful of stickers scattered by the same person, a
single-file drop that's part of a bigger scavenger hunt, or any case where
there's no shared directory to scan first — the optional `collection_id`
field (key 17, 8 random bytes) provides a lighter-weight mechanism:

- The author generates one random `collection_id` and stamps it into every
  QR code (Single, Manifest, or Paper Manifest) that should be grouped
  together. There's no central directory listing what belongs to the
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

### Icons

`icon` (key 24) is an optional text field — typically a single emoji — that
authors can stamp onto a Single, Manifest, or Paper Manifest to give a page
or collection a visual identity (e.g. "🌳" for a trail stop under a tree,
"📖" for a story page). The TagDrop app shows it in a small icon slot on the
Collections, History, and collection-detail screens.

For an ad-hoc collection (`collection_id`), the app uses the icon from the
first scanned item that has one — the same "first wins" pattern used for
`collection_label`.

Key 25 is reserved for a future small embedded image icon (raw bytes), as an
alternative for authors who want a custom image instead of an emoji. The
icon slot in the app's UI is designed to host either form.

## 8. Compression

| `compression` value | Algorithm |
|---|---|
| 0 (or absent) | None |
| 1 | DEFLATE, zlib-wrapped (RFC 1950) |
| 2–255 | Reserved |

Compression is applied to the complete assembled payload (in multi-code) or the content field directly (in single-code). The `sha256` in the manifest is over the **compressed** assembled bytes (before decompression), so integrity can be verified before decompression.

DEFLATE typically achieves 50–70% size reduction on HTML and text, effectively doubling QR capacity for textual content.

---

## 9. Encryption

A payload's `hint` (3), `mime_type` (4), `content` (5), and `filename` (11)
may optionally be shadowed by a hidden, encrypted **override map** that
overlays the payload/manifest map (the **clear map**), independently of
compression (§8). Unlike most of this format, the override map's presence is
**never required to be declared** — see "Discovery, not declaration" below.
A code with no declared `encryption` and an unremarkable clear map can still
be carrying one.

| `encryption` value | Algorithm |
|---|---|
| 0 (or absent) | None declared |
| 1 | AES-256-GCM |
| 2–255 | Reserved |

`encryption` (key 28) is an **optional hint**, not a precondition: a code MAY
set it to `1` to advertise "scan a key to unlock more" (e.g. for a "🔒
Locked" badge in the UI). Its absence does NOT mean the code has no hidden
override map.

| Key | Field | Type | Used in |
|---|---|---|---|
| 28 | `encryption` | uint (opt) | S, M |
| 30 | `key_material` | bytes (32, opt) | S, M, P, `related` entries |
| 31 | `retain_key` | bool (opt, default `true`) | wherever `key_material` appears |

Key 29 is reserved and unused: the GCM nonce travels embedded in the override
map's ciphertext itself (below), so a separate clear-map `nonce` field would
only add bulk and a second (always-matching, or suspiciously-not) value to
cross-check — without it, there's simply nothing nonce-shaped in the clear
map at all.

### Encrypted override map

A hidden **override map** is a CBOR map using the same key numbers as the
clear map — 3 (`hint`), 4 (`mime_type`), 5 (`content`, Single only), 11
(`filename`):

```
override map {
  3: "treasure map",          // hint — optional
  4: "image/png",              // mime_type
  5: h'<real content bytes>',  // content — Single only
  11: "map.png",               // filename — optional
}
```

Its CBOR bytes are compressed (§8, if `compression != 0`) and then
AES-256-GCM-encrypted (see below) to a single **self-contained blob**:

```
nonce(12 bytes) || ciphertext || tag(16 bytes)
```

The nonce travels with the blob — nothing in the clear map is needed to
locate or interpret it.

**Where this blob lives:**

- **Single (`type` = 0):** as **raw trailing bytes** after the code's 3-item
  CBOR Sequence (envelope + payload map, §2) — *not* a 4th CBOR item, just
  bytes appended after a complete, self-delimiting sequence (§2, "Decoders
  tolerate trailing bytes"). Any trailing bytes of length ≥ 28 (the minimum
  possible blob: 12-byte nonce + 16-byte tag, for an empty plaintext) are a
  candidate.
- **Manifest (`type` = 1):** the assembled chunk bytes (§5) themselves, if ≥
  28 bytes, are a candidate — *in addition to* whatever they decode to as
  plain `content` per `compression`. §5 step 5 tries both.

**Producing the final view:** for each candidate `key_material` the app
holds, try AES-256-GCM decryption of the candidate blob using its first 12
bytes as the nonce. If the authentication tag checks out, decompress the
remaining plaintext (if `compression != 0`) and CBOR-decode it as the
override map, then merge it onto the clear map —
`final = {...clear map, ...override map}` — with the override map's values
winning on key collisions. If no key has yet succeeded (or the code carries
no such blob at all), the clear map *is* the final view, exactly as in
§4.1/§4.2.

```
clear map {
  2: h'<8 random bytes>',  // cache_id — random, see below
  12: 1,                   // compression — applies to content (Single) /
                           //   assembled bytes (Manifest), and to the
                           //   override map's CBOR bytes before encryption
  28: 1,                   // encryption — optional "🔒 Locked" hint
}
// + trailing bytes (Single) / assembled bytes (Manifest):
//   h'<12-byte nonce>' || h'<ciphertext of (compressed) override map>' || h'<16-byte tag>'
```

**Cover stories, or no story at all:** the clear map's `hint` (3),
`mime_type` (4), `content` (5, Single only), and `filename` (11) are shown
(and used) until a matching key is found. They MAY be a generic "locked"
placeholder, a believable **decoy** (different hint/MIME/content/filename
than what's really there), or — since `encryption` need not be declared —
genuine, unremarkable content with no relation to the hidden override map at
all. A code can look, scan, and behave exactly like any other TagDrop code
while still carrying a hidden layer in its trailing/assembled bytes. Once a
matching `key_material` is found, the override map's same-numbered fields
replace the clear map's — the displayed content, hint, MIME type, and
filename **self-correct** to the real ones.

For the fully-undeclared case to actually be deniable, the clear map needs
genuine, unremarkable content of its own — an empty or trivially-placeholder
clear map is itself a tell ("why would this code exist at all?").

**Order of operations:** compress (§8) first, then encrypt — encrypted bytes
are high-entropy and don't compress further, so encryption is always the
last transform applied before transmission, and the first reversed on
receipt. What gets compressed-then-encrypted is the override map's CBOR
bytes. `sha256` (key 8, §5) continues to cover the assembled bytes exactly as
transmitted, i.e. after compression *and* encryption, so a partially- or
incorrectly-assembled multi-code cache can be detected before a decryption
key is even available.

**`cache_id` for a code carrying a hidden override map is random, not
content-addressed.** §4.5 defines `cache_id = SHA-256(uncompressed
content)[0:8]` so that identical content always gets the same ID — useful for
deduplication, but exactly the wrong property here: it would let anyone
compute the `cache_id` of the clear map's own `content` (cover story or not)
and check whether any code in the wild carries it, linking that code to a
known document regardless of what's hidden inside. An author embedding a
hidden override map MUST set `cache_id` (key 2, in the clear map) to 8 random
bytes, independent of both the clear map's own `content` and the override
map's real `content`.

**AES-256-GCM:** the 12-byte nonce prefixing the blob MUST be unique for
every encryption performed under a given key — a reused nonce breaks
AES-GCM's confidentiality entirely. The (compressed) override map's CBOR
bytes are encrypted to `ciphertext || 16-byte authentication tag` (tag
appended) — the default output of both `javax.crypto.Cipher`
("AES/GCM/NoPadding") on Android and `SubtleCrypto.encrypt()` in browsers —
then prefixed with the nonce to form the blob above, used as the Single's
trailing bytes or the Manifest's assembled chunk bytes.

### Decryption keys

A decryption key is **32 raw bytes** (`key_material`, key 30) — an
AES-256-GCM key, used directly with no passphrase or key-derivation step. It
can appear:

- on any Single, Manifest, or Paper Manifest payload, as a top-level field —
  "this code also carries a key for other content," independent of whatever
  `content` (if any) the code itself carries; or
- on an element of a Paper Manifest's `related` array (key 16, §4.4) —
  "scanning this paper reveals a key for the related paper," for trails
  meant to be discovered in sequence.

A Single payload containing `key_material` may omit `content` and
`mime_type` entirely (otherwise required for Single payloads, §4.1) — a code
can be *just a key*, with no displayable content of its own:

```
payload map {
  30: h'<32-byte AES-256 key>',
  31: false,                 // retain_key — use once against what's cached now, then forget
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
`cache_id` (key 2) too, since it references no content of its own to be
deduplicated or cached against.

`retain_key` (key 31, default `true`) is the author's recommendation for
whether the app should remember this key for future matches across scanning
sessions (`true`), or use it only against content already cached *right now*
and then discard it (`false`). It's a recommendation, not an enforceable
guarantee — an app or user can always choose to remember a key regardless.

**Discovery, not declaration:** no field says which content a given
`key_material` decrypts, and — per above — `encryption` (key 28) is at most
a hint, not a precondition. Instead, whenever the app learns a new
`key_material`, it tries AES-256-GCM decryption — using the candidate blob's
own embedded 12-byte nonce — against the trailing bytes of every cached
Single and the assembled-chunk bytes of every cached Manifest that are ≥ 28
bytes and haven't already been opened. A successful authentication-tag check
is the match; the app decompresses (if `compression != 0`) and CBOR-decodes
the result as the override map (§9), then merges it onto the clear map —
refreshing the displayed `hint`/`mime_type`/`content`/`filename` to their
real values. Symmetrically, whenever a new code is cached, its
trailing/assembled bytes (if ≥ 28 bytes) are tried against every
previously-seen `key_material` (subject to that key's `retain_key`). This is
cheap — AES-GCM decryption of a few KB against a handful of candidates is
negligible, with a false-positive authentication rate of ~2⁻¹²⁸ — so trying
one key against an entire trail's worth of cached codes costs nothing
measurable, and means **scan order doesn't matter**: the key first, the
content first, or either in a later session, the app reconciles them
whichever order they arrive in.

### Privacy properties

A few of the choices above double as standard **plausible deniability**
measures — the inability for an observer to distinguish "this contains
something hidden" from "this is just opaque data," even with some of the
format in plain view. The same property underlies things like VeraCrypt's
hidden volumes or OTR's deniable authentication.

- **Ciphertext is indistinguishable from random.** AES-GCM ciphertext with a
  fresh nonce is computationally indistinguishable from random bytes. Without
  the right key, only the **clear map** is visible — the envelope
  (`version`, `type`), `cache_id`, `compression`, optionally `encryption`,
  whichever optional collection/icon/manifest-sizing fields the author
  included, and whatever `hint`/`mime_type`/`content`/`filename` (§9) the
  author chose: a placeholder, a decoy, or genuine unremarkable content with
  no relation to what's hidden. A hidden override map's trailing/assembled
  bytes reveal nothing about the real values, or whether any particular
  `key_material` unlocks them — and genuine cover content is
  indistinguishable from "this is simply the (unencrypted) content, full
  stop."
- **Decoders tolerate trailing bytes.** A CBOR Sequence (RFC 8742, §2) is
  self-delimiting — a decoder reads exactly as many bytes as the known items
  require and stops. TagDrop decoders MUST NOT treat additional bytes after
  a complete, valid envelope+payload sequence as an error. This is what lets
  a Single carry a hidden override map (§9) as raw trailing bytes — to a
  decoder that doesn't know to look for them, indistinguishable from padding
  or noise. The same tolerance also lets those trailing bytes themselves be
  followed by a second, independent envelope+payload(+override) sequence,
  encrypted under a different key.
- **Keys and content are the same shape.** A `key_material`-only code and a
  small code carrying a hidden override map both look like a short CBOR map
  of high-entropy byte strings, optionally followed by trailing bytes that
  are equally high-entropy. Nothing marks "this is a key," "this is locked
  content," or "this is just a normal code with some padding."
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
a shared passphrase using PBKDF2-HMAC-SHA256. Three optional fields in the
**clear map** signal this:

| Key | Field | Type | Description |
|---|---|---|---|
| 37 | `kdf_alg` | uint | KDF algorithm: `1` = PBKDF2-HMAC-SHA256 |
| 38 | `kdf_salt` | bytes (16) | Random salt; unique per encryption |
| 39 | `kdf_iters` | uint | PBKDF2 iteration count; default `100000` if absent |

When `kdf_alg = 1` is present in the clear map alongside a candidate override
blob (trailing bytes for a Single, assembled-chunk bytes for a Manifest — see
"Where this blob lives" above), the reader MUST:

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
passphrase-encrypted code has `kdf_alg`/`kdf_salt` in its clear map but no
`key_material` field and no separate key QR; a key-code-encrypted code has
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
encryption (§9) and to content-addressing (§3, §4.5) — a signed code has the
same `cache_id` / `root_hash` and `sha256` as its unsigned equivalent, and
signing is **opt-in**: most TagDrop codes (stickers, treasure hunts, paper
backups) need no signature at all.

| `signature_algorithm` value | Algorithm |
|---|---|
| 0 (or absent) | Unsigned |
| 1 | ML-DSA-44 (Dilithium2, FIPS 204) |
| 2–255 | Reserved |

| Key | Field | Type | Used in |
|---|---|---|---|
| 32 | `signature_algorithm` | uint (opt) | S, M, P |
| 33 | `signature` | bytes (2420, opt) | S, M, P |
| 34 | `signer_pubkey` | bytes (1312, opt) | S, M, P |
| 35 | `signer_id` | bytes (8, opt) | S, M, P |
| 36 | `signer_label` | text (opt) | S, M, P |

**Implementation status:** specified for forward-compatibility, not yet
implemented in either reference implementation (§15). ML-DSA-44 is not
available in `java.security`/Android's crypto providers or in
`SubtleCrypto` (Web Crypto), so adding it requires a new dependency in both
— e.g. BouncyCastle for the Kotlin app, and a pure-JS PQC library (such as
`@noble/post-quantum`) for the browser tools, which currently depend on
nothing but a QR CDN script. Readers that don't recognise keys 32–36 ignore
them per §3's forward-compatibility rule and treat the code as unsigned.

**Why post-quantum, not ECDSA/Ed25519?** Shor's algorithm breaks the
discrete-log and elliptic-curve problems outright — a future quantum
computer doesn't just weaken ECDSA/Ed25519, it forges signatures under those
schemes entirely. By contrast, Grover's algorithm only *halves* AES's
effective key length, which is why AES-256-GCM (§9) needs no change for
quantum resistance. A signature scheme adopted now should not be one that a
sufficiently large quantum computer invalidates retroactively for every code
ever signed with it. ML-DSA-44 (Dilithium2, NIST security category 2, FIPS
204, standardized 2024) is a lattice-based scheme with no known efficient
quantum attack, and its sizes are **fixed regardless of message length** —
2420-byte signature, 1312-byte public key, 2560-byte private key (never
transmitted). For small codes (a few hundred bytes) that's significant
overhead; for content already spanning multiple chunks (§4.2) — e.g. an
essay of a few KB — a constant ~2.4 KB signature is proportionally minor,
and the public key (§ below) is amortized across an entire trail or
collection.

**Signed message:** `SHA-256(envelope || payload)` where `payload` is the
CBOR map with keys 32–36 *absent* — i.e. the SHA-256 of the exact bytes an
unsigned code would encode to. For a Paper Manifest (type 3), `root_hash`
(key 2) is computed first per §4.5 (also with keys 32–36 absent), and the
signature is then computed over the payload map *including* that final
`root_hash` but still excluding keys 32–36. In both cases, signing happens
last and feeds back into nothing — `cache_id`/`root_hash`/`sha256` are
identical whether or not keys 32–36 are subsequently added.

**Verification:** a verifier strips keys 32–36, recomputes the same
SHA-256, and checks `signature` (key 33) against that hash using
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
in any order" pattern as chunk assembly (§5) and key matching (§9).

**Trust model:** trust-on-first-use (TOFU), like SSH host keys — there is no
PKI, certificate authority, or revocation. A verified signature proves "the
same private key signed this as everything else cached under this
`signer_id`," not a real-world identity. `signer_label` (key 36, free text)
lets an author attach a human-readable name (e.g. "City Parks Dept.
Trail"); it is self-asserted and meaningful only as a consistent label
across that signer's codes, exactly like a comment in `~/.ssh/known_hosts`.

**Downgrade:** stripping keys 32–36 from a signed code yields a valid
unsigned code with the same `cache_id`/`sha256` — content-addressing doesn't
distinguish "never signed" from "signature removed." This is an accepted
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
payload map {
  2: h'<8 random bytes>',         // cache_id
  4: "text/markdown",
  5: h'<content bytes>',
  32: 1,                           // signature_algorithm: ML-DSA-44
  33: h'<2420-byte signature>',
  34: h'<1312-byte public key>',   // only on first code from this signer
  35: h'<8-byte signer_id>',
  36: "Alice's Trail",              // optional human-readable label
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

The CBOR sequence (`version`, `type`, `payload` — §2; the same bytes that get Base41-encoded into the `tagdrop:` URI) can be stored directly in an NFC NDEF record with:

- **TNF:** `0x02` (MIME Media type)
- **Type:** `application/vnd.tagdrop`
- **Payload:** the raw CBOR sequence bytes (no Base41 encoding needed for NFC binary storage)

Because `version` and `type` are carried in the payload bytes themselves (§2), one permanent MIME type covers every TagDrop format version — no per-version MIME subtypes needed, and the `tagdrop:<base41>` and raw-NDEF decoders share the same CBOR-sequence parsing, differing only in the Base41 step.

This lets the same physical sticker carry both a QR code (for camera scanning) and an NFC tag (for tap-to-read), with identical content. Android dispatches `application/vnd.tagdrop` NDEF records to the TagDrop app via intent filter.

A NFC-NDEF capable multi-tag sequence would use the same manifest/chunk split, where each NFC tag holds one chunk's CBOR sequence. (NFC Type 2 tags at 1 KB are suitable for single-code payloads; 8 KB tags can hold larger manifests or multi-chunk sequences.)

---

## 13. Alternative Carriers

The format is carrier-agnostic. Any medium that can carry a UTF-8 string supports the `tagdrop:` URI form. Any medium that carries raw bytes supports the raw CBOR sequence form.

| Carrier | Form | Notes |
|---|---|---|
| QR code | `tagdrop:` URI, alphanumeric mode; or raw CBOR sequence, byte mode | Primary target. Byte mode avoids Base41 overhead — denser, but not human-typable; best for Chunks, which are always camera-scanned |
| Aztec code | `tagdrop:` URI | Higher density than QR at small sizes |
| Data Matrix | `tagdrop:` URI | Better damage resistance |
| JABCode (color) | `tagdrop:` URI or raw CBOR sequence | ~4× capacity of QR; see [jabcode/jabcode](https://github.com/jabcode/jabcode) |
| NFC NDEF tag | Raw CBOR sequence, MIME type | No Base41 overhead; supports tapping |
| Plain URL | `tagdrop:` as deep-link | QR of a URL that deep-links to app |

---

## 14. Version Negotiation

`version` is the first item of the envelope sequence (§2) — a single CBOR integer, decodable independently of everything that follows it. A reader encountering an unsupported `version` should stop immediately and show a human-readable "unsupported format version" message, without attempting to decode `type` or `payload` — a future version is free to redefine either, even to something other than CBOR.

Version history:

**Version 1** (initial release, current)

- `version`/`type` envelope as a 2-item CBOR sequence prefix (§2); `type` 0–3 for Single / Manifest / Chunk / PaperManifest.
- Payload map integer keys 2–19, 20–24, 26–27. Key 1 retired (formerly `version` inside the payload — now lives in the envelope). Key 25 reserved for a future binary image icon.
- Base41 URI encoding: `tagdrop:<base41>`. DEFLATE compression (key 12). Content-addressed IDs via SHA-256 (§4.5).
- Paper manifests (type 3) with file directories, `set`/`slug` navigation, and `related` paper hints with optional `lat`/`lng` placeholder coordinates (keys 26/27). TagDropNet relative-link and `tagdrop://` navigation (§7).
- Ad-hoc collections via `collection_id`/`collection_label`/`collection_tag` (keys 17–19). Emoji `icon` (key 24).
- AES-256-GCM hidden override maps (§9): self-contained `nonce||ciphertext||tag` blob carried as trailing bytes (Single) or assembled-chunk bytes (Manifest), applied after compression. Optional non-binding `encryption` hint (key 28). Key 29 reserved, unused. `key_material`/`retain_key` (keys 30/31) matched by trial decryption ("discovery, not declaration"). PBKDF2-HMAC-SHA256 passphrase derivation via `kdf_alg`/`kdf_salt`/`kdf_iters` (keys 37–39).
- ML-DSA-44 post-quantum signatures (§10): `signature_algorithm`/`signature`/`signer_pubkey`/`signer_id`/`signer_label` (keys 32–36), additive and not affecting `cache_id`/`root_hash`/`sha256`. Specified for forward-compatibility; not yet implemented in reference implementations.

---

## 15. Reference Implementations

- **Android app:** `app/src/main/java/com/github/mofosyne/tagdrop/data/format/`
  - `TagDropCodec.kt` — encode/decode all payload types; `contentId()`, `rootHashOf()`, `createSingle()`
  - `Base41.kt` — TagDrop's own alphabet, packed like RFC 9285 Base45 (§2)
  - `MiniCbor.kt` — minimal CBOR encoder/decoder; supports arrays (major 4), nested maps, float64 (major 7), and top-level CBOR sequences (RFC 8742) for the version/type envelope
  - `ChunkAssembler.kt` — multi-code assembly with SHA-256 verification
  - `TagDropLinkResolver.kt` — resolves `tagdrop://<rootHash>/<slug>` navigation links; also locates the `style.css` sibling for `text/markdown` content (§7)
  - `MarkdownRenderer.kt` — renders `text/markdown` content to HTML (§7) via CommonMark

- **Android database:** `app/src/main/java/com/github/mofosyne/tagdrop/data/db/`
  - `FoundCache.kt` — Room entity for scanned file caches
  - `ScannedPaper.kt` — Room entity for scanned paper manifests
  - `AppDatabase.kt` — Room database with migrations

---

## 16. Design Notes and Alternatives Considered

**Why not extend `data:` URI syntax?** (issues #2, #4, #13) Adding parameters like `;seq-id=`, `;seq-total=`, `;crc=` to the data URI was the original approach. It fails because data: URIs are opaque to QR readers — there's no way to route them to the app by scheme. The `tagdrop:` scheme gives us OS-level routing and a clean separation between the envelope and payload.

**Why a version/type envelope instead of URI path segments or per-map keys?** An earlier draft put `v1/<type>/` in the URI path and a `version` key inside each payload map. That works for QR, but raw-byte carriers (NFC NDEF, JABCode raw — §12/§13) have no URI wrapper, so type/version information would either be lost or have to be guessed from which map keys happen to be present — fragile, and ambiguous for future payload types. Prefixing every payload with a 2-item CBOR Sequence (RFC 8742) — `CBOR(version) || CBOR(type)`, 1 byte each for the foreseeable range of values — makes the same bytes self-describing on every carrier: Base41-encode them for `tagdrop:<base41>`, or store them raw in an NDEF record, with identical decode logic either way. It also lets the URI collapse to `tagdrop:<base41>` (no `//`, no `/<type>/` segment), gives a clean disambiguation rule against `tagdrop://<rootHash>/<slug>` navigation links (§2), and — being a sequence rather than a CBOR array — costs one less byte than `[version, type, payload]` and doesn't require `payload` to be CBOR-wrapped, leaving room for a non-CBOR `payload` in a future version. `version` lives *only* in the envelope, not redundantly inside `payload` too: two fields claiming to describe the same fact can disagree, forcing a reader to pick which one to trust — the same class of ambiguity RFC 9112 §6.3 closes off by forbidding conflicting `Content-Length`/`Transfer-Encoding` framing in HTTP/1.1. CBOR's own self-describing-data convention (the tag-55799 "magic number", RFC 8949 §3.4.5.3) is likewise an external prefix rather than a duplicated internal field, reinforcing that self-description belongs in the envelope.

**Why not NDEF as the primary format?** (issue #16) NDEF is a memory-layout format for NFC chips with a specific capability container. Adapting it for QR codes adds complexity without benefit — the QR code already handles error correction and binary framing. We use NDEF only as a transport option for NFC tags (§12).

**Binary mode vs alphanumeric Base41:** Raw binary QR codes store 8 bits/char. Alphanumeric Base41 stores 2 bytes in 3 characters at 5.5 bits/char = ~8.25 bits/byte of original data. The tiny efficiency loss is worth the interoperability gain for most codes: alphanumeric QR codes are more reliably decoded by all readers, and the `tagdrop:` prefix is human-readable/typable. Chunks are the exception — they're always camera-scanned, never hand-typed or shared as text — so the Android reference reader also accepts a QR byte-mode segment carrying the raw CBOR sequence directly (no `tagdrop:`/Base41 wrapper), per §13. The web generator (`tools/generator/index.html`) splits content too large for one QR into a Manifest + Chunks and, by default, renders Chunk QR codes in binary mode (toggleable back to alphanumeric `tagdrop:` URIs); the Manifest QR is always alphanumeric, since it must stay human-typable/shareable like a Single. The Android app's only Manifest+Chunks generator, `ShareQrActivity` (re-sharing an already-cached item too large for one QR), still emits alphanumeric `tagdrop:` URIs for every Chunk.

**Compression:** DEFLATE was chosen over LZMA (issue #2) because it is available in every Java/Android standard library (`java.util.zip`), requiring no dependency. LZMA achieves better ratios for larger payloads but is a future extension (compression value 2).

**Structured append in QR spec:** QR's built-in structured append (up to 16 codes) is not portable across barcode formats and is poorly supported by many readers. Our manifest/chunk approach works with any 2D barcode type and supports up to 2^32 chunks.

**No explicit folder hierarchy:** Paper manifests list files as flat slug strings. Slugs that contain `/` (e.g. `images/photo.jpg`) create virtual path conventions without requiring a tree structure in the CBOR or the database. String equality on the full slug is the only lookup operation needed. This keeps the manifest format simple and avoids directory-traversal edge cases.

**Non-HTML content types (images, audio, MIDI):** The cache stores raw bytes for any MIME type, and the Android WebView can serve them all via `WebViewClient.shouldInterceptRequest`. When a loaded HTML page contains `<img src="tagdrop://...">`, `<audio src="tagdrop://...">`, or any other subresource reference, `shouldInterceptRequest` intercepts the fetch, looks up the slug in the local DB, and returns a `WebResourceResponse` with the cached bytes — no network involved. MIDI files require a JS player library embedded in the same HTML file (the MIDI bytes are served as `audio/midi` via the same mechanism). Purely binary payloads (a standalone image, a MIDI file) are displayed/played by wrapping them in a minimal HTML page that references the tagdrop:// URI. The navigation flow (`shouldOverrideUrlLoading`) and the subresource flow (`shouldInterceptRequest`) are independent: the former fires when the user clicks a link and loads a new top-level page; the latter fires for every embedded asset on the current page. Both resolve through the same `TagDropLinkResolver`.
