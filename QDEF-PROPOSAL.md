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

### 3.3 Conformance Levels

QDEF is designed so a minimal, generic parser is genuinely minimal — no
implementer has to bring a compression library or sector-reassembly logic
just to support the *container*:

- **Core QDEF parser (mandatory, all implementers):** verify magic/version,
  walk the CBOR Sequence, read each Record's `map[0]` to route or skip it,
  apply the even/odd rule (§3.2) to unrecognized keys. That's the entire
  surface area — no compression, no multi-code state, no knowledge of any
  specific Record Type's fields.
- **Record-Type-specific handling (optional, per Record Type an implementer
  chooses to support):** everything else — including whether a given
  Record Type's payload happens to be compressed, or happens to require
  reassembling several codes — is defined *by that Record Type*, not by
  QDEF. An implementer who only cares about Wi-Fi provisioning (Type 100)
  never has to read, understand, or link against whatever TagDrop's Type
  900 or any other registered type does internally.

This is deliberate, not incidental: keeping compression and multi-code
splitting entirely out of core conformance (see §6) is what makes "write a
QDEF parser for my own Record Type" a small, self-contained task instead of
requiring every implementer to first solve reassembly and compression
generically. Reference/example code for other implementers should reflect
this split explicitly — a small core router, plus separate, independent
Record Type handlers that each own their own complexity (or lack of it).

### 3.4 Wrapper Records (optional)

A **Wrapper Record** is an ordinary Record — same routing, same even/odd
rule — using a reserved low Type ID, whose payload is not application data
but the *encoded bytes of another Record* (which may itself be a Wrapper
Record, nested). Unwrapping and re-parsing the result as a Record is the
entire mechanism: no new parsing concept beyond "run the Record parser
again on these bytes."

Reserved Wrapper Type IDs (placeholders, pending a real registry):

```
Type 2: {                    // Split
  0: 2,
  2: h'<group_id>',          // CRITICAL: content-addressed (e.g. a hash of
                              //   the full reassembled bytes) — never an
                              //   issued serial, so no coordination needed
                              //   between independent encoders
  4: 1,                      // CRITICAL: this fragment's index
  6: 4,                      // CRITICAL: total fragment count in the group
  8: h'<fragment bytes>'     // CRITICAL: this code's slice
}

Type 3: {                    // Compress (DEFLATE)
  0: 3,
  2: h'<deflate bytes>'      // CRITICAL
}

Type 4: {                    // Encrypt (e.g. AES-GCM)
  0: 4,
  2: h'<nonce>',             // CRITICAL
  4: h'<ciphertext+tag>'     // CRITICAL
}
```

