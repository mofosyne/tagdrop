# TagDrop Encoding Specification

**Version:** 1 (draft)  
**Status:** Working draft — feedback welcome via GitHub issues

---

## 1. Overview

TagDrop encodes self-contained digital content into one or more 2D barcodes (QR, Aztec, Data Matrix) for physical placement — on walls, along trails, inside objects. A finder scans the code(s) with the TagDrop app (or any QR reader that understands the URI scheme) to retrieve the content.

The format is designed to:

- Fit rich content (HTML pages, images, audio snippets, plain text) into one or more standard QR codes
- Support **geographic distribution**: chunks of a large payload can be placed at different physical locations, so finding all pieces is part of the experience
- Be **future-proof**: versioned, binary-compact, with reserved fields for compression and future extensions
- Remain **backward-compatible** with the legacy `data:` URI approach
- Be **transport-agnostic**: the same CBOR payload can be carried by QR codes, NFC NDEF tags, or other 2D barcode formats (Aztec, JABCode)

---

## 2. URI Scheme

All TagDrop codes use the URI scheme `tagdrop://` so any QR scanner routes them to the app. The URI doubles as an intent deep-link on Android.

```
tagdrop://v1/<type>/<base45-cbor>
```

| Segment | Values | Meaning |
|---|---|---|
| `v1` | `v1` | Format version 1 |
| `<type>` | `s` | Single-code cache (complete payload) |
| | `m` | Manifest (header for a multi-code cache) |
| | `c` | Chunk (one geographic fragment) |
| | `p` | Paper manifest (directory for a physical paper) |
| `<base45-cbor>` | alphanumeric | RFC 9285 Base45-encoded CBOR map |

### Navigation links (not QR payloads)

HTML pages embedded in TagDrop caches can link to other files and papers using:

```
tagdrop://<rootHash-base45>/<slug>
```

`rootHash` is the 8-byte SHA-256 of the paper manifest CBOR, Base45-encoded (12 characters). `slug` is the file's identifier within that paper. The TagDrop app intercepts these links in its WebView and resolves them against the local scanned-paper database — no network needed.

**Disambiguation:** the encoding URI paths all begin `v1/` (lowercase `v`). The Base45 alphabet contains only uppercase letters and digits. So `v1/` can **never** be the start of a valid Base45-encoded root hash. Encoding URIs and navigation links are unambiguously distinguishable by the first two characters after `tagdrop://`.

**Why Base45?** QR codes have an alphanumeric mode (charset 0–9, A–Z, space, `$%*+-./:`) that stores 5.5 bits per character vs 8 bits per character in binary mode. Base45 encodes 2 bytes → 3 alphanumeric characters, giving ~3% overhead — far better than Base64 (33% overhead in binary mode).

**Why CBOR?** CBOR (RFC 8949) is binary JSON: self-describing, compact, standardised, and easy to parse without a schema. It is 20–50% smaller than JSON for typical payloads. Integer map keys (used here) are 1 byte each.

---

## 3. CBOR Map Keys

All payloads are CBOR maps with **integer keys**. Unknown keys must be ignored (forward compatibility).

| Key | Field | Type | Used in |
|---|---|---|---|
| 1 | `version` | uint | S, M, P |
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

**S** = Single, **M** = Manifest, **C** = Chunk, **P** = PaperManifest

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

---

## 4. Payload Types

### 4.1 Single-Code Cache (`tagdrop://v1/s/…`)

The entire payload fits in one QR code. Recommended for content ≤ ~800 bytes decoded.

```
CBOR map {
  1: 1,                   // version
  2: h'<8 random bytes>', // cache_id — unique identifier
  3: "under the bridge",  // hint — optional, for the finder
  4: "text/html",         // mime_type
  5: h'<content bytes>',  // content — raw or compressed
  11: "poem.html",        // filename — optional
  12: 1,                  // compression — omit if none (0)
}
```

`content` is the raw payload bytes (after any decompression). If `compression` is present and non-zero, decompress before use.

### 4.2 Manifest (`tagdrop://v1/m/…`)

The manifest QR is placed at the **start** of a multi-code cache (or can be a separate marker — see §6). It announces how many chunks exist, their total size, and a SHA-256 hash for integrity verification.

