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
standard existed" — see §6.

### When QDEF earns its place (a general rule, not a TagDrop exception)

This isn't specific to TagDrop: **any** application that defines its own
text/URI scheme (human-typable, clickable) should encode its envelope
directly under that scheme, not wrap it in QDEF. The scheme prefix already
does the recognition job QDEF's magic header exists for (§2) — wrapping
would only add redundant bytes with nothing to show for them. QDEF earns
its place on carriers with **no pre-existing dispatch**: plain byte-mode QR
with no URI at all, or an NDEF payload with no app-specific MIME type
already doing the routing. That's true for TagDrop's own byte-mode/NFC path
(§6) and equally true for any other application considering the same
tradeoff (§8's PGP-backup example, for instance) — a self-contained rule,
not something argued case by case each time it comes up.

## 1. Abstract & Philosophy

QDEF is a binary-first data exchange format for 2D barcodes and NFC tags. It
replaces rigid text schemas (`WIFI:S:...;;`, `BEGIN:VCARD`) with a
multi-action, extensible CBOR payload, while staying parseable by both a
modern smartphone and a deeply constrained embedded scanner (transit gate,
POS terminal) with no semantic-tag-aware CBOR library.

QDEF is deliberately two things, not one: a minimal **core format** (§3),
plus a separate **standard library** (§4) of reusable building blocks built
on top of it. Neither is optional to the design — see §4 for why.

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
splitting entirely out of core conformance (see §7) is what makes "write a
QDEF parser for my own Record Type" a small, self-contained task instead of
requiring every implementer to first solve reassembly and compression
generically. Reference/example code for other implementers should reflect
this split explicitly — a small core router, plus separate, independent
Record Type handlers that each own their own complexity (or lack of it).

## 4. The QDEF Standard Library

QDEF is a *format plus a standard library*, not just the format — the same
relationship C-the-language has with libc. §3 defines a minimal core any
conformant parser must implement, and says nothing about compression,
splitting, encryption, or graceful degradation for scanners that don't
understand a given Record Type. Those live here instead: a small, curated
set of Record Types any application can pull in — writing no reassembly
code, no cipher code, no fallback-routing code of its own — the same way
nobody hand-rolls `malloc` just because C-the-language doesn't itself
require libc to exist.

**Reserved Type ID range:** `1`–`99` are reserved for this standard
library, maintained alongside the QDEF spec itself. `100` and above are
open for applications to register their own domain-specific Record Types
(§5's Wi-Fi/Ticket examples, §6's TagDrop registration at `900`) — who
governs *that* allocation is still open (§9), but at least the two
registries are partitioned by construction and can't collide.

### 4.1 Wrapper Records (optional)

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
  8: h'<fragment bytes>',    // CRITICAL: this code's slice
  9: 5821,                   // OPTIONAL: total_bytes of the reassembled
                              //   whole — lets a decoder show progress or
                              //   pre-size a buffer before all fragments
                              //   have arrived
  11: 1                      // OPTIONAL: parity_scheme — 0/absent = none,
                              //   nonzero selects a registered forward-
                              //   error-correction scheme so the group
                              //   tolerates a missing/damaged code (see
                              //   SPEC.md §4.1's `parity_scheme` for the
                              //   design this is modeled on)
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

`total_bytes`/`parity_scheme` are safely odd/OPTIONAL despite sounding like
correctness-critical fields: reassembly only requires fragments `0` through
`count − 1`; a parity fragment (index ≥ `count`, present only when
`parity_scheme` is set) is pure bonus redundancy, useful for reconstructing
one missing/damaged fragment but never required when all `count` real
fragments already arrived. A decoder that doesn't understand
`parity_scheme` can just ignore any fragment past `count` and still
reassemble correctly in the all-present case — it only loses resilience,
never correctness. (Mirrors SPEC.md §4.1: `parity_scheme` sectors sit at
`sector_index ≥ sector_count`, strictly additive to the sectors a basic
decoder already needs.)

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
need for it stays a plain, unwrapped Record, exactly as cheap as §5's
examples.

### 4.2 Fallback Hint (optional)

Unlike §4.1, this is deliberately **not** a wrapper — a plain stdlib Record
Type meant to sit as a *sibling* alongside real content records in the same
CBOR Sequence, carrying a URI any generic tool can follow if it doesn't
understand anything else in the container:

```
Type 5: {                          // Fallback Hint (stdlib)
  0: 5,
  2: "https://example.com/open-this",  // CRITICAL: a URI a generic tool
                                        //   or browser can follow
  1: "Open in TagDrop"                 // OPTIONAL: human-readable label
}
```

This is what actually gives a QDEF container the "something useful happens
even without the specific app" property — the same mechanism behind
SPEC.md's own NDEF companion-record convention (a Well-Known URI/Text/MIME
record placed at index 0 alongside TagDrop's own record, so a non-TagDrop
NFC reader still gets something), formalized here as reusable stdlib
infrastructure instead of an NFC-only, ad hoc convention, so it also
reaches the byte-mode QR case NDEF's own URI record can't.

