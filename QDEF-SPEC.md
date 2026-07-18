# QDEF — Quick Data Exchange Format

**This is a copy of https://github.com/mofosyne/tagdrop/blob/master/SPEC.md, DESIGN.md not copied over to this repo as it's not needed for implementation**

**Status: Draft. Validated by two throwaway prototypes — a Node round-trip
prototype covering the full design ([`/prototype`](../prototype)) and a
`no_std`, zero-dependency Rust prototype of the mandatory core specifically
([`/rust/qdef-core`](../rust/qdef-core)), which also builds for a bare-metal
Cortex-M0 target (see [FINDINGS.md](FINDINGS.md)); not yet implemented as a
reference library, not yet used in production anywhere. This document is
normative; the reasoning behind its decisions — mechanisms tried and
removed, alternatives weighed, and what's still unresolved — lives in
[DESIGN.md](DESIGN.md).**

QDEF is a general-purpose binary container for multi-action 2D barcodes
(QR, Data Matrix, Aztec) and NFC tags. Think of it as filling the gap NDEF
already fills for NFC — "here are one or more typed records in one
tap/scan" — but for the byte-mode payload of an optical code, or for an NFC
payload with no existing MIME type doing the routing. No equivalent exists
today: text barcode schemas (`WIFI:S:...;;`, `BEGIN:VCARD`) are rigid,
single-purpose, and text-only; NDEF solves multi-record framing but only
for NFC.

Without a format field to dispatch on, a general-purpose QR reader has no
choice but to *guess* what a scanned payload is — by sniffing prefixes, a
list that only grows and never gets more reliable. NDEF sidestepped this
for NFC decades ago with its MIME-type/TNF field; QR never had the
equivalent. QDEF's magic header plus prefix-based Type-ID routing (§3)
gives byte-mode QR that same explicit, extensible dispatch.

QDEF is meant to be adopted by unrelated applications with no shared
history — a Wi-Fi provisioning sticker, an event ticket, a passphrase-
protected key backup spread across several printed codes (worked example in
§7) are all equally valid uses. It is not tied to, and does not assume
familiarity with, any particular application.

## 1. Abstract & Philosophy

QDEF is binary-first: an extensible, multi-action CBOR payload, parseable
both by a modern smartphone and by a deeply constrained embedded scanner
(transit gate, POS terminal) with only a minimal CBOR decoder — no
semantic-tag support, no compression library, nothing beyond reading maps,
uints, and strings.

QDEF is deliberately two things, not one:

- A minimal **core format** (§3): magic framing, a CBOR Sequence of
  Records, prefix-based Type-ID routing, and a per-key criticality
  rule. A parser that only implements this can route or skip any Record
  without knowing anything else about it.
- A separate, optional **standard library** (§4): reusable building blocks
  (splitting a payload across multiple codes, compression, encryption, a
  generic fallback) that any application can pull in without writing its
  own reassembly, cipher, or fallback-routing code.

Neither layer is optional to the *design* — see §4 for why a minimal
implementer must never be forced to bring a compression or reassembly
library just to route Records.

### When QDEF earns its place

Any application that already defines its own text/URI scheme (human-
typable, clickable — `myapp://...`) should encode its envelope directly
under that scheme, not wrap it in QDEF. The scheme prefix already does the
recognition job QDEF's magic header exists for (§2); wrapping adds only
redundant bytes with nothing to show for them. QDEF earns its place on
carriers with **no pre-existing dispatch**: plain byte-mode QR with no URI
at all, or an NDEF payload with no app-specific MIME type already routing
it. §7's PGP-key-backup example is exactly this case — those codes are only
ever scanned by one app, never clicked or typed, so there's no scheme to
lean on instead.

## 2. Container Wire Format

8-bit byte mode only — never alphanumeric; text-safety is explicitly not a
goal. A 4-byte magic header (4 bytes total, no version byte) for instant
optical-stream validation, followed by a **mandatory container
discriminator** (§3.5 — always exactly one CBOR item, always present),
followed by a CBOR Sequence (RFC 8742) of Records — a sequence rather
than a wrapping CBOR array, so a constrained parser can process each
Record as it streams in without buffering the whole payload first
(validated in the prototype against a real incremental CBOR decoder fed
arbitrary byte chunks).

```
+----------------------+------------------------+----------------------------------+
|   Magic (4 bytes)     | Discriminator (1 item) |     CBOR Sequence of Records     |
+----------------------+------------------------+----------------------------------+
| 0x51 0x44 0x45 0x46   |  uint, bstr, array,    |  Record, Record, Record, ...     |
|       "QDEF"          |  or map (§3.5)         |                                  |
+----------------------+------------------------+----------------------------------+
```

A minimal Record is at minimum a typeID prefix (a bare uint, or a
namespace-pairing array, §3.1) followed by a field Map:

```
+---------------------------+-------------------------------+
|  typeID prefix (1 item)   |   field Map (CBOR map)        |
+---------------------------+-------------------------------+
|  100                      |  { 0: "SSID", 2: 2 }         |
+---------------------------+-------------------------------+
```

The map acts as the record delimiter in the Sequence — the parser knows a
Record ends when it reaches the first Map. An optional bare text string
may follow the typeID (the NDEF-ID-equivalent, §3.1), and unknown items
may appear between the typeID and the map (forward-compat padding for
future QDEF evolution), but the minimum viable Record is just typeID +
map.

For NFC, the magic prefix is redundant: NDEF's own MIME-type field already
identifies the payload. An NDEF record carrying QDEF content uses MIME type
`application/vnd.qdef` with just the CBOR Sequence of Records as the
payload — no magic bytes, no discriminator item.

