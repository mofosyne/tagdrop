# QDEF — Quick Data Exchange Format (Draft Proposal)

**Status: Draft / exploratory. Not part of tagdrop's own wire format.**

This document is **not** SPEC.md. It's a separate, independent proposal for a
general-purpose *binary* multi-action barcode/NFC container — the kind of
thing NDEF already is for radio taps, but aimed at optical (QR/Data
Matrix/Aztec) byte-mode payloads, which have no equivalent today. TagDrop's
own `tagdrop:<base41-cbor-sequence>` URI (SPEC.md §2) is untouched by this —
that stays the ASCII/alphanumeric-mode, human-typable, URI-safe encoding for
TagDrop's primary QR/NFC path. QDEF targets a different case: **raw
byte-mode** payloads (already used today for TagDrop's non-initial sectors
and NFC NDEF records, per SPEC.md §12), where there's no URI/typability
requirement and multiple unrelated actions might want to share one code.

If this gains no traction outside this repo, it's still useful as a written
target for "what would TagDrop's byte-mode path plug into, if a wider binary
standard existed" — see §5.

## 1. Abstract & Philosophy

QDEF is a binary-first data exchange format for 2D barcodes and NFC tags. It
replaces rigid text schemas (`WIFI:S:...;;`, `BEGIN:VCARD`) with a
multi-action, extensible CBOR payload, while staying parseable by both a
modern smartphone and a deeply constrained embedded scanner (transit gate,
POS terminal) with no semantic-tag-aware CBOR library.

## 2. Container Wire Format

8-bit byte mode only (never alphanumeric — text-safety is explicitly not a
goal; see TagDrop's Base41 for that case instead). A 5-byte magic header for
instant optical stream validation, followed by a CBOR Sequence (RFC 8742) of
Records — a sequence rather than a wrapping CBOR array so a constrained
parser can process each record as it streams in, without buffering the whole
payload first.

```
+-------------------+--------------+----------------------------------+
|   Magic (4 bytes)  | Version (1B) |     CBOR Sequence of Records     |
+-------------------+--------------+----------------------------------+
| 0x51 0x44 0x45 0x46 |    0x01     |  Record, Record, Record, ...     |
|      "QDEF"        |  (Version 1) |                                  |
+-------------------+--------------+----------------------------------+
```

For NFC, the magic+version prefix is redundant (NDEF's own MIME-type field
already identifies the payload) — an NDEF record carrying QDEF content uses
MIME type `application/vnd.qdef` with just the CBOR Sequence of Records as
the payload, no magic bytes. The magic header exists only for the QR/optical
case, where a scanner needs to recognize the byte stream's format before any
higher-level dispatch exists to tell it what it's looking at.

## 3. The Record Architecture

Every Record is a CBOR Map.

### 3.1 Hardware Parity Routing (Key 0)

1. **The Smart Route (Tags):** the Record Map SHOULD be wrapped in a CBOR
   Semantic Tag matching the Record Type ID, for parsers with tag-aware CBOR
   libraries.
2. **The Constrained Route (Key 0):** the Record Map MUST also contain Key
   `0` (uint), with the same Record Type ID as the tag. A constrained parser
   with no tag support reads `map[0]` directly and ignores the tag.

Both routes carry the *same* ID — this is redundant dual-declaration for
routing robustness, not a two-level type hierarchy (that's a different
thing NDEF does — TNF category + a separate Type string — which QDEF does
not need, since "Record Type ID" is already the only dispatch key).

### 3.2 The Extensibility Rule (Even/Odd Keys)

Borrowed from PNG's critical/ancillary chunk convention:

- **Even keys are CRITICAL.** An unrecognized even-numbered key MUST cause
  the parser to abort processing *that record* (not the whole stream — other
  records in the same Sequence are unaffected). Key `0` is even, and is
  always critical.
- **Odd keys are OPTIONAL.** An unrecognized odd-numbered key MUST be
  silently ignored; the rest of the record still processes normally.

This gives per-field forward compatibility: a future critical field doesn't
require bumping the container `Version` byte, only choosing an even key
number the current Record Type doesn't yet define.

## 4. Record Type Registry (informative examples)

### Type `100`: Wi-Fi Provisioning

```
Tag 100: {
  0: 100,               // CRITICAL: Record Type ID
  2: "My Coffee Shop",  // CRITICAL: SSID
  4: "guest123",        // CRITICAL: Password
  6: 2,                 // CRITICAL: Auth Type (0=Open, 1=WEP, 2=WPA2/3)
  1: true                // OPTIONAL: Hidden Network Flag
}
```

### Type `105`: Universal Transit / Event Ticket

```
Tag 105: {
  0: 105,                // CRITICAL: Record Type ID
  2: h'A7F90B...',       // CRITICAL: Ticket Hash/Token
  4: 1735689600,         // CRITICAL: Expiry Epoch Timestamp
  1: "General Admit",    // OPTIONAL: UI Display Text
  3: "Gate A"            // OPTIONAL: Wayfinding Hint
}
```

## 5. Registering TagDrop as a Record Type

TagDrop's byte-mode payload (SPEC.md §2/§12) already *is* the four-item
`version`/`type`/`part_meta`/`sector_bytes` CBOR Sequence used identically
for non-initial QR sectors and for NFC NDEF storage today — no re-encoding
needed, only re-framing as a QDEF Record:

```
Tag 900: {                      // proposed Record Type ID — unallocated,
  0: 900,                       //   placeholder pending a real registry
  2: h'<TagDrop CBOR Sequence>' // CRITICAL: raw bytes, unchanged from
                                 //   SPEC.md §2 — version/type/part_meta/
                                 //   sector_bytes, exactly as decoded today
}
```

This means a QDEF-aware scanner could dispatch a single byte-mode QR or NFC
tag containing, say, a Wi-Fi record *and* a TagDrop content record together
— useful for a "join the local mesh AND grab this cached page" sticker —
without TagDrop's own decoder changing at all: it still just reads the raw
CBOR Sequence out of key `2`, the same bytes SPEC.md already defines.

This is additive and speculative — nothing in SPEC.md or either codec
implementation depends on it, and it doesn't need to exist for TagDrop to
keep working exactly as it does today via its own `tagdrop:` URI and raw-CBOR
NFC path.

## 6. Open questions (not resolved by this draft)

- **Registry governance.** Who allocates Record Type IDs (100, 105, 900,
  ...) if this is meant to be shared across unrelated projects? No registry
  exists yet — IDs above are illustrative placeholders only.
- **Magic-header overhead for QR.** 5 bytes fixed cost matters for a
  single-record payload in a size-constrained QR version; is it worth
  gating on payload size (e.g. omit magic when embedded via a scheme that
  already identifies the format, mirroring the NFC case in §2)?
- **Relationship to existing standards.** NDEF already solves "multiple
  typed records, one message" for NFC (§2's `application/vnd.qdef` MIME
  framing leans on this directly). This draft's actual net-new contribution
  is narrower than it first appears: a *magic-header-plus-CBOR-Sequence*
  convention for the optical/QR case specifically, plus the even/odd
  criticality rule, which NDEF itself does not have (NDEF has no
  per-key criticality signal at all, only per-record TNF/Type).