It **must** stay a plain sibling record, never nested inside a Wrapper —
its entire value is being visible to a parser that understands nothing
else in the container, which a Wrapper's opaque payload would defeat.

## 5. Record Type Registry (informative examples)

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

## 6. Registering TagDrop as a Record Type

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

## 7. Compression and splitting across multiple tags/codes

**QDEF itself defines neither.** Both stay entirely inside each Record
Type's own payload definition — TagDrop's registration (§6) is the working
example of why that's the right call, not a limitation to design around.

**Why not build them into the container:**

- *Compression:* §3.1's "Constrained Route" only works if a bare-metal
  scanner can read `map[0]` at zero decode cost to decide whether a record
  concerns it. If the CBOR Sequence itself were compressed, that scanner
  would need a DEFLATE implementation just to *skip* a record it doesn't
  recognize — directly against the "No-Snobbery Rule" behind Hardware
  Parity (§3.1). Keeping compression a per-record-type concern — as
  TagDrop already does, DEFLATE happening before sectorization, opaque to
  any wrapper — means a parser that doesn't recognize Type 900 never
  touches a compressed byte.
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
implementation. Under §6's registration, a multi-sector TagDrop payload
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
encryption without writing any of it itself:** that's what §4.1's Wrapper
Records are for — a generic, reusable resolver (reassemble / decompress /
decrypt → re-parse as a Record) that any Record Type can opt into by simply
being wrapped, with zero code written by that Record Type's own author.
Not required for TagDrop interop (Type 900 keeps its own proven, signing-
aware `part_meta`, unchanged) — this is purely for record types that don't
already have their own answer.

## 8. Worked example: a non-TagDrop adopter (PGP key backup)

Illustrating §4.1 and §7 for an application that has nothing to do with
TagDrop: an app that backs up a passphrase-protected OpenPGP secret key
across a set of printed QR codes.

This app has **no scheme of its own** to dispatch on — these codes are only
ever scanned by its own app, never clicked or typed — so per "When QDEF
earns its place" above, going through QDEF's byte-mode container (magic
header included) is the right call, not redundant the way it would be for
TagDrop's ASCII path.

Registers one Record Type, say `950`, for the plain secret-key bytes:

```
Tag 950: {
  0: 950,
  2: h'<raw OpenPGP transferable secret key packet bytes>'  // CRITICAL
}
```

Because the key material is sensitive and may not fit one code, the app
composes it through two Wrapper Records, in the fixed order from §4.1 —
`Split (outermost) → Encrypt → plain Type-950 Record` (no `Compress` layer
here — key material is already high-entropy, DEFLATE wouldn't help):

```
authoring:  Type-950 Record  →  Encrypt Wrapper (Type 4)  →  Split Wrapper (Type 2)
decoding:   Split Wrapper    →  Encrypt Wrapper           →  Type-950 Record
            (per code)          (after reassembly)            (the real key)
```

Each of the 3 printed codes carries one Split-Wrapper Record (Type 2) with
`parity_scheme` set — losing one code out of the set is recoverable, which
matters far more for a one-off secret-key backup than for TagDrop's
disposable content sectors. The app wrote **zero** reassembly, parity, or
AES-GCM code of its own for the container format — all of it is the shared
QDEF Wrapper resolver from §4.1, exercised through the *exact same*
recursive "unwrap bytes → re-parse as a Record" step, regardless of what
Type 950 turns out to mean. This is the concrete version of the "won't need
to write the code for it" goal this whole mechanism is for.

## 9. Open questions (not resolved by this draft)

- **Registry governance.** Who allocates application-specific Record Type
  IDs (`100`, `105`, `900`, ...) if this is meant to be shared across
  unrelated projects? No registry exists yet — IDs above are illustrative
  placeholders only.
- **Standard library governance.** Related but narrower (§4): who maintains
  the reserved `1`–`99` range itself — additions like §4.1/§4.2 need some
  process for becoming part of "the stdlib" rather than just another
  vendor's Record Type squatting on a low number.
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
- **Enforcing nesting order (§4.1).** Is `Split → Encrypt → Compress` just
  documented convention an encoder is trusted to follow, or should a
  non-conformant order be independently detectable/rejectable by a
  decoder? Leaning toward "trust the encoder" (matches QDEF's minimal-core
  philosophy) but worth deciding explicitly rather than leaving implicit.