**Fixed nesting order**, when more than one is combined: `Split (outermost,
if present) → Encrypt → Compress → plain inner Record`. Split must be
outermost — decompression/decryption need the complete byte string, which
only exists after reassembly. Compress-before-encrypt is the only sound
order between the other two (ciphertext doesn't compress).

**Why a wrapper, not a reserved key range on the inner record itself** (an
earlier idea for this same problem): wrapping avoids a cross-record
correctness hazard a sibling/key-range approach doesn't. If spanning info
were just extra keys inside, say, a Type 200 "Photo Fragment" record, a
parser that recognizes Type 200 but not the spanning convention would
happily treat one fragment as if it were the whole photo. A Wrapper Record
can't be misread that way: its payload is opaque bytes, not a valid inner
Record, so a parser that doesn't implement Type 2 just skips the entire
record like any other unrecognized Type ID — it never sees anything to
misinterpret. This is also what makes the mechanism *reusable*: reassembly/
decompression/decryption are generic byte-in-byte-out operations, so one
resolver, written once, works for every Record Type that opts in — no
Record Type author writes any of this themselves.

**Cost:** wrapper framing (CBOR map + a few keys) is added per code on top
of the inner record, so this stays strictly opt-in — a Record Type with no
need for it stays a plain, unwrapped Record, exactly as cheap as §4's
examples.

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

## 6. Compression and splitting across multiple tags/codes

**QDEF itself defines neither.** Both stay entirely inside each Record
Type's own payload definition — TagDrop's registration (§5) is the working
example of why that's the right call, not a limitation to design around.

**Why not build them into the container:**

- *Compression:* §3.1's "Constrained Route" only works if a bare-metal
  scanner can read `map[0]` at zero decode cost to decide whether a record
  concerns it. If the CBOR Sequence itself were compressed, that scanner
  would need a DEFLATE implementation just to *skip* a record it doesn't
  recognize — directly against §6 of the manifesto ("No-Snobbery Rule").
  Keeping compression a per-record-type concern — as TagDrop already does,
  DEFLATE happening before sectorization, opaque to any wrapper — means a
  parser that doesn't recognize Type 900 never touches a compressed byte.
- *Splitting:* QDEF is deliberately scoped to one physical code's records
  (§2). Reassembling a payload spread across multiple codes (ordering,
  missing/duplicate sectors, parity, content-addressing instead of an
  issued serial) is a much harder problem than routing, and it's exactly
  what TagDrop's `part_meta` (SPEC.md §4.1) already solves — after multiple
  rounds of hard-won correctness (see CLAUDE.md's notes on this exact class
  of bug in the signing feature). A second, competing addressing scheme at
  the QDEF layer risks two envelopes disagreeing about what "sector 2 of 4"
  means.

**Why this is also the only interop-safe answer for TagDrop specifically:**
compression/splitting can't be relocated into an outer QDEF wrapper without
breaking the ASCII `tagdrop:` URI path, which shares the *identical* CBOR
Sequence and has no QDEF wrapper at all — Base41 encodes it directly
(SPEC.md §2). So this logic has to keep living exactly where it lives
today, inside the CBOR Sequence itself, for both paths to share one
implementation. Under §5's registration, a multi-sector TagDrop payload
becomes **N separate QDEF containers** (or N NDEF messages) — each holding
one Type-900 Record whose value is one already-compressed, already-addressed
TagDrop sector, byte-for-byte unchanged. QDEF performs no reassembly; it's
purely per-code framing.

**Cost of wrapping every sector:** ~5 bytes magic + a few bytes of map/tag
framing (key 0, key 2) *per code*, on top of what SPEC.md §12's raw
byte-mode QR path already gets for free today (that's the reason non-initial
sectors go byte-mode unwrapped in the first place). QDEF framing should
therefore stay **opt-in per code** — used only when a code genuinely needs
to co-locate a TagDrop sector alongside an unrelated record (e.g. a Wi-Fi
record on the same sticker) — not the default framing for ordinary
multi-sector TagDrop content, where the existing unwrapped raw CBOR
sequence remains cheaper and should stay the default.

**If a future *non*-TagDrop record type wants splitting, compression, or
encryption without writing any of it itself:** that's what §3.4's Wrapper
Records are for — a generic, reusable resolver (reassemble / decompress /
decrypt → re-parse as a Record) that any Record Type can opt into by simply
being wrapped, with zero code written by that Record Type's own author.
Not required for TagDrop interop (Type 900 keeps its own proven, signing-
aware `part_meta`, unchanged) — this is purely for record types that don't
already have their own answer.

## 7. Open questions (not resolved by this draft)

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
- **Wrapper Type registry (§3.4).** Same governance gap as ordinary Record
  Types, now also for reserved Wrapper IDs (Split/Compress/Encrypt) — needs
  the same "who allocates numbers" answer above, just for a smaller,
  presumably QDEF-core-maintained range rather than open to any vendor.
- **Enforcing nesting order (§3.4).** Is `Split → Encrypt → Compress` just
  documented convention an encoder is trusted to follow, or should a
  non-conformant order be independently detectable/rejectable by a
  decoder? Leaning toward "trust the encoder" (matches QDEF's minimal-core
  philosophy) but worth deciding explicitly rather than leaving implicit.