```
CBOR map {
  1: 1,
  2: h'<8 random bytes>',   // cache_id — same across all codes in this set
  3: "location hint",
  4: "text/html",
  11: "story.html",
  12: 1,                    // compression applied to assembled chunks
  6: 4,                     // chunk_count: 4 codes to collect
  7: 3200,                  // total_bytes: assembled (pre-decompression if compressed)
  8: h'<32-byte sha256>',   // hash of assembled raw chunk data (before decompression)
}
```

### 4.3 Chunk (`tagdrop://v1/c/…`)

Each chunk carries a slice of the raw payload. Chunks are **order-independent** — the app collects them in any order and sorts by `chunk_index` before assembly.

```
CBOR map {
  2: h'<same cache_id>',    // links this chunk to its manifest
  9: 2,                     // chunk_index: 0-based
  10: h'<chunk bytes>',
}
```

Chunks intentionally omit version, hint, mime, and filename to minimise size.

### 4.4 Paper Manifest (`tagdrop://v1/p/…`)

A paper manifest is the **directory QR code** for a physical paper (A4 sheet, sticker board, poster). Think of it as a floppy disk's FAT: it lists every file on the paper and can point toward related papers at other locations.

```
CBOR map {
  1: 1,                             // version
  2: h'<8-byte root hash>',         // SHA-256(this CBOR)[0:8] — paper's permanent address
  3: "Trail Stop 3 — Oak Tree",     // label — human-readable paper name (optional)
  13: "sunset-trail",               // set — which network/trail (optional)
  14: "oak-tree",                   // slug — this paper's address within the set (optional)
  15: [                             // files — directory of QR codes on this paper
    {20: "index",   21: "text/html",  22: h'<file_id>'},
    {20: "map",     21: "image/svg+xml", 22: h'<file_id>'},
  ],
  16: [                             // related — hints to other papers
    {3: "Next stop: the red letterbox 200m north", 14: "letterbox", 23: h'<paper_id>'},
    {3: "Start of trail: town square notice board"},
  ]
}
```

`root_hash` (key 2) is computed externally after the rest of the CBOR is finalised — see §4.5.

