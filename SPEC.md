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
| `<base45-cbor>` | alphanumeric | RFC 9285 Base45-encoded CBOR map |

**Why Base45?** QR codes have an alphanumeric mode (charset 0–9, A–Z, space, `$%*+-./:`) that stores 5.5 bits per character vs 8 bits per character in binary mode. Base45 encodes 2 bytes → 3 alphanumeric characters, giving ~3% overhead — far better than Base64 (33% overhead in binary mode).

**Why CBOR?** CBOR (RFC 8949) is binary JSON: self-describing, compact, standardised, and easy to parse without a schema. It is 20–50% smaller than JSON for typical payloads. Integer map keys (used here) are 1 byte each.

---

## 3. CBOR Map Keys

All payloads are CBOR maps with **integer keys**. Unknown keys must be ignored (forward compatibility).

| Key | Field | Type | Used in |
|---|---|---|---|
| 1 | `version` | uint | S, M |
| 2 | `cache_id` | bytes (8) | S, M, C |
| 3 | `hint` | text (opt) | S, M |
| 4 | `mime_type` | text | S, M |
| 5 | `content` | bytes | S |
| 6 | `chunk_count` | uint | M |
| 7 | `total_bytes` | uint | M |
| 8 | `sha256` | bytes (32) | M |
| 9 | `chunk_index` | uint | C |
| 10 | `chunk_data` | bytes | C |
| 11 | `filename` | text (opt) | S, M |
| 12 | `compression` | uint (opt) | S, M |

**S** = Single, **M** = Manifest, **C** = Chunk

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

## 7. Compression

| `compression` value | Algorithm |
|---|---|
| 0 (or absent) | None |
| 1 | DEFLATE (RFC 1951, raw, no zlib header) |
| 2–255 | Reserved |

Compression is applied to the complete assembled payload (in multi-code) or the content field directly (in single-code). The `sha256` in the manifest is over the **compressed** assembled bytes (before decompression), so integrity can be verified before decompression.

DEFLATE typically achieves 50–70% size reduction on HTML and text, effectively doubling QR capacity for textual content.

---

## 8. Backward Compatibility: Legacy `data:` URIs

Codes containing a raw `data:` URI (without the `tagdrop://` scheme) are recognised and handled in **legacy mode**:

- A single code: the data URI is opened directly in the WebView viewer.
- Multiple codes: fragments are **dumb-appended** in scan order (the original V1 behaviour). The assembled string is interpreted as a `data:` URI.

New content should use the `tagdrop://` scheme. Legacy support will be maintained indefinitely.

---

## 9. NFC Transport (future)

The CBOR payload (without the `tagdrop://v1/s/` prefix) can be stored directly in an NFC NDEF record with:

- **TNF:** `0x02` (MIME Media type)
- **Type:** `application/vnd.tagdrop`
- **Payload:** the raw CBOR bytes (no Base45 encoding needed for NFC binary storage)

This lets the same physical sticker carry both a QR code (for camera scanning) and an NFC tag (for tap-to-read), with identical content. Android dispatches `application/vnd.tagdrop` NDEF records to the TagDrop app via intent filter.

A NFC-NDEF capable multi-tag sequence would use the same manifest/chunk split, where each NFC tag holds one chunk CBOR record. (NFC Type 2 tags at 1 KB are suitable for single-code payloads; 8 KB tags can hold larger manifests or multi-chunk sequences.)

---

## 10. Alternative Carriers

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

## 11. Version Negotiation

The `version` field (key 1) is present in Single and Manifest payloads (not Chunks, which inherit from their Manifest). A reader encountering an unknown version should display a human-readable error rather than silently failing.

Version history:
| Version | Changes |
|---|---|
| 1 | Initial release. Keys 1–12. DEFLATE compression. Base45 URI encoding. |

---

## 12. Reference Implementations

- **Android app:** `app/src/main/java/com/github/mofosyne/tagdrop/data/format/`
  - `TagDropCodec.kt` — encode/decode
  - `Base45.kt` — RFC 9285
  - `MiniCbor.kt` — minimal CBOR encoder/decoder
  - `ChunkAssembler.kt` — multi-code assembly with SHA-256 verification

---

## 13. Design Notes and Alternatives Considered

**Why not extend `data:` URI syntax?** (issues #2, #4, #13) Adding parameters like `;seq-id=`, `;seq-total=`, `;crc=` to the data URI was the original approach. It fails because data: URIs are opaque to QR readers — there's no way to route them to the app by scheme. The `tagdrop://` scheme gives us OS-level routing and a clean separation between the envelope and payload.

**Why not NDEF as the primary format?** (issue #16) NDEF is a memory-layout format for NFC chips with a specific capability container. Adapting it for QR codes adds complexity without benefit — the QR code already handles error correction and binary framing. We use NDEF only as a transport option for NFC tags (§9).

**Binary mode vs alphanumeric Base45:** Raw binary QR codes store 8 bits/char. Alphanumeric Base45 stores 2 bytes in 3 characters at 5.5 bits/char = ~8.25 bits/byte of original data. The tiny efficiency loss is worth the interoperability gain: alphanumeric QR codes are more reliably decoded by all readers, and the `tagdrop://` prefix is human-readable.

**Compression:** DEFLATE was chosen over LZMA (issue #2) because it is available in every Java/Android standard library (`java.util.zip`), requiring no dependency. LZMA achieves better ratios for larger payloads but is a future extension (compression value 2).

**Structured append in QR spec:** QR's built-in structured append (up to 16 codes) is not portable across barcode formats and is poorly supported by many readers. Our manifest/chunk approach works with any 2D barcode type and supports up to 2^32 chunks.