**The same applies to an application carrying QDEF content under its own
URI scheme** (§1's "When QDEF earns its place"): the scheme prefix
(`myapp:...`) already identifies the payload, so the remainder is a bare
CBOR Sequence of Records with no magic and no discriminator, decoded via
the same `decodeSequence` path. See DESIGN.md for why this also affects
even-Type-ID collision safety on that carrier (§3.5).

**No version byte.** §3.2's even/odd criticality rule already provides
local forward compatibility; see [DESIGN.md](DESIGN.md#container-framing-choices)
for why an earlier draft's version byte was removed.

**No record count or total payload size in the header.** A CBOR Sequence
is self-delimiting; see [DESIGN.md](DESIGN.md#container-framing-choices)
for why these fields were deliberately left out.

## 3. The Record Architecture

Every Record is a sequence of CBOR items terminated by a CBOR Map — one
typeID-bearing item (a bare uint, or a namespace-pairing array),
optionally one NDEF-ID-equivalent text string, zero or more unknown items
(forward-compat padding for future QDEF evolution), then a field Map as
the record delimiter. Using a Wi-Fi Record (Type `100`, see
[EXAMPLES.md](EXAMPLES.md)) as the example (this is where §3.2's even/odd
rule applies):

```
Prefix: 100                                  // typeID (uint 100)

Map:
+-----+------------------------+-------+----------+-----------------------------+
| Key | Value                  | Type  | Even/Odd | If unrecognized             |
+-----+------------------------+-------+----------+-----------------------------+
| 0   | "My Coffee Shop"       | text  | even     | CRITICAL: abort Record      |
| 2   | "guest123"             | text  | even     | CRITICAL: abort Record      |
| 4   | 2                      | uint  | even     | CRITICAL: abort Record      |
| 1   | true                   | bool  | odd      | OPTIONAL: silently ignored  |
+-----+------------------------+-------+----------+-----------------------------+
```

Every Record — a plain content Record like this one or a standard record
type Wrapper Record (§4.1) — has exactly this shape: a typeID-bearing
item, optionally an NDEF-ID, followed by a Map. Field values may be any
well-formed CBOR item now, not just scalars and strings (§3.2) — the
"never needs recursion" property §3.3 describes still holds, just via a
bounded explicit stack (the same one already used to skip unrecognized
prefix items) rather than a shape restriction.

The parser uses a two-phase loop to find each Record's boundaries:
Phase 1 recognizes the Record's single typeID-bearing item (a bare uint,
or a namespace-pairing array), then its optional NDEF-ID text string.
Phase 2 skips any non-map items (forward-compat padding) until it
reaches the first Map, which serves as the record delimiter.

### 3.1 Record Type ID (prefix item) and the NDEF-ID-equivalent

Every Record begins with exactly one typeID-bearing item — a bare uint
(major type 0), or a namespace-pairing array (below) — before the field
Map, optionally followed by exactly one bare text string (the
NDEF-ID-equivalent, below). There is no backup-typeID mechanism and no
decentralized (byte string) or Named (text string) Type ID form. See
DESIGN.md and FINDINGS.md for why these were retired.

A Record with no typeID-bearing item before the map cannot be routed and
MUST be marked as ignored (§3.2's well-formed-but-unroutable case).

The typeID's CBOR major type determines its classification:

```
+------------------+-------------------+------------------+-----------------------+
| typeID CBOR type | Classification    | Scope            | Meaning               |
+------------------+-------------------+------------------+-----------------------+
| uint, even       | Standard record   | Always global    | Registered standard   |
|                  | type              |                  | mechanism or content  |
|                  |                   |                  | type                  |
+------------------+-------------------+------------------+-----------------------+
| uint, odd        | Scoped record     | Namespace-       | REQUIRES a declared   |
|                  | type              | scoped           | namespace (§3.5);     |
|                  |                   |                  | absent namespace,     |
|                  |                   |                  | Record MUST abort     |
+------------------+-------------------+------------------+-----------------------+
```

Even uints are always globally interpreted regardless of any declared
namespace, so standard record type mechanisms (§4) work unconditionally
inside any namespaced container. Odd uints require a declared namespace;
without one, the Record MUST abort.

**TypeID form boundary.** A bare typeID item is only ever CBOR major
type 0. Major type 4 (array) is valid at the typeID position only as a
definite-length 2-element namespace-pairing item (below); any other
array shape at that position falls through to Phase 2's forward-compat
skip. No other major type is valid there.

**Even/odd vocabulary reuse note.** §3.2's even/odd also governs map
*keys* (critical vs. optional) — a distinct axis from typeID *value*
parity here; the two never overlap.

**Namespace-pairing prefix item.** In place of a bare typeID, a
Record's prefix MAY instead hold a **namespace-pairing item**: a
definite-length CBOR array of exactly 2 elements, `[namespace, typeId]`,
where `namespace` is a byte string (the only valid Namespace ID shape,
§3.5) and `typeId` is a uint. A uint in the `namespace` slot is not
recognized as a pairing item; the array falls through as an unrecognized
prefix item, and the Record has no typeID. When present, the array's
second element becomes the Record's routing typeID, scoped against the
array's first element instead of the container's ambient namespace
(§3.5) — a purely local override; every other Record is unaffected.

```
Prefix: [h'a9d6e1f30b7c4482', 1]    // this Record's own namespace,
                                     //   paired with a scoped typeID
```

An even `typeId` inside a pairing is vacuous — even uints are always
globally interpreted regardless of any declared namespace (§3.5),
unconditionally. Pairing only affects odd (scoped) typeIds.

A namespace-pairing item is paid fresh on every Record that carries
one — unlike the container discriminator, there is no amortization. A
Record happy with the container's ambient namespace should use a bare
typeID instead. See DESIGN.md's "Multiple namespaces per container" for
the byte-cost comparison.

**NDEF-ID-equivalent.** A Record MAY carry a stable, type-independent
external reference, mirroring NDEF's own `ID` field: immediately
following the typeID-bearing item, a Record's prefix MAY hold exactly
one bare CBOR text string (major type 3). It never affects routing;
absent, it costs nothing; present without a recognized typeID before
it, the whole Record is unroutable and ignored, not treated as an ID.

```
Prefix: 100, "wifi-record-1"    // typeID 100, NDEF-ID "wifi-record-1"
```

**Implementer caution for uint Type IDs:** a uint Type ID MUST be
encoded as a native CBOR uint (major type 0), never wrapped in a bignum
tag (CBOR tag `2`/`3`). Verify your specific encoder does this, not just
that some CBOR library is present. See FINDINGS.md #14.

**Implementer caution for the NDEF-ID-equivalent:** it MUST be a
definite-length CBOR text string. Comparison, if an application layer
does any, is exact and byte-for-byte over the raw UTF-8 encoding — no
Unicode normalization, case-folding, or whitespace trimming.

### 3.2 The Extensibility Rule (Even/Odd Keys)

Borrowed from PNG's critical/ancillary chunk convention. Note: this even/odd
rule applies to *map keys* only, not to a typeID prefix item's *value* —
see §3.1 for the even/odd classification of Type ID values, which is a
separate convention on a separate axis.

- **Even keys are CRITICAL.** An unrecognized even-numbered key MUST cause
  the parser to abort processing *that record* (not the whole stream —
  other records in the same Sequence are unaffected).
- **Odd keys are OPTIONAL.** An unrecognized odd-numbered key MUST be
  silently ignored; the rest of the record still processes normally.

This gives per-field forward compatibility: a future critical field doesn't
require any version-bump mechanism, only choosing an even key
number the current Record Type doesn't yet define.

**A Record field's value MAY be any well-formed CBOR item** — a scalar,
a string, or a nested array, map, or tag of any depth. See DESIGN.md
and FINDINGS.md for why the earlier flat-scalar-only restriction was
dropped.

```
7: [1, 6, 11]                     // a bare array (major type 4)
```

Pre-encoding structured content as an opaque byte string is still
legal, and still useful when an outer decoder should skip it without a
generic CBOR library:

```
7: h'8301060b'                    // pre-encoded [1, 6, 11], opaque to a
                                   //   decoder that skips it
```

**Skip-safety.** `skip_any_item` (the mandatory core's generic "skip any
well-formed CBOR item" function) walks containers of any shape with a
bounded explicit stack, never the call stack — the same mechanism
already used for unrecognized prefix items. Nesting depth is bounded by
each decoder's own practical limit, an implementation detail, not part
of this spec's wire contract.

**Advisory, not required.** Encoders SHOULD NOT produce field values
nested more deeply than genuinely useful content needs. Decoders MAY
enforce their own depth limit and reject anything deeper.

**Indefinite-length items are legal in field values for decoders to
accept, never for conformant encoders to produce** — §3.4's
canonical-encoding requirement (definite-length forms only) is
unchanged for encoders; this is decoder-side tolerance only.

**Precondition on "the whole stream is unaffected":** this isolation
guarantee assumes the Record is at least well-formed CBOR — a parser
needs the Record's byte length to find where the next Record starts. A
Record that fails to route (no typeID in prefix, §3.1) is still
well-formed and isolable this way. A Record that is malformed CBOR
(including a malformed indefinite-length chunk sequence) is a stronger
failure: the parser cannot determine that boundary and cannot safely
resume the Sequence at all.

### 3.3 Conformance Levels

QDEF is designed so a minimal, generic parser is genuinely minimal — no
implementer has to bring a compression library or sector-reassembly logic
just to support the *container*:

- **Core QDEF parser (mandatory, all implementers):** verify magic, skip
  the discriminator as one opaque CBOR item (§3.5 — never interpreting
  its shape), walk the CBOR Sequence, read each Record's prefix typeIDs
  to route or skip it, apply the even/odd rule (§3.2) to unrecognized
  keys. That's the entire surface area — no compression, no multi-code
  state, no knowledge of any specific Record Type's fields.
- **Record-Type-specific handling (optional, per Record Type an implementer
  chooses to support):** everything else — including whether a given
  Record Type's payload happens to be compressed, or happens to require
  reassembling several codes — is defined *by that Record Type*, not by
  QDEF. An implementer who only cares about Wi-Fi provisioning (Type 100)
  never has to read, understand, or link against whatever some other
  registered Record Type does internally.

A conformant core parser never needs *true* recursion: a Record is
always exactly `typeID-item → (NDEF-ID)? → Map`, and skipping any
well-formed CBOR item at any depth — an unrecognized field, or a whole
unrecognized Record — uses a bounded explicit stack instead of the call
stack. Validated in [`rust/qdef-core`](../rust/qdef-core); see
FINDINGS.md for the mechanism (`skip_any_item`).

### 3.4 Canonical Encoding

**Encoders MUST produce CBOR meeting RFC 8949 §4.2.1's core deterministic
encoding requirements** for every Record: the shortest-form argument for
every integer, length, and tag; no indefinite-length items; and every
Record Map's keys sorted in bytewise lexicographic order of their own
encoded bytes. QDEF doesn't define a new canonical-encoding rule — it
adopts CBOR's own, unchanged.

This is a requirement on *encoders*, not decoders: a decoder MUST NOT
reject an otherwise well-formed Record merely for being non-canonically
encoded (key order never affects whether `map[N]` is findable). The rule
exists so that anywhere QDEF hashes a Record's bytes for content-
addressing (§4.1's `group_id`, and any future Sign mechanism, §8), two
independent encoders handed identical field values compute the same
hash — otherwise semantically-identical content could hash differently
across encoders. See DESIGN.md for the full reasoning.

Not a new implementation burden in practice: most CBOR encoders already
default to shortest-form arguments and definite-length items. The one
requirement needing explicit encoder discipline is map key ordering —
cheap, since Record Maps are small by construction.

### 3.5 The Container Discriminator

Immediately after the magic (§2), every QDEF container carries exactly
one mandatory CBOR item — the **discriminator** — before the CBOR
Sequence of Records begins: a format namespace identifier, the same job
RIFF's form-type (`WAVE`/`AVI `) does after RIFF's own magic. It is not
itself typeID-prefixed or Map-shaped; its own CBOR major type dispatches
which shape below it is. The mandatory core (§3.3) only skips it as one
opaque CBOR item; interpreting it is Record-Type-interpretation-specific
handling, prototyped in `prototype/src/header.js`, not part of the
minimal Rust core (`rust/qdef-core`). See DESIGN.md for why the
discriminator is mandatory rather than an optional leading Record.

**Three recognized shapes**, dispatched by the discriminator's own CBOR
major type. A namespace ID is always a byte string — there is no uint
(Allocated) namespace tier. See DESIGN.md for why.

```
+-----------------------------------+------------------------------------------------------------+
| Discriminator shape                | Meaning                                                     |
+-----------------------------------+------------------------------------------------------------+
| uint 0                             | No namespace declared (cheapest legal container: 1 byte)   |
| byte string                        | Decentralized Namespace ID (self-certifying, no registry)  |
| map                                | Full extensible form: {1: namespace, 3: hint, 5: backup,   |
|                                     | ...} — the ONLY way to carry a hint or a backup namespace   |
| anything else (unrecognized,       | Degrades to "no namespace" — same graceful degrade as      |
| including any array and any        | uint 0                                                      |
| nonzero uint)                      |                                                              |
+-----------------------------------+------------------------------------------------------------+
```

```
0                                      // cheapest legal container: no namespace declared

h'a9d6e1f30b7c4482'                    // bare namespace, no hint

{                                       // full extensible form -- needed for a
                                        //   hint, a backup namespace, or both
  1: h'a9d6e1f30b7c4482',              // namespace: a byte string, always
  3: "com.example/tagdrop-paper",      // OPTIONAL: recoverable Hint name,
                                        //   hash-derivation pattern below
  5: h'a7f90b3c'                       // OPTIONAL: a second, differently-
                                        //   sized namespace, for a length-
                                        //   promotion transition in
                                        //   progress
}
```

A decoder MUST recognize all three shapes, and MUST treat any array, any
nonzero uint, or any other CBOR shape not listed above as unrecognized,
degrading gracefully to "no namespace" — never a hard failure.

**A hint on a namespace ID is always self-certifying, since a namespace
ID is always a byte string.** Key `3`'s Hint name plays the
self-certifying strengthening role the hash-derivation algorithm below
describes: anyone examining a QDEF container found in the wild, with no
registry or external lookup, can both read the namespace's name off the
wire and verify it matches the namespace bytes.

**Byte-length guidance:** self-allocate freely at **4 bytes or longer**
— collision safety comes from the byte length alone, and stays
comfortable into the tens of thousands of independently self-chosen
namespaces. **Shorter than 4 bytes is reserved, not self-allocatable**
— collision risk at those widths is real even against a small,
uncoordinated population, so a namespace this short is only safe with
its uniqueness guaranteed by direct coordination. See DESIGN.md for the
collision math this floor is grounded in.

**Hash-derivation algorithm**, for any self-certifying byte string value
this spec calls for (namespace IDs here, and App Route's hash-derived
form, §4.4 — a general-purpose primitive, not tied to Type IDs):

```
digest = SHA-256(UTF-8(name))
N      = developer-chosen byte length (4+ for namespace IDs, per the
         byte-length guidance above)
Value  = digest[0..N] as a definite-length CBOR byte string (major type 2)
```

Verification is opportunistic — no version marker records whether a
value used this convention; if the hash matches, the binding is
confirmed, otherwise the Hint name is just a plain, unverified label.

**A name feeding hash-derivation SHOULD be qualified by something the
namer actually, verifiably controls** — a reverse-domain string
(`"com.example.tagdrop"`, not bare `"tagdrop"`), the same convention
Java packages, XML namespaces, and MIME subtypes use, so the derived
value behaves like a random draw rather than a likely collision with
another implementer's bare-word choice. See DESIGN.md for why an
unqualified name produces a certain, not probabilistic, collision.

Prototyped in `prototype/src/header.js`'s `verifyNamespaceHint`. See
FINDINGS.md #21 for a real verification bug this pinned algorithm fixed.

**No dedicated version field.** The map form is the fully extensible
escape hatch — a future incompatible addition is a new even/critical
key in it, using the same even/odd extensibility (§3.2) every Record's
own field Map already relies on.

**What a declared namespace changes.** Even uint Type IDs always stay
globally interpreted regardless of any declared namespace — standard
record type mechanisms (§4) and registered content types keep working
unconditionally inside any namespaced container.

**Odd uint Type IDs become namespace-scoped once a namespace is
declared.** When the discriminator declares namespace `N`, a Record's
odd Type ID `T` is looked up as the compound key `(N, T)`, not the bare
value `T` — the same pattern as a Bluetooth short UUID paired with its
Base UUID. `N` is normally the container's ambient namespace, but a
Record MAY instead pair its own typeID with a different namespace
inline (§3.1's namespace-pairing item), overriding `N` for that one
Record only.

**Odd uint Type ID with no declared namespace MUST cause the Record to
abort** — there is no collision-safety source without one.

**Correctness obligation on any decoder implementing namespace-scoped
semantics:** it MUST check for a declared namespace before applying its
interpretation, and MUST NOT fall back to a global reading merely
because it doesn't recognize the specific `(namespace, TypeID)` pair —
that pair is simply unrecognized, never silently reinterpreted as the
global meaning of the same number.

**MUST repeat, identically, on every physical code of a multi-code
group whenever that group carries any namespace-scoped (odd uint) Type
ID** — whether the scoped Type ID belongs to a plain sibling Record or
is only reachable after a Wrapper stack (§4.1) resolves. Each physical
code is parsed independently, from a blank slate, with no cross-code
state; a decoder holding only one code has no way to learn a namespace
declared on another. Split's XOR parity does not protect the
discriminator itself (only fragment bytes), so a namespace declared on
a single code is a genuine single point of failure the Split-protected
content does not otherwise share.

**Fully additive, no migration forced.** An app with an existing even
uint Type ID keeps it working forever, namespaced container or not.
Adopting namespace-scoped odd uint IDs for new content is an
independent, opt-in choice.

**Recommended pattern for an isolated-carrier application** — one whose
own URI scheme or NDEF MIME type already isolates it from other
QDEF-aware decoders (§2): namespace-scoped odd Type IDs with the
namespace *implied* by the carrier, never transmitted, rather than a
self-allocated even ID. This costs nothing more than the even-ID
pattern but fails *closed* if the same bytes ever reach an unisolated
carrier (an odd Type ID with no namespace present is a spec-mandated
abort), instead of silently misinterpreting the ID. The implied
namespace value MUST be identical across every one of the application's
own carriers. See DESIGN.md for the byte-cost comparison.

**Caution: carrier isolation is a property of the point of consumption,
not of the bytes themselves.** "Isolated" and "unisolated" byte
sequences are bit-for-bit identical. An implementer reusing identical
CBOR-sequence bytes across multiple carriers MUST verify *every*
carrier those bytes can reach provides isolation, not just the primary
one — a future carrier added without an equivalently exclusive dispatch
mechanism silently reintroduces full collision exposure for every even
Type ID already in use.

Prototyped end to end in `prototype/src/header.js`,
`prototype/src/wrappers.js`'s `resolveStack`, and cross-validated
against `rust/qdef-core` (needs no discriminator-shape-specific code at
all). See `prototype/test/header.test.js` and
`prototype/test/multi-code-namespace.test.js`.

## 4. The QDEF Standard Record Types

QDEF is a *format plus a set of standard record types*, not just the
format — the same relationship C-the-language has with libc. §3 defines
a minimal core any conformant parser must implement, and says nothing
about compression, splitting, encryption, or graceful degradation for
scanners that don't understand a given Record Type. Those live here
instead: a small, curated set of Record Types any application can pull
in — writing no reassembly code, no cipher code, no fallback-routing
code of its own.

**Standard record type IDs:** even numbers `2`–`98` are reserved for
these standard record types, maintained alongside the QDEF spec itself.
Even numbers `100` and above are open for applications to register their
own domain-specific Record Types ([EXAMPLES.md](EXAMPLES.md)) — who governs *that*
allocation is still open (§8), but at least the two registries are
partitioned by construction and can't collide.

**Currently assigned Type IDs**, each defined in full in its own
subsection below:

```
+------+------------------+---------+---------------------------------+
| ID   | Record Type      | Section | Notes                          |
+------+------------------+---------+---------------------------------+
|  2   | Split            | §4.1    | Fragment reassembly / parity    |
|  4   | Encrypt          | §4.1    | AEAD (e.g. AES-256-GCM)         |
|  6   | Media Payload    | §4.3    | Typed binary content            |
|  8   | Compress         | §4.1    | DEFLATE                         |
| 10   | Fallback Hint    | §4.2    | URI fallback for unaware readers|
| 12   | App Route        | §4.4    | Application dispatch/routing    |
+------+------------------+---------+---------------------------------+
```

All six sit in the `0`–`22` Standards Action tier — this spec document's
own publication is the authoritative declaration for them, independent
of whether a registry authority exists yet. An adopter's own pick in
the `100`–`32767` tier is different: provisional until a review
authority exists, since nothing in this document declares what any
specific number in that range means.

**Type ID allocation ranges** (adapted from CBOR's tag registry pattern,
RFC 8949 §9.2):

```
+----------------+----------+----------------------------------------------+
| Range          | Even/Odd | Purpose & governance                         |
+----------------+----------+----------------------------------------------+
| 0–22           | even     | Standards Action — Wrapper Records and other  |
|                |          | standard record type infrastructure,         |
|                |          | spec-maintained                              |
| 24–98          | even     | Specification Required — standard record     |
|                |          | types reserved for future use                |
| 100–32767      | even     | Specification Required — common vocabulary,  |
|                |          | reviewed application-specific types          |
| 32768+         | even     | First Come First Served — self-allocated     |
| odd uints      | odd      | Namespace-scoped only (§3.5) — requires      |
|                |          | declared namespace, abort otherwise          |
+----------------+----------+----------------------------------------------+
```

Each tier's collision-safety comes from curation (reviewed before
granting: Standards Action, Specification Required) or recording
(tracked first-come, no review: First Come First Served). No registry
authority exists today for any uint tier — see DESIGN.md. Namespace IDs
(§3.5) use a third source, self-certification from byte length alone,
but that's a namespace-layer property, not a Type ID tier.

**Choosing a Type ID form.** Three mechanisms sit above, each solving
collision-safety a different way. Work through these questions in
order; stop at the first `YES`:

```
1. Is this part of QDEF's own standard-record-type infrastructure
   (a Wrapper Record or similar mechanism, not application content)?
     YES -> even uint 0-22 (Standards Action, spec-maintained -- not
            something an application ever picks for itself)

2. Do you want this Type eventually recognized by unrelated
   implementers, even though no registry exists yet (§8)?
     YES -> even uint 100-32767 (Specification Required / common
            vocabulary). Ship now with an illustrative number -- there
            is no cheaper provisional-placeholder mechanism anymore
            (no backup typeID promotion path), so pick the number you
            intend to keep.

3. Does your application already have -- or are you willing to
   declare -- a namespace (self-chosen; §3.5 has no Allocated/uint
   namespace tier, so this is always a byte string you pick yourself)?
     YES -> a small sequential odd uint (1, 3, 5...) inside that
            namespace. The cheapest option (as little as 1 byte), and
            if your carrier already isolates you (own URI scheme, own
            NDEF MIME type), the namespace itself can be implied by
            that carrier and never transmitted at all (§3.5) -- so
            this option is usually available "for free" even without
            wanting to pay for an explicit namespace declaration.
     NO  -> a self-allocated even uint, 32768+ (First Come First
            Served) -- but only if your carrier already isolates you
            (own URI scheme, own NDEF MIME type) and you're accepting
            that its safety is carrier-dependent (§3.5's caution)
            rather than declaring a namespace after all (re-read the
            YES branch first -- the implied-namespace pattern costs
            the same and is strictly safer).
```

Most application Record Types resolve at step 3's `YES` branch — a
declared namespace, implied or explicit, with small sequential odd
uints inside it. See DESIGN.md and FINDINGS.md #29/#30/#36.

### 4.1 Wrapper Records (optional)

A **Wrapper Record** is an ordinary Record — same routing, same even/odd
rule — using a reserved low Type ID, whose payload is not application data
but the *encoded bytes of another Record* (which may itself be a Wrapper
Record, nested). Unwrapping and re-parsing the result as a Record is the
entire mechanism: no new parsing concept beyond "run the Record parser
again on these bytes." A single generic resolver — reassemble fragments /
decompress / decrypt, then re-parse as a Record, repeat until the result
isn't a Wrapper Record anymore — implements this for every Record Type
that opts in, with zero code written by that Record Type's own author
(demonstrated in `prototype/src/wrappers.js`'s `resolveStack`).

Wrapper Type IDs, authoritatively assigned by this spec document itself
(Standards Action, `0`–`22` — see the note above the Type ID allocation
table on why that tier needs no separate registry to be real):

```
Type 2: {                    // Split
  // prefix typeID: 2
  // field map:
  0: h'<group_id>',          // CRITICAL: content-addressed (a hash of the
                              //   full reassembled bytes) — never an issued
                              //   serial, so no coordination is needed
                              //   between independent encoders (relies on
                              //   §3.4's canonical-encoding rule to actually
                              //   hold across more than one encoder). A
                              //   decoder MUST recompute this hash after
                              //   reassembly and reject a mismatch — it
                              //   doubles as the group's integrity check.
  2: 1,                      // CRITICAL: this fragment's index
  4: 4,                      // CRITICAL: total fragment count in the group
  6: h'<fragment bytes>',    // CRITICAL: this code's slice
  7: 5821,                   // OPTIONAL as a key (odd), but MUST be present
                              //   whenever key 9 (parity_scheme) is set —
                              //   see chunking rule below. When present:
                              //   total_bytes of the reassembled whole.
  9: 1                       // OPTIONAL: parity_scheme — 0/absent = none,
                              //   nonzero selects a registered forward-
                              //   error-correction scheme so the group
                              //   tolerates a missing/damaged code.
}

Type 8: {                    // Compress (DEFLATE)
  // prefix typeID: 8
  // field map:
  0: h'<deflate bytes>'      // CRITICAL
}

Type 4: {                    // Encrypt (e.g. AES-GCM)
  // prefix typeID: 4
  // field map:
  0: h'<nonce>',             // CRITICAL
  2: h'<ciphertext+tag>',    // CRITICAL
  3: 3,                      // OPTIONAL: Algorithm — 3 = A256GCM
  5: -25                     // OPTIONAL: Key Algorithm — -25 = ECDH-ES+HKDF-256
}
```

**Keys `3` (Algorithm) and `5` (Key Algorithm)** are each a uint or a
text string, an encoder's choice:

- **A uint** is a [COSE Algorithm
  ID](https://www.iana.org/assignments/cose/cose.xhtml) (RFC 9053/9054),
  covering both content encryption algorithms (`1`/`2`/`3` =
  A128GCM/A192GCM/A256GCM) and key-agreement/wrap/derivation algorithms
  (`-25` = ECDH-ES+HKDF-256; `-10` = direct+HKDF-SHA-256; `-5` = A256KW)
  — negative integers included, permitted by §3.2.
- **A text string** names the algorithm directly for anything not
  registered there.

Both keys are odd/optional: absent, a decoder falls back to whatever
algorithm it already assumed, which fails safely either way since
AEAD's own authentication tag check catches a wrong-algorithm or
wrong-key attempt.

**A decoder that does honor key `3`/`5` MUST NOT let them broaden which
algorithms it's willing to run** — the same "alg" confusion class of
vulnerability JOSE/JWT is known for. Treat the field as a hint to check
against an application-chosen allowlist, never as an instruction to
trust outright.

**Encrypt cannot provide deniability — a scope boundary, not a gap.**
Being wrapped in a Type-`4` Record at all is itself a visible
declaration to any QDEF-aware parser, since Type ID routing (§3.1)
happens unconditionally before any per-Record-Type logic runs. An
application needing ciphertext indistinguishable from random should
keep its own encryption entirely inside an opaque registered blob (§5)
instead. See FINDINGS.md #13.

**Fragment chunking (Type 2).** The spec must fix *how* the original bytes
are sliced, not just what fields describe the result, or two independent
encoders/decoders can't agree on wire bytes. Fixed rule:

```
chunkLen = ceil(total_bytes / count)
fragment[i] = bytes[i * chunkLen .. min((i + 1) * chunkLen, total_bytes)]
```

the last fragment is shorter than `chunkLen` when `total_bytes` isn't an
exact multiple of `count`. This uniform-chunking rule is what makes
`parity_scheme`'s XOR-style recovery well-defined (every fragment
zero-padded to `chunkLen` before XOR). Splitting a group across
different-capacity physical codes with different-sized fragments while
still supporting parity recovery is not yet resolved — see §8.

`parity_scheme` mechanics: a parity fragment (index ≥ `count`, present
only when `parity_scheme` is set) is pure bonus redundancy — plain
reassembly only ever requires fragments `0` through `count − 1`. A
decoder that doesn't understand `parity_scheme` can ignore any fragment
past `count` and still reassemble correctly, losing only resilience,
never correctness. `parity_scheme = 1` (prototype-defined only): a
single XOR parity fragment, recovering exactly one missing/damaged
fragment.

**Fixed nesting order** when more than one Wrapper is combined: `Split
(outermost, if present) → Encrypt → Compress → plain inner Record`.
Compress-before-encrypt is the only sound order (ciphertext doesn't
meaningfully compress); Split-outermost is recommended for efficiency
but **not structurally required, and a decoder cannot detect or reject
a different order** — the generic resolver has no notion of "correct"
order. See FINDINGS.md #7.

**Why a wrapper, not a reserved key range on the inner record itself:**
wrapping avoids a cross-record correctness hazard. See DESIGN.md.

**Cost:** wrapper framing is added per code on top of the inner record,
so this stays strictly opt-in — a Record Type with no need for it stays
a plain, unwrapped Record.

### 4.2 Fallback Hint (optional)

Unlike §4.1, this is deliberately **not** a wrapper — a plain standard record type Record
Type meant to sit as a *sibling* alongside real content records in the same
CBOR Sequence, carrying a URI any generic tool can follow if it doesn't
understand anything else in the container:

```
Type 10: {                         // Fallback Hint (standard record type)
  // prefix typeID: 10
  // field map:
  0: "https://example.com/open-this",  // CRITICAL: a URI a generic tool
                                        //   or browser can follow
  1: "Open in MyApp",                   // OPTIONAL: human-readable label
  3: "en",                              // OPTIONAL: BCP 47 language tag
                                         //   for key 1's label
  5: 0                                  // OPTIONAL: suggested action --
                                         //   0 = perform the action (open
                                         //   the URI), 1 = save for later,
                                         //   2 = open for editing
}
```

This is what gives a QDEF container the "something useful happens even
without the specific app" property. It **must** stay a plain sibling
record, never nested inside a Wrapper — a Wrapper's opaque payload
would hide it from a parser that understands nothing else in the
container.

**Keys `3` and `5`** are odd/optional (§3.2): key `3` a BCP 47 language
tag for key `1`'s label, key `5` a suggested action (`0` = perform the
action, `1` = save for later, `2` = open for editing). A decoder that
doesn't recognize either still gets a fully working URI and label. See
DESIGN.md for why these mirror NDEF Smart Poster's own fields.

**Multiple languages or URIs need no new mechanism** — repeat Fallback
Hint as an ordinary sibling Record, once per variant. Nothing in QDEF
restricts how many Records of the same Type appear in one Sequence.

### 4.3 Media Payload (optional)

A plain standard record type Record Type — not a wrapper — for attaching a standard,
already-widely-recognized media type (a JPEG thumbnail, a vCard, a PDF
snippet) without registering a bespoke Type ID for every possible file
format the way [EXAMPLES.md](EXAMPLES.md) does for application-specific content:

```
Type 6: {                          // Media Payload (standard record type)
  // prefix typeID: 6
  // field map:
  0: 22,                           // CRITICAL: Media Type — uint or text,
                                    //   see below (22 = image/jpeg)
  2: h'<payload bytes>'            // CRITICAL: the content itself
}
```

**Key `0` (Media Type) may be a uint or a text string** — an encoder's
choice, and a decoder MUST accept either shape:

- **A uint in `0`–`65535`** is a [CoAP Content-Format
  ID](https://www.iana.org/assignments/core-parameters/core-parameters.xhtml)
  (RFC 7252 §12.3, as amended by RFC 9876) — an existing IANA registry
  assigning compact numeric IDs to common media types (`application/cbor`
  = 60, `image/jpeg` = 22, `image/png` = 23, `application/json` = 50,
  `text/plain;charset=utf-8` = 0, and hundreds more).
- **A text string** is the literal MIME type, used whenever the media
  type isn't in CoAP's registry (e.g. `"text/vcard"`).

Adopters relying on this field SHOULD keep a periodic mirror of CoAP's
Content-Formats table, so the numbering can be kept alive independently
if that registry ever goes unmaintained. See DESIGN.md.

Prototyped in `prototype/test/media-payload.test.js`.

### 4.4 App Route (optional)

A plain standard record type Record — not a wrapper — for letting a generic
QDEF-aware scanner offer to launch a specific handling application,
comparable to NFC's Android Application Record (AAR) or platform
Intent-filter dispatch, without the scanner needing any
implementer-specific knowledge baked in ([GitHub issue
#10](https://github.com/mofosyne/qdef/issues/10)):

```
Type 12: {                         // App Route (standard record type) — domain form
  // prefix typeID: 12
  // field map:
  0: "example.com",                // CRITICAL: a domain the routing
                                    //   target has verified control over
  1: "Open in Example App"         // OPTIONAL: human-readable label
}

Type 12: {                         // App Route (standard record type) — hash-derived form
  // prefix typeID: 12
  // field map:
  0: h'<truncated SHA-256>',      // CRITICAL: hash-derived byte string
                                    //   value (§3.5's derivation algorithm)
  1: "com.example/tagdrop-paper"   // OPTIONAL: Hint name, same role as
                                    //   §3.5's hash-derivation hint
}
```

**Key `0` may be a domain string or a hash-derived byte string — two
different trust models for two different purposes.**

*The domain form* is verifiable using the mechanism Android App Links
and iOS Universal Links already deploy (a `.well-known` file —
`assetlinks.json` on Android, `apple-app-site-association` on iOS —
hosted on the domain the claimant controls). Use this form for
auto-launch dispatch, where getting it wrong means the wrong
application opens.

*The hash-derived form* reuses §3.5's hash-derivation algorithm to
produce key `0`'s value, with key `1` playing the Hint name role. This
is a plain field value, not a Type ID — App Route's own routing typeID
is always the standard uint `12` either way. **This form has no
anti-spoofing property.** The hash-derivation proves name-to-value
consistency, never authorization — anyone can compute the same hash
from the same name. Use this form only where getting it wrong costs
*effort*, not *trust*: a fast, per-code pre-filter a scanner uses to
reject an obviously-unrelated scan before attempting reassembly,
layered ahead of §4.1's `group_id` integrity check, never as a
replacement for it.

Resolving a domain to a launch target is platform-specific:

- **Android** exposes an explicit query (`PackageManager` Intent-filter
  resolution) a scanner can call to ask "which installed app claims
  this domain."
- **iOS** exposes no equivalent query. A scanner constructs an actual
  `https://` URL from the domain and opens it (`openURL:`); iOS checks
  the domain's `apple-app-site-association` registration as a side
  effect of opening that URL.

Key `0` carries the bare domain, not a full URL.

**Not positionally special** — a decoder finds this Record the same way
it finds any recognized Record Type (§3.1).

**Encoder etiquette, repetition across a multi-code group:**

- *The domain form* SHOULD repeat verbatim on every code if the adopter
  wants auto-launch to work from whichever code is scanned first.
  Restricting it to one designated code is also valid; auto-launch then
  only fires from that code.
- *The hash-derived form* SHOULD repeat on every code, more strongly
  than the domain form — its entire value is rejecting an
  obviously-unrelated scan *before* reassembly, which a copy on only
  one code can't do for scans of any other code in the group.

Both forms SHOULD stay small and plain — never Compress- or
Split-wrapped — so a scanner can read one without reassembling anything
else first. See DESIGN.md for the per-code-repetition cost tradeoff
between the two forms.

## 5. Adopting QDEF for an existing application-specific format

An application with its own existing binary payload format (e.g. a
proprietary CBOR sequence used today for some other transport) can register
one Record Type ID and carry that payload unchanged, byte-for-byte, as an
opaque blob under a single key:

```
// prefix typeID: N
{
  0: h'<existing payload bytes>'  // CRITICAL: raw bytes, unchanged from
                                   //   whatever that application already
                                   //   defines — QDEF never looks inside
}
```

This lets a QDEF-aware scanner dispatch a single byte-mode QR or NFC tag
containing, say, a Wi-Fi Record *and* this application's own content Record
together — without that application's own decoder changing at all: it
still just reads the raw bytes out of key `0`. This is additive and
opt-in — nothing about the application's own format needs to route through
QDEF for it to keep working exactly as it does today.

(`mofosyne/tagdrop` uses exactly this pattern, illustrated here as Type
`900`; §7 below is an unrelated adopter using the same mechanism.)

**Registering a real Type ID before governance exists.** `900` here is
an illustrative placeholder, not a protected allocation — the
`100`–`32767` tier has no review authority yet. Any adopter wiring this
into real shipping code before that governance exists should declare
their own namespace (§3.5) and use a namespace-scoped odd uint instead
of a fixed low number in the shared tier — cheaper, and no Type ID to
migrate later.

**On signing:** an adopter whose own signature covers the
fully-reassembled plaintext (after all splitting/addressing is
resolved) needs no QDEF-level Sign mechanism — §4.1's `group_id` is
already a content hash a decoder MUST verify after Split reassembly,
which is all a whole-payload signature needs from the container. See §6
and DESIGN.md.

## 6. Compression and splitting across multiple tags/codes

**QDEF itself defines neither** — both stay entirely inside each Record
Type's own payload definition. An application that already solved
reassembly/compression for its own format keeps using its own solution,
unchanged, rather than adopting a second, competing one at the QDEF layer.
See [DESIGN.md](DESIGN.md#why-not-build-compression-or-splitting-into-the-container)
for why these were deliberately kept out of the container.

**The same reasoning applies to signing.** An application with its own
proven authentication mechanism over the fully reassembled payload needs
no QDEF Sign primitive either (§5). §8's Sign entry is for the different
case — a Record with no pre-existing answer of its own.

**If an application wants splitting, compression, or encryption without
writing any of it itself:** that's what §4.1's Wrapper Records are for — a
generic, reusable resolver any Record Type can opt into by simply being
wrapped, with zero code written by that Record Type's own author (§7 is the
worked example).

## 7. Worked example: passphrase-protected key backup across several codes

An app backs up a passphrase-protected secret key across a set of printed
QR codes. This app has **no scheme of its own** to dispatch on — these
codes are only ever scanned by its own app, never clicked or typed — so per
"When QDEF earns its place" (§1), going through QDEF's byte-mode container
(magic header included) is the right call.

Registers one Record Type, say `950`, for the plain secret-key bytes:

```
// prefix typeID: 950
{
  0: h'<raw secret key packet bytes>'  // CRITICAL
}
```

Because the key material is sensitive and may not fit one code, the app
composes it through two Wrapper Records, in the recommended order from
§4.1 — `Split (outermost) → Encrypt → plain Type-950 Record` (no `Compress`
layer here — key material is already high-entropy, DEFLATE wouldn't help):

```
authoring:  Type-950 Record  →  Encrypt Wrapper (Type 4)  →  Split Wrapper (Type 2)
decoding:   Split Wrapper    →  Encrypt Wrapper           →  Type-950 Record
            (per code)          (after reassembly)            (the real key)
```

Each printed code carries one Split-Wrapper Record (Type 2) with
`parity_scheme` set — losing one code out of the set is recoverable, which
matters far more for a one-off secret-key backup than for disposable
content. The app wrote **zero** reassembly, parity, or AES-GCM code of its
own for the container format — all of it is the shared QDEF Wrapper
resolver from §4.1, exercised through the exact same recursive "unwrap
bytes → re-parse as a Record" step, regardless of what Type 950 turns out
to mean. This is exactly the case Encrypt's Algorithm/Key Algorithm fields
(§4.1) are optional for: the app's own passphrase-KDF scheme is only ever
read by itself, so it has nothing to gain from self-describing it — those
fields exist for the *different* case of two unrelated apps needing to
interoperate on a key transfer, not this one.

This exact scenario — 3 data fragments + 1 XOR parity fragment, one
fragment deliberately dropped and recovered, then the full
Split→Encrypt→plain chain decrypted and re-parsed — is exercised end to end
in `prototype/test/roundtrip.test.js`.

## 8. Design rationale and open questions

Moved to [`DESIGN.md`](DESIGN.md): why mechanisms were removed (the CBOR
tag route), alternatives weighed and
rejected, comparisons against NDEF/BBQr/MCAP and `mofosyne/tagdrop`, and
what this draft still hasn't resolved (registry governance, the Sign
wrapper, Split's per-code capacity limits). None of it is required
reading to implement a conformant parser — everything normative is
above this line.