**Navigation:** HTML files on the paper can link to other files using:
```html
<a href="tagdrop://<paper-root-hash-base45>/map">See the map</a>
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
root_hash = SHA-256(paper manifest CBOR bytes)[0:8]
```
A paper's root hash is computed from its manifest CBOR, which includes the root hash field itself (key 2). This is the same chicken-and-egg situation IPFS solves the same way: encode the manifest *without* the hash, compute the hash, insert it, then re-encode. The root hash is the paper's permanent, immutable address. Because paper is inherently immutable (you can't update a sticker), this is fine — a new revision gets a new root hash.

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
5. Decompress if `compression != 0`.
6. Deliver MIME-typed content to the viewer.

**Resumability (issue #14):** Because assembly only needs the CBOR map stored in each QR, a partially-collected set can be saved to a database and resumed later. Each chunk carries `cache_id` so the app can match newly-scanned chunks to a pending collection.

**Geographic distribution:** Each chunk is an independent, self-contained QR code. Chunks can be placed at geographically separate locations (along a trail, in different rooms, across a city). The finder accumulates them over time. The hint in the manifest can describe the treasure hunt.

---

## 6. Placing Codes in the Field

### Recommended layouts

**Single-code cache (simple drop):**
```
[ tagdrop://v1/s/<base45> ]
```
One sticker, one QR. Scan and done.

**Multi-code cache (trail):**
```
Location A: [ Manifest: tagdrop://v1/m/<base45> ]   ← start here
Location B: [ Chunk 0:  tagdrop://v1/c/<base45> ]
Location C: [ Chunk 1:  tagdrop://v1/c/<base45> ]
Location D: [ Chunk 2:  tagdrop://v1/c/<base45> ]
```

Or the manifest can be omitted from the field and provided out-of-band (e.g. posted online, given at registration). In that case all codes in the field are chunks — the app will queue them and complete assembly once the manifest is provided.

**Chunk size recommendation:** Target ~600 bytes per chunk (decoded), which encodes to ~900 Base45 characters and fits in a QR Version 15 (M error correction) that prints cleanly at 3cm × 3cm.

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
tagdrop://<rootHash-base45>/<slug>
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
  `https://paper.tagdrop.invalid/<rootHash-hex>/<slug>` as the **base URL**
  (via `loadDataWithBaseURL`) instead of as an opaque `data:` URI. `.invalid`
  is an IANA-reserved TLD (RFC 2606) that never resolves over the network.
- Ordinary relative URLs in the HTML/CSS resolve against that base using
  standard URL resolution, producing more `https://paper.tagdrop.invalid/...`
  URLs. The app's `WebViewClient` recognises this host (alongside the
  `tagdrop://` scheme) and resolves the resulting `<rootHash-hex>/<slug>` pair
  through `TagDropLinkResolver`, exactly like a `tagdrop://<rootHash>/<slug>`
  link.

To reference a file on a **different** paper (a different root hash), use an
explicit `tagdrop://<rootHash-base45>/<slug>` link — this can't be relative,
since it's a different content-addressed directory.

Practical effect: a normal static-site folder (HTML + CSS + images with
relative links) can be zipped, fed to the generator, and turned into a set of
QR codes where the relative links keep working once scanned into the app —
no rewriting of the authored HTML required.

### Sets and slugs

Papers can belong to named **sets** (trails, networks, exhibitions). Within a set, each paper has a unique `slug`. This enables relative addressing:

```
A paper with set="sunset-trail", slug="oak-tree"
links to another paper with slug="letterbox" in the same set.
```

The full navigation URI for the letterbox paper's index file would be:
```
tagdrop://<letterbox-paper-root-hash-base45>/index
```

Root hashes are permanent because paper is immutable. If a paper is updated, it gets a new hash — the old one continues to work as long as the old paper exists physically.

## 8. Compression

| `compression` value | Algorithm |
|---|---|
| 0 (or absent) | None |
| 1 | DEFLATE (RFC 1951, raw, no zlib header) |
| 2–255 | Reserved |

Compression is applied to the complete assembled payload (in multi-code) or the content field directly (in single-code). The `sha256` in the manifest is over the **compressed** assembled bytes (before decompression), so integrity can be verified before decompression.

DEFLATE typically achieves 50–70% size reduction on HTML and text, effectively doubling QR capacity for textual content.

---

## 9. Backward Compatibility: Legacy `data:` URIs

Codes containing a raw `data:` URI (without the `tagdrop://` scheme) are recognised and handled in **legacy mode**:

- A single code: the data URI is opened directly in the WebView viewer.
- Multiple codes: fragments are **dumb-appended** in scan order (the original V1 behaviour). The assembled string is interpreted as a `data:` URI.

New content should use the `tagdrop://` scheme. Legacy support will be maintained indefinitely.

---

## 10. NFC Transport (future)

The CBOR payload (without the `tagdrop://v1/s/` prefix) can be stored directly in an NFC NDEF record with:

- **TNF:** `0x02` (MIME Media type)
- **Type:** `application/vnd.tagdrop`
- **Payload:** the raw CBOR bytes (no Base45 encoding needed for NFC binary storage)

This lets the same physical sticker carry both a QR code (for camera scanning) and an NFC tag (for tap-to-read), with identical content. Android dispatches `application/vnd.tagdrop` NDEF records to the TagDrop app via intent filter.

A NFC-NDEF capable multi-tag sequence would use the same manifest/chunk split, where each NFC tag holds one chunk CBOR record. (NFC Type 2 tags at 1 KB are suitable for single-code payloads; 8 KB tags can hold larger manifests or multi-chunk sequences.)

---

## 11. Alternative Carriers

The format is carrier-agnostic. Any medium that can carry a UTF-8 string supports the `tagdrop://` URI form. Any medium that carries raw bytes supports the raw CBOR form.

| Carrier | Form | Notes |
|---|---|---|
| QR code | `tagdrop://` URI, alphanumeric mode | Primary target |
| Aztec code | `tagdrop://` URI | Higher density than QR at small sizes |
| Data Matrix | `tagdrop://` URI | Better damage resistance |
| JABCode (color) | `tagdrop://` URI or raw CBOR | ~4× capacity of QR; see [jabcode/jabcode](https://github.com/jabcode/jabcode) |
| NFC NDEF tag | Raw CBOR, MIME type | No Base45 overhead; supports tapping |
| Plain URL | `tagdrop://` as deep-link | QR of a URL that deep-links to app |

---

## 12. Version Negotiation

The `version` field (key 1) is present in Single, Manifest, and PaperManifest payloads (not Chunks, which inherit from their Manifest). A reader encountering an unknown version should display a human-readable error rather than silently failing.

Version history:
| Version | Changes |
|---|---|
| 1 | Initial release. Keys 1–16, 20–23. Single/Manifest/Chunk/PaperManifest types. DEFLATE compression. Base45 URI encoding. Content-addressed IDs. |

---

## 13. Reference Implementations

- **Android app:** `app/src/main/java/com/github/mofosyne/tagdrop/data/format/`
  - `TagDropCodec.kt` — encode/decode all payload types; `contentId()`, `rootHashOf()`, `createSingle()`
  - `Base45.kt` — RFC 9285
  - `MiniCbor.kt` — minimal CBOR encoder/decoder; supports arrays (major 4) and nested maps
  - `ChunkAssembler.kt` — multi-code assembly with SHA-256 verification
  - `TagDropLinkResolver.kt` — resolves `tagdrop://<rootHash>/<slug>` navigation links

- **Android database:** `app/src/main/java/com/github/mofosyne/tagdrop/data/db/`
  - `FoundCache.kt` — Room entity for scanned file caches
  - `ScannedPaper.kt` — Room entity for scanned paper manifests
  - `AppDatabase.kt` — Room DB v2 with migration

---

## 14. Design Notes and Alternatives Considered

**Why not extend `data:` URI syntax?** (issues #2, #4, #13) Adding parameters like `;seq-id=`, `;seq-total=`, `;crc=` to the data URI was the original approach. It fails because data: URIs are opaque to QR readers — there's no way to route them to the app by scheme. The `tagdrop://` scheme gives us OS-level routing and a clean separation between the envelope and payload.

**Why not NDEF as the primary format?** (issue #16) NDEF is a memory-layout format for NFC chips with a specific capability container. Adapting it for QR codes adds complexity without benefit — the QR code already handles error correction and binary framing. We use NDEF only as a transport option for NFC tags (§10).

**Binary mode vs alphanumeric Base45:** Raw binary QR codes store 8 bits/char. Alphanumeric Base45 stores 2 bytes in 3 characters at 5.5 bits/char = ~8.25 bits/byte of original data. The tiny efficiency loss is worth the interoperability gain: alphanumeric QR codes are more reliably decoded by all readers, and the `tagdrop://` prefix is human-readable.

**Compression:** DEFLATE was chosen over LZMA (issue #2) because it is available in every Java/Android standard library (`java.util.zip`), requiring no dependency. LZMA achieves better ratios for larger payloads but is a future extension (compression value 2).

**Structured append in QR spec:** QR's built-in structured append (up to 16 codes) is not portable across barcode formats and is poorly supported by many readers. Our manifest/chunk approach works with any 2D barcode type and supports up to 2^32 chunks.

**No explicit folder hierarchy:** Paper manifests list files as flat slug strings. Slugs that contain `/` (e.g. `images/photo.jpg`) create virtual path conventions without requiring a tree structure in the CBOR or the database. String equality on the full slug is the only lookup operation needed. This keeps the manifest format simple and avoids directory-traversal edge cases.

**Non-HTML content types (images, audio, MIDI):** The cache stores raw bytes for any MIME type, and the Android WebView can serve them all via `WebViewClient.shouldInterceptRequest`. When a loaded HTML page contains `<img src="tagdrop://...">`, `<audio src="tagdrop://...">`, or any other subresource reference, `shouldInterceptRequest` intercepts the fetch, looks up the slug in the local DB, and returns a `WebResourceResponse` with the cached bytes — no network involved. MIDI files require a JS player library embedded in the same HTML file (the MIDI bytes are served as `audio/midi` via the same mechanism). Purely binary payloads (a standalone image, a MIDI file) are displayed/played by wrapping them in a minimal HTML page that references the tagdrop:// URI. The navigation flow (`shouldOverrideUrlLoading`) and the subresource flow (`shouldInterceptRequest`) are independent: the former fires when the user clicks a link and loads a new top-level page; the latter fires for every embedded asset on the current page. Both resolve through the same `TagDropLinkResolver`.
