# CLAUDE.md

Project-specific notes for Claude Code sessions working on this repo. For
user-facing docs see `readme.md`; for the wire format see `SPEC.md`; for
Android build setup see `DEVELOPING.md`.

## Two parallel wire-format implementations

TagDrop has two independent codec implementations, each handling both
Content and Paper payloads:

1. **Kotlin (Android app)** — `app/src/main/java/.../data/format/`
   (`Base41.kt`, `MiniCbor.kt`, `TagDropCodec.kt`). This is the canonical
   implementation; `app/src/test/.../TagDropCodecTest.kt` is the most
   thorough test suite. **Ported to QDEF Records (SPEC.md v8)** for both
   Content and Paper, matching the JS reference's Preview/Body split,
   Compress Wrapper, and Split Wrapper — the old version-1 four-item
   envelope (`part_meta`/`sector_bytes`) is gone entirely, a clean cutover
   with no dual-format decode support (SPEC.md was still Draft with no
   real code deployed at port time, so no backward-compat burden). Key
   redesigns: `TagDropPayload.kt`'s `Sector`/`PartMeta`/`TagDropScan`
   became `ScannedRecord`/`SplitFragment`/`TagDropScan.RecordScan`,
   mirroring the JS reader's `RecordAssembler`; `SectorAssembler.kt` now
   keys in-flight groups by Split Wrapper `group_id` instead of
   `(type, cache_id)`, and — matching the JS reference exactly — has no
   `AwaitingKey` state: an encrypted payload resolves to `ContentReady`
   immediately with `pendingOverrideBlob` set, and single-code override
   resolution happens in the caller (`ReceiveActivity.handleContentReady`
   trying retained keys directly via `TagDropCodec.tryDecryptOverrideMap`)
   rather than via `SectorAssembler.tryKey()`, which only ever has
   something to resolve for a still-collecting multi-code Split group.
   `createContentSectors`/`createPaper` etc. now return a `ContentBuild`/
   `PaperBuild` exposing the LOGICAL (pre-wrap) `previewRaw`/`bodyRaw`
   alongside the wire-ready `codes`, mirroring the JS generator's
   `{codes, preview, bodyPlain}` shape — needed so `data/signing/
   SigningIdentity.kt`'s placeholder-then-strip signing can hash the
   right bytes without re-decoding a built code. Verification note: this
   environment's Gradle wrapper can't download its own distribution
   (`gradle-9.5.1-bin.zip` 403s — GitHub releases blocked by org egress
   policy) and the one pre-installed system Gradle (8.14.3) is below AGP
   9.2.1's minimum (9.4.1+), so no real `./gradlew testDebugUnitTest` has
   run against this port yet. Verified instead via a standalone
   `kotlinc`+JUnit harness assembled from Maven Central jars (same
   precedent as the signing work's earlier verification, CLAUDE.md's
   "placeholder-then-strip" note below): the full `data/format`+
   `data/signing` package set compiles clean, and a rewritten
   `TagDropCodecTest.kt` (64 tests — Content/Paper round trips including
   real ML-DSA-44 sign/verify, Split reassembly in shuffled order, XOR
   parity reconstruction, `group_id`/`root_hash` tamper detection, SPEC
   §2.2 even/odd key criticality, key-only codes, override-map
   encryption) passes green. A full Android Studio build (Room codegen,
   resource linking, the 15 caller Activities/Fragments) remains the
   honest verification gap.

   **Update: ported to SPEC.md v9's array-wrapped Records + Content
   restructuring**, following the JS side's earlier v9 port (below).
   `MiniCbor.kt` gained array-wrapped Record support mirroring the JS
   `cborRecord`/`craw` design: `encodeRecord(typeId, fields, subrecords)`,
   `decodeRecordPrefix()` (a `DecodedRecord(typeId, record, raw,
   subrecords, trailing)` data class, byte-range-aware via new private
   `Cursor`/`skipItem`/`itemRanges`/`readUintAt` primitives mirroring the
   JS `cborSkipItem`/`cborItemRanges`/`cborReadUint`), and a
   record-array-aware rewrite of `stripKeys()` plus new
   `stripSubrecordType()`/`stripAllSubrecords()` — the old map-level
   `stripKeys(mapBytes, ...)` (which expected a bare CBOR map, not an
   array-wrapped Record) was removed outright since its only caller,
   `TagDropCodec.kt`, was being rewritten in the same change; the
   trailing-run-specific `stripTrailingKeys()` is untouched (unrelated,
   pre-QDEF era, still exercised by its own `MiniCborTest.kt` cases).
   `TagDropCodec.kt`'s Content side was fully rebuilt around Content
   Extension (Type 1) + Media Preview (QDEF Type 14) + Media Payload
   (QDEF Type 6) + Content Signature (Type 3, nested as Media Payload's
   subrecord when signed) — `ContentBuild` now exposes `extensionRaw`/
   `mediaPreviewRaw`/`mediaPayloadRaw` instead of the v8 `previewRaw`/
   `bodyRaw` pair, and `contentSignedMessageHash` takes three args
   (`SHA-256(MediaPreview' || MediaPayload'' || Extension')`, SPEC §10)
   instead of two. Paper (`createPaper`/`parsePaperStream`/
   `paperSignedMessageHash`) is semantically unchanged by v9 but now
   flows through the same `MiniCbor.encodeRecord`/`decodeRecordPrefix`
   array-wrapped primitives instead of the old bare-prefix ones.
   `TagDropPayload.kt`'s `ScannedRecord` became a sealed class
   (`ScannedRecord.Content`/`ScannedRecord.Paper`) rather than one flat
   data class — Content's small-part fields (Content Extension) and
   large-part fields (Media Preview, once known; a Split Wrapper field
   map in the multi-code case; Media Payload's own wire bytes in the
   single-code case) live in their own Record Type's independent key
   namespace, so a single merged `Map<Int, Any>` (workable pre-v9, when
   Content only ever had one small/large Record pair) would have
   silently collided key numbers across two now-separate Records — this
   is also why `TagDropCodec.previewIdentity()` was added, a small
   accessor SectorAssembler/ReceiveActivity use instead of ever poking a
   raw preview map's key `1`/`3` directly (which, post-v9, would read
   the wrong Record for Content: cache-identity now lives in Media
   Preview, not Content Extension). `SectorAssembler.kt`'s `Group` and
   `SectorAssembler.State.ContentReady` were updated to match (`Group`
   tracks `extensionRaw`/`extension`/`mediaPreview`/`mediaPreviewRaw`
   instead of one `previewRaw`/`preview` pair; `ContentReady` exposes
   `extensionRaw`/`mediaPreviewRaw`/`mediaPayloadRaw`). `data/signing/
   SigningIdentity.kt`/`SignatureVerifier.kt`'s Content signing/
   verification calls were updated to the new 3-arg hash function; Paper
   signing is untouched. Caller fixes: `ReceiveActivity.kt` (the
   `contentSignedMessageHash` call sites and the "Inspect CBOR" debug
   dialog's identity lookup), `WriteNfcTagActivity.kt` (a
   `previewRaw`→`extensionRaw` rename in its own two-pass NFC capacity
   probe). Verified the same way as the v8 port, using the *same*
   standalone `kotlinc` 2.0.21 + JUnit4 jars already cached under `/tmp`
   from that earlier verification pass (this environment resets its
   filesystem between sessions, but reused the identical fetch this
   time round): the full `data/format`+`data/signing` package set
   compiles clean, and both `TagDropCodecTest.kt` (Content-side cases
   rewritten for the new field names/3-arg hash; two hand-tampered-CBOR
   tests — even/odd key criticality, `group_id` mismatch — rewritten for
   array-wrapped framing) and `SectorAssemblerTest.kt` (its own
   hand-built-CBOR helpers rewritten from bare-prefix Content-Preview/
   Content-Body framing to array-wrapped Content
   Extension/Media-Preview/Media-Payload/Split framing, since that file
   deliberately builds wire bytes by hand rather than through
   `TagDropCodec`'s own encoders, to isolate `SectorAssembler`'s
   reassembly logic from the codec's encode-side field layout) pass
   green — 123 tests total across `TagDropCodecTest`/
   `SectorAssemblerTest`/`MiniCborTest` combined, including real
   ML-DSA-44 sign/verify against the new 3-Record hash formula.
   `tools/test-qdef-roundtrip.mjs` (the separate, CI-gated Node port of
   this same wire format, unrelated to the Kotlin app itself but ported
   in the same pass) was also updated to v9's array-wrapped-Record/
   Content-Extension-Media-Preview-Media-Payload-Content-Signature shape
   — same `decodeArrayRecord`/`encodeArrayRecord` design as the other
   three JS tools, `qdef-fixtures.json` regenerated — all 10 of its own
   vectors plus `test-qr-roundtrip.mjs`'s 14 (unaffected, already on v9
   from the earlier JS port) pass. The still-outstanding gap is a full
   Android Studio build (Room codegen, resource linking, the 15 caller
   Activities/Fragments) for the Kotlin side specifically, same Gradle-
   version limitation noted above.
2. **Browser JS** — inline `<script>` in `tools/generator/index.html` and
   `tools/examples/index.html` (encode side), `tools/reader/index.html`
   (decode side). SHA-256 via `crypto.subtle`, DEFLATE via
   `CompressionStream`/`DecompressionStream`. Both Content and Paper
   encoding/decoding have been **ported to version 2** QDEF Records
   (Preview/Body split per payload type, Compress Wrapper, Split Wrapper) —
   Content-Preview/Content-Body and Paper-Preview/Paper-Body are four
   independent Record Type IDs, each with its own field-key namespace
   (SPEC.md §2.1/§3). No wire format left on the old four-item envelope in
   any of the three web tools.

When SPEC.md changes, **both** implementations need updating and
re-verifying, or they silently drift apart. There's still no *automated,
committed* cross-check between the two Kotlin/JS implementations (that
remains manual — decode a real generator-produced code by hand and check
fields match) — but the JS side's own internal cross-tool consistency
(generator's output actually being readable by reader) is no longer purely
manual: `tools/test-qr-roundtrip.mjs` and `tools/test-qdef-roundtrip.mjs`
are both CI-gated (`.github/workflows/ci.yml`), and this project's own
verification practice throughout the QDEF port was to drive the *actual*
generator and reader pages in a real headless browser (Playwright) and
assert on the reader's resulting IndexedDB/UI state, not just compare two
independent codec copies' output bytes — catching several real bugs
(a CBOR double-wrap, a lost verification result, a routing bug for
multi-code Papers) that self-consistent unit tests alone missed. Those
Playwright scripts were run ad hoc from a scratch directory, not committed
to the repo — worth formalizing into a real `tools/test-*` script if this
kind of regression recurs.

The web tools (generator + reader) do real ML-DSA-44 sign/verify via
`@noble/post-quantum` (dynamically imported from a CDN, same pattern as
qrcode/jsPDF/marked/zxing-wasm — no bundled dependency, still a single
self-contained file). The generator's signing identity (keypair + label)
lives in that browser's `localStorage` only, generated on first use —
`exportSigningIdentity()`/`importSigningIdentity()` let it be backed up as a
passphrase-protected JSON file (PBKDF2 + AES-256-GCM over just the secret
key; `publicKey`/`signerId`/`label` are stored in the clear, since they're
not secret) and moved to another browser/computer, since `localStorage`
alone doesn't survive that move (a fresh identity, with a different
`signer_id`, would otherwise be generated there instead — breaking TOFU
continuity with anyone who'd already cached the old one). The Kotlin app has
the same export/import capability: `data/signing/SigningIdentityBackup.kt`'s
`exportSigningIdentity()`/`importSigningIdentity()` use the identical JSON
shape (same field names/hex encoding) as the web generator's, so a backup
made in either implementation can in principle be read by the other; wired
into `CreateActivity` via two buttons shown alongside the sign checkbox
(SAF `CreateDocument`/`OpenDocument`, a passphrase `AlertDialog`, and a
confirm dialog before overwriting a *different* existing identity). The Kotlin
app now also does real ML-DSA-44 sign/verify,
via BouncyCastle (`bcprov-jdk18on`, `org.bouncycastle.pqc.crypto.mldsa` —
`data/signing/MLDSA44.kt`), so this is no longer an asymmetry between the two
implementations. `data/signing/SigningIdentity.kt` persists the local signing
keypair in an `EncryptedSharedPreferences` file (Keystore-wrapped) and builds
signed sectors; `data/signing/SignatureVerifier.kt` mirrors the JS reader's
`verifySignature()`, caching first-seen `signer_pubkey`s in the
`trusted_signers` Room table (`TrustedSigner`/`SignerDao`, TOFU). Both mirror
the same `signSectors`/`verifySignature` design: signing must build with a
same-length **placeholder** signature first, not build unsigned and signed
independently — adding ~3.7 KB of signature fields can itself push a payload
from single- to multi-sector, and `content_sha256`/`bulky_meta_sha256`'s
presence *and value* must stay identical whether or not signing happens
(SPEC §10 "signing happens last and feeds back into nothing") — this exact
class of bug (a field's value silently changing between an independently-built
"unsigned" pass and the real signed build) was caught three times during
development, once for each of `root_hash`/`content_sha256`-triggering-resplit/
`bulky_meta_sha256`, only by actually running real ML-DSA-44 sign→verify round
trips end to end, not by code review alone — true again for the Kotlin port,
verified via a standalone `kotlinc`+JUnit harness (this environment's Gradle
wrapper can't download its own distribution, so Gradle-based `./gradlew test`
hasn't been run here; a full Android Studio build is the remaining
verification step).

UI wiring: `CreateActivity`'s "Sign with Verified Authorship" checkbox signs
single-code Content payloads; `ReceiveActivity` verifies every scanned
Content/Paper's signature and persists the result
(`signatureStatus`/`signerIdHex`/`signerLabel` on `FoundCache`/`ScannedPaper`);
`CollectionDetailAdapter`/`item_page.xml` show a ✅/⚠️/🔏 badge for
verified/invalid/pending. The web generator now has a "Sign with ML-DSA-44"
checkbox on **both** tabs — Single File and Paper Layout — sharing the same
browser-local signing identity (`signPaperSectors`/`paperSignedMessageHash`
mirror `signContentSectors`/`contentSignedMessageHash`'s placeholder-then-
strip discipline). `CreatePaperActivity` is the one place that still lacks
a signing checkbox — per "web generator first" above, the web tool getting
it first was the intended order, and the Android app is the remaining
follow-up.

### Split/Compress Wrapper field-key bug (SPEC.md version 10)

A re-read of the vendored QDEF-SPEC.md (prompted by a routine "have a read
of the latest spec" check, not by any wire-shape change upstream) surfaced
a real, long-standing compliance bug: both implementations' Split (Type 2)
and Compress (Type 8) Wrapper Records had every field key offset by +2
from QDEF-SPEC.md's actual defined numbering —
`group_id`/`index`/`count`/`data`/`total_bytes`/`parity_scheme` encoded at
`2`/`4`/`6`/`8`/`9`/`11` instead of the correct `0`/`2`/`4`/`6`/`7`/`9`,
and Compress's payload at `2` instead of `0`. `git log -p --follow --
QDEF-SPEC.md` confirms the correct numbering was already present the very
first time the file was vendored (`6bf5340`) — this was never a spec
change landing after the fact, just a divergence between spec-as-vendored
and code-as-shipped that had gone unnoticed since Wrapper Records were
first adopted. SPEC.md's own inline example (§3.1a) already showed the
correct numbering throughout — only the actual codecs had drifted.
Fixed everywhere the wrong numbering appeared: `TagDropCodec.kt`'s
`SK_*`/`CK_PAYLOAD` constants; `tools/reader/index.html`'s `SK`/`CK`
objects; `tools/generator/index.html` and `tools/examples/index.html`'s
`compressWrap`/`splitFragments` literals plus their `RECORD_TYPE_INFO`
keyNames tables (both files also had a stale `Type 3` in the Compress
Wrapper's doc comment — should've read `Type 8` since that earlier
renumbering, SPEC.md version 8's history entry — fixed alongside); and
`tools/test-qdef-roundtrip.mjs`'s `compressWrap`/`splitFragments`/
`reassembleSplit`/the tamper-detection test's hand-built fragment, with
`qdef-fixtures.json` regenerated afterward. Two Kotlin test-only helpers
needed the same fix since they hand-build wire bytes rather than going
through `TagDropCodec`'s own encoders: `SectorAssemblerTest.kt`'s
`splitFragmentBytes`/`compressWrapBytes`, and `TagDropCodecTest.kt`'s
`multiCodeGroupIdMismatchIsHashMismatch` tampered-fragment literal.

One file was deliberately **left on the old +2 numbering**:
`tools/test-qr-roundtrip.mjs`. Its own `cborRecord()` helper (unlike the
other three JS tools') is not actually on the real QDEF array-wrapped
`[typeId, map, subrecords]` grammar — it flattens `typeId` into the same
CBOR map as the Record's fields, reserved at key `0`, a leftover from
before this project's array-wrap port that this file was apparently never
migrated off of. That reservation collides with the *correct* Split/
Compress key `0` (`group_id`/Compress payload) — attempting the same fix
here breaks this file's own self-consistent round trip (`typeId` and
`group_id`/payload both trying to occupy key `0` of the same map). Rather
than expand this fix into a second, larger port (this file's
Content-Preview field layout also still reflects the pre-version-9 flat
shape, a separate, deeper gap from the Split/Compress key numbering this
pass was scoped to), the key constants here were deliberately reverted to
the old `2`/`4`/`6`/`8`/`9`/`11`/`2` numbering with a comment explaining
why, keeping this CI-gated test file internally consistent and green
without either silently mis-testing the corrected wire format or
expanding scope mid-fix. Revisit if/when this file gets its own pass onto
the real array-wrapped Record grammar and current Content-Preview/Body
shape — at that point it should adopt the corrected Split/Compress
numbering too.

### QDEF grammar update: payload slot, Bundle Type 0 (no TagDrop change needed)

A message purporting to relay upstream QDEF changes (an optional bare
`payload` slot replacing the old `ndefId` position, and a new structural
Bundle Type 0) arrived mid-session and was initially treated as
unverifiable/likely-injected, since it didn't match this repo's vendored
QDEF-SPEC.md and referenced `docs/DESIGN.md`/`FINDINGS.md` in a repo not
in scope for that session. That assessment was **wrong** — live-fetched
directly from `github.com/mofosyne/qdef`'s current `main` branch
(`docs/QDEF-SPEC.md`) to check: both mechanisms are real and current.
The vendored copy was simply stale (confirmed separately by
[#67](https://github.com/mofosyne/tagdrop/issues/67), which describes an
earlier trim-and-cleanup pass this repo's copy also never caught up to)
— exactly the drift problem the QDEF-SPEC.md → pointer change (above)
now removes going forward. Corrected grammar noted in SPEC.md §2's
opening paragraph: `[namespace?, typeId, map?, payload?, subrecord*]`
(was `[namespace?, typeId, ndefId?, map, subrecord*]`).

**Offline in-app spec viewer:** `SpecActivity`'s QDEF pane needs real
content, not just a pointer, to stay useful offline (the app's whole
"no computer needed" ethos) — but a hand-maintained full copy is exactly
the drift problem the pointer just fixed. Resolved with
`scripts/sync-qdef-spec.sh`, a manually-run script (not CI, not a
build-time fetch — deliberately, so a bad upstream fetch or an
unreviewed upstream change never lands unattended) that mirrors
`mofosyne/qdef`'s current `docs/QDEF-SPEC.md` into a committed
`QDEF-SPEC-cached.md`, printing a diff against the last committed copy so
whoever runs it can review before committing. `app/build.gradle`'s
`copyQdefSpecToRawRes` task now sources from that cache file instead of
the pointer (falling back to the pointer if the cache is ever missing,
so a fresh checkout without it still builds) — keeps the actual
`./gradlew build` fully offline/hermetic, unlike a build-time fetch
would've been.

Assessed whether either new mechanism changes anything for TagDrop:
**no action needed on either**, at first pass. The bare `payload` slot
looked like a byte-cost optimization for Records that carry exactly one
untyped value — none of TagDrop's four Record Types looked like they fit
that shape (Content Extension and Paper-Preview/Body always carry several
optional fields; Media Payload's `{mediaType, content}` looked like two
fields, not zero). Bundle (Type 0, `[0, [subrecord*]]`, a structural
grouping wrapper with no transform semantics) genuinely has no TagDrop use
case — that conclusion held. The payload-slot conclusion didn't survive a
closer look — see the next entry.

### QDEF payload slot adopted for Compress Wrapper + Media Payload (SPEC.md version 11)

Revisited the "no action needed" call above after actually asking: Media
Payload's `content` field *is* exactly the single-value case the payload
slot exists for — `mediaType` is metadata about the payload, not a second
peer value. Compress Wrapper's `{0: compressed_payload}` map fits even
more cleanly (nothing else in it, ever). Both adopted: Compress Wrapper
becomes `[8, deflated_bytes]` (its map disappears entirely — one fewer
CBOR item, one fewer header byte); Media Payload becomes `[6, {mediaType},
content_bytes]`.

Adopting this exposed a real design question, resolved through a relayed
exchange with qdef bot rather than assumed: QDEF's payload shape rule
went through two more revisions after the version this repo had last
synced. First landed as "any well-formed CBOR item, including an
array-shaped nested Record" — which required a new mandatory-null
placeholder rule (an encoder MUST emit an explicit CBOR `null` in the
payload position whenever a Record has subrecords but no real payload of
its own), since once payload can be array-shaped, a decoder can no longer
tell "this array is the payload" from "this array is subrecord 0" by
major type alone. That rule would have forced a backward-incompatible
re-encode of TagDrop's own Media Preview (nests Media Payload, no payload
of its own) and Split Wrapper (nests Media Preview) — both had subrecords
without a payload under the old grammar, which was fine under pure
type-sniffing but broken under the new rule without adding the marker.
Flagged this cost back upstream along with a structural argument (an
array-shaped payload is byte-for-byte identical to a subrecord — same
`[typeId, map, ...]` shape, same recursive decode — so it never bought
anything a first subrecord didn't already give, just under a different
name at a different array position) and it landed: payload permanently
excludes arrays again, the mandatory-null rule is gone with it, and an
array right after the map/typeId is unconditionally "subrecords start
here," no marker needed. Net effect for TagDrop: **Media Preview and
Split Wrapper need no changes at all** — their existing shape was already
correct under the reverted rule — only Compress Wrapper and Media Payload
change, and only because they *choose* to use the payload slot, not
because anything requires them to.

The same exchange also confirmed and extended QDEF's Common Field Keys
registry (§3.6, negative integer keys, always odd/optional, usable on any
Record) with two more entries backed by real TagDrop fields: `-13 Source`
(matching `source_url`, independently duplicated across Content Extension
and Paper-Preview before this) and `-15 Filename` (matching Media
Preview's `filename` — and, it turned out, QDEF's own Media Preview
Type's `filename` field independently, a stronger validation signal than
either adopter alone). Combined with the already-existing `-11 Content
Hash` match (Media Preview's `contentHash`) and `-7 Label` match (Media
Preview's `label`), TagDrop migrates four fields off Type-specific keys
onto the shared registry — a pure key-location change, no value-shape
difference. A fifth candidate, a `Reference`/`In-Reply-To` key for
TagDrop's own `in_reply_to`, was proposed and declined: TagDrop's field is
a deliberately truncated, unauthenticated 8-byte pointer (SPEC.md §7),
weaker than the full-multihash strength a shared key for this concept
would need, and would ship with zero real adopters at its stronger shape
— the same pattern the array-shaped-payload revert had just been fixed
for, so not repeated here on purpose.

**New primitive work, not just a renumbering.** Negative CBOR map keys
(major type 1, RFC 8949 §3.1: argument `-(n+1)`) had never been emitted or
parsed anywhere in either codec before this — `MiniCbor.kt`'s
`encodeMap`/`readValue` and every JS tool's `cborFieldMap`/`decodeItem`
equivalent needed genuine new encode/decode branches, not just changed
key constants. Payload-slot support needed the same treatment: neither
`MiniCbor.encodeRecord`/`decodeRecordPrefix` nor any JS tool's
`cborRecord`/`decodeRecordPrefix`/`decodeArrayRecord` had ever modeled a
payload item at all (only `[typeId, map, subrecord*]`) — all four
implementations gained a shared `layoutOf`-style helper (dispatch by CBOR
major type: map first if present, then payload if the next item isn't an
array, then subrecords) used consistently by encode, decode, and the
`stripKeys`/`stripSubrecordType`/`stripAllSubrecords` byte-surgery
functions signing/hashing depends on — `stripSubrecordType`, called on
Media Payload to strip its Content Signature subrecord before hashing,
would otherwise have crashed trying to interpret the new payload-slot
bytes as a malformed subrecord array.

Two real bugs caught during implementation, both by running the actual
test suites rather than by inspection alone:
1. **Map-omission was value-dependent, not declaration-dependent, on
   first pass.** `encodeRecord`'s "omit the map when there's nothing to
   put in it" logic initially checked whether every *value* happened to
   be null — which silently omits Content Extension's map whenever a
   test (or a real minimal payload) leaves every optional field unset,
   breaking `stripKeys` (which requires a map to exist) for any Type that
   always *declares* fields but doesn't always *set* them. Fixed to check
   whether the `fields` list itself is empty (a static, per-call-site
   choice — true only for Compress Wrapper, which now passes `emptyList()`
   /`{}` explicitly) rather than whether every value is currently unset.
2. **A "fix" to `TagDropCodec.kt`'s Media Preview subrecord lookup — made
   earlier in this same session, framed as replacing fragile positional
   access (`subrecords[0]`) with type-search (`find { it.typeId ==
   TYPE_MEDIA_PAYLOAD }`) — was itself wrong**, caught only by
   `SectorAssemblerTest.kt`'s `failedStateWhenPayloadSubrecordTypeIsWrong`
   regressing. Media Preview's one subrecord is Media Payload *or* a
   Compress Wrapper around it, depending on whether the payload was
   compressed — searching for `TYPE_MEDIA_PAYLOAD` specifically silently
   broke the compressed case, and even fixed to search for either type,
   it changed *when* a malformed subrecord gets rejected (at scan time
   instead of deferred to unwrap time), contradicting a deliberately
   lazy validation design SPEC §5.1 documents ("only the field map and
   subrecord count are checked at scan time"). Reverted to the original
   positional access, since the existing `subrecords.size != 1` check
   already makes position 0 unambiguous — there was no real bug there to
   begin with, just an incomplete read of why the original code was
   shaped that way.

Verified via the same two-track discipline as the Split/Compress fix:
`MiniCborTest.kt` gained direct unit coverage for the new primitives
(payload-slot round trip with/without a map, map-omission-only-when-
fields-empty, negative-key encode/decode, wire-order sorting,
`stripKeys`/`stripSubrecordType`/`stripAllSubrecords` payload-slot
preservation) before touching `TagDropCodec.kt` at all, then the full
existing suite (134 real tests across `TagDropCodecTest`/
`SectorAssemblerTest`/`MiniCborTest`, plus the pre-existing 2 unrelated
`ByteArray`-reference-equality failures from the byte-mode-framing
feature, untouched by this change) confirmed nothing else regressed.
`tools/test-qdef-roundtrip.mjs`'s 10 vectors and `tools/test-qr-
roundtrip.mjs`'s 14 (deliberately left on `test-qr-roundtrip.mjs`'s own
pre-array-wrap grammar, per the version-10 entry above — still
self-consistent, still passing, still not exercising the real current
wire format) both pass; `qdef-fixtures.json` regenerated.

### Canonical CBOR key-ordering bug (SPEC.md version 12)

Self-discovered, not reported upstream or by a user: a close read of
QDEF-SPEC.md §3.4 (done while verifying an unrelated "Root Unification"
claim, below) turned up that every implementation was sorting Record
field-map keys by plain ascending integer value, not RFC 8949 §4.2.1's
actual core deterministic-encoding rule — compare each key's own
*encoded* bytes, shorter first, then bytewise. The two rules are silently
identical as long as every key on a Record shares one CBOR major type,
which was true of every TagDrop key through version 10 (all Type-specific
keys are non-negative uints, major type 0, where a bigger integer always
encodes to more-or-equal bytes — integer order and byte-length order
coincide). Version 11's negative Common Field Keys (major type 1) broke
that coincidence: a negative key's encoded byte is always numerically
*larger* than a same-magnitude non-negative key's (`-11` encodes to
`0x2a`, `0` encodes to `0x00`), so canonical order actually puts `0`
*before* `-11` on the wire — the reverse of what version 11's own history
entry claimed at the time ("a negative Common Field Key always sorts
before any Type-specific non-negative key"). That claim was corrected in
place (SPEC.md's version-11 entry now has an explicit note pointing at
version 12) rather than silently rewritten, matching this project's
practice elsewhere of leaving a visible trail when an earlier documented
belief turns out to be wrong (see the FINDINGS.md #51 self-correction,
CLAUDE.md's own session history).

Fixed in `MiniCbor.kt` (a new `CANONICAL_KEY_BYTES_ORDER: Comparator<ByteArray>`
used by `encodeRecord`'s sort call, replacing `sortedBy { it.first }`) and
the three JS files that actually emit negative keys —
`tools/generator/index.html`, `tools/examples/index.html` (byte-identical
`cborFieldMap`/`keyBytes`/`compareKeyBytes` in both, per the "known
duplication" note below), and `tools/test-qdef-roundtrip.mjs`'s
`encodeRecord`. **`tools/reader/index.html` needed no change** — it only
ever decodes, never encodes, negative keys. **`tools/test-qr-
roundtrip.mjs` also needed no change** — per the version-10 entry above,
this file was deliberately never migrated to array-wrapped Records or
Common Field Keys at all, so it has no negative-key support to begin
with; its existing integer-ascending sort is (and remains) correct,
since every key it ever emits is major type 0.

`MiniCborTest.kt`'s `negativeKeysSortBeforeNonNegativeKeysOnTheWire` test
asserted the old (wrong) order and was rewritten —
`fieldsSortByCanonicalEncodedKeyBytesOnTheWire` — to assert the corrected
one. Verified via the same standalone `kotlinc`+JUnit harness used
throughout this port (155/157 tests green; the 2 failures are the same
pre-existing, unrelated `ByteArray`-reference-equality issue noted
elsewhere in this file, untouched by this change) and both JS test
scripts (`test-qdef-roundtrip.mjs`'s 10 vectors, `test-qr-roundtrip.mjs`'s
14, both passing); `qdef-fixtures.json` regenerated.

### Self-delimited root array replaces the bare Record Sequence (SPEC.md version 13)

Started from a user question — "with the new spec saying root is a
record, is it possible for you to simplify your parser?" — prompted by
QDEF's "Root Unification" (root parsed with the same grammar as a
Record, end-of-buffer-bounded instead of array-bounded). First attempt
at the simplification was wrong and reverted same-session: SPEC.md §9's
deniability feature requires decoders to stop after the Records they
expect and tolerate anything beyond as untouched, possibly-independent
bytes — a generic "decode records until end of buffer, fail on any
error" walker breaks that outright. Root Unification's own root-as-
Record framing doesn't actually require a decoder to walk that far
either — a decoder is always free to stop early — so at that point there
was no live bug, just a documented near-miss.

The real fix came from a different angle: proposed adding an explicit
"end of record" marker to QDEF (reusing CBOR's break code) so a
*generic* decoder without app-specific record-count knowledge could find
the same boundary TagDrop's own bounded decoder already knew how to
find. Relayed to qdef bot, whose answer landed differently than
proposed but solved the same problem better: QDEF's spec had already
moved to wrapping the root in an ordinary definite-length CBOR array —
literally the same self-delimiting shape a subrecord already uses — on
every carrier, including `tagdrop:`-style own-URI-scheme and NDEF
carriers, not just the magic-header one "Root Unification" covered.
Verified directly against the live `QDEF-SPEC.md`/`DESIGN.md`/
`prototype/scripts/qdef-lint.js` before touching any code (not trusted
on the relay's word alone, per this project's established practice) —
confirmed real, and confirmed it does change TagDrop's own wire bytes on
carriers already shipping, unlike Root Unification itself.

**The change:** a code carrying a single top-level Record (a key-only
code) is unaffected — that Record's own array already *is* the root, "no
Bundle indirection." A code carrying two top-level Records (the common
case for both Content and Paper) now wraps them in one more
definite-length CBOR array — the two subrecords of an implied,
never-transmitted Bundle (typeId `0`, omitted) — costing exactly 1 byte
per code versus the previous bare RFC 8742 concatenation. `MiniCbor.kt`
gained `encodeRootBundle`/`decodeRootBundle`, mirroring the existing
`encodeRecord`/`decodeRecordPrefix` primitives; all four JS tools gained
matching functions (`tools/reader/index.html` decode-only, matching its
decode-only role; the other three both directions). Every encode-side
site concatenating a Preview/Extension with a second Record — Content's
single-code and Split-fragment cases, Paper's equivalent pair, in both
`TagDropCodec.kt` and the three encoding-capable JS tools — now goes
through the new wrapping function instead of raw concatenation.
Decode-side `recordScanResult`/`contentScanResult`/`paperScanResult`
(Kotlin) and their JS mirrors were rewritten around
`decodeRootBundle`, recovering the exact simplification originally
asked about — now safe, since `decodeRootBundle`'s self-delimiting is
structural (bounded by the array's own header) rather than the
generic-walker approach that broke SPEC §9 the first time around.

Two debug-only reconstruction sites needed the same fix to keep working
at all, not just to stay byte-accurate: `ReceiveActivity.kt`'s "Inspect
CBOR" fallback (reconstructs wire bytes when the original scan bytes
aren't retained) and `SectorAssembler.kt`'s stored Paper `streamBytes`
(`ScannedPaper.cborBytes`, later re-decoded by `TagDropCodec.
decodePaperStream` — confirmed by tracing every caller, not assumed,
since this one persists to Room and getting it wrong would have broken
existing-session Paper browsing silently). More subtly, the CBOR debug
pretty-printers (`TagDropCodec.describeCbor`'s Record-walking loop, and
all three JS tools' `describeRecords`) would have silently shown *zero*
Records for any real code under the new format — they used to walk
`decodeRecordPrefix`+`trailing` repeatedly, which assumed a bare
concatenated sequence; calling that directly on a root-Bundle-wrapped
code misreads the wrapping array's own header as if it were a
Record's, and fails immediately. Fixed by trying the strict
`decodeRootBundle` first and falling through to the old best-effort
`decodeRecordPrefix`/`trailing` walk for whatever's left afterward
(SPEC §9 trailing bytes, or genuinely malformed/legacy input) — keeping
the debug view's existing lenient, never-fully-blank behavior on bad
input while fixing it for every real code. One non-obvious byte-math
fix, caught by tracing every site doing manual `Raw + Raw`-style
concatenation rather than assuming the list above was exhaustive:
`WriteNfcTagActivity.kt`'s NFC capacity-probe estimate
(`codes.first().size - extensionRaw.size`) needed one more byte
subtracted for the new wrapping array header — an estimate feeding a
retry loop that always re-measures the real rebuilt code, so not
correctness-critical, but silently off by one otherwise.

**New verification tool adopted, not just written:** qdef bot mentioned
a "validator" in passing; turned out to be `prototype/scripts/
qdef-lint.js`, a standalone, dependency-free grammar-and-footgun checker
qdef ships, written from scratch against the spec with no shared code
with any TagDrop encoder — a genuinely independent second check, not
just another self-consistency test. Vendored unmodified as
`tools/qdef-lint.cjs` (`.cjs` since `tools/package.json` is
`"type": "module"` but the vendored file is CommonJS), wired up via
`tools/verify-qdef-lint.mjs` (`npm run lint:qdef`, gated in CI
alongside `test`/`test:qdef`) — lints every code in `qdef-fixtures.json`
with `--no-magic` (none of TagDrop's shipping carriers use QDEF's magic
header). All 15 codes across the 10 fixtures lint clean: 0 errors, 0
warnings. Worth noting for its own sake: this tool's canonical-map-
key-order check would have caught the SPEC.md v12 key-ordering bug
directly, had it existed at the time — a concrete demonstration of why
an independently-written checker catches a different class of mistake
than a codebase's own self-consistent test suite.

Verified via the same standalone `kotlinc`+JUnit harness used throughout
this port (162/164 tests green; the 2 failures are the same
pre-existing, unrelated `ByteArray`-reference-equality issue noted
elsewhere in this file, untouched by this change), both JS test scripts
(`test-qdef-roundtrip.mjs`'s 10 vectors, `test-qr-roundtrip.mjs`'s 14 —
deliberately unaffected, per the version-10 entry's reasoning, since it
was never migrated to array-wrapped Records), and the new
`qdef-lint.cjs` pass; `qdef-fixtures.json` regenerated.

**Separately found, real, severe pre-existing bug — none of the above
verification would have caught it:** `test-qdef-roundtrip.mjs` and
`qdef-lint.cjs` both exercise only *one* codec's own byte output against
itself (or against a spec-level grammar checker) — neither actually
feeds the web generator's real output into the web reader's real decoder.
Doing exactly that (a from-scratch Node `vm`-based cross-tool check,
loading `generator/index.html` and `reader/index.html`'s actual script
bodies and calling `createContentSectors`/`recordScanResult` directly)
surfaced a real, severe, and entirely unrelated bug: **all three web
tools' generic CBOR value decoder (`readVal`, inside
`cborDecodeSequencePrefix`) never gained a `case 1` for negative
integers when SPEC.md v11 introduced negative Common Field Keys.** Its
`switch (major)` fell through to `default: throw`, so any Record whose
field map contained a negative key — which includes Media Preview's
`contentHash`/`filename`/`label` on essentially *every* real Content
code, and `source_url` wherever set — failed to decode via
`decodeRecordPrefix`'s map-decoding step. Since `decodeRecordPrefix` is
what `recordScanResult` (the reader's actual scan entry point) depends
on, this meant the reader has been unable to correctly scan a normal
Content code's Media Preview since v11 shipped — not a hypothetical or
edge case, the mainline path. The byte-range-aware primitives
(`cborItemRanges`/`cborReadInt`/`layoutOf`, used by `stripKeys`/etc.)
were correctly updated for negative keys back in v11; only this one
generic recursive decoder was missed, and nothing in the existing test
suites exercises it against reader-produced output (the reader has no
committed automated tests of its own at all — this project's own
"Playwright scripts run ad hoc, not committed" gap, noted earlier in
this file, is exactly the coverage hole this fell through). Fixed
identically in all three files (`generator/index.html`,
`examples/index.html`, `reader/index.html`) by adding a `case 1`
mirroring `case 0`'s existing structure (including its same
8-byte-argument/BigInt-overflow handling, negated per RFC 8949 §3.1:
value = `-(a+1)`). Verified via direct `decodeRecordPrefix` calls against
a hand-built Media-Preview-shaped byte string in all three files
post-fix (all three now correctly decode `-11`/`-15`); the full
cross-tool generator→reader round trip (Content single-code, Content
Split multi-code, Paper) was also confirmed working end-to-end during
debugging, though that specific Node `vm` harness (needing DOM stubs to
tolerate each file's UI-initialization code) wasn't kept as a committed
script — a real headless-browser (Playwright) version of this exact
check would be a good candidate for the "worth formalizing" gap this
file's "Why the reader uses zxing-wasm" section area already flags.

### Known duplication (not yet deduped)

`tools/generator/index.html`'s codec helpers — Base41 (`base41Encode`/
`base41Decode`), CBOR (`writeHead`/`cborValue`/`cborMap`/`cborArrayBytes`/
`cborDecodeSequencePrefix`/etc.), QDEF Record builders
(`cborFieldMap`/`cborRecord`/`compressWrap`/`splitFragments`/
`buildContentPreview`/`buildContentBody`/`encodeCode`/`stripKeys`/
`contentSignedMessageHash`/`paperSignedMessageHash`), crypto
(`encryptAesGcm`/`encryptOverrideMap`/`deriveKeyFromPassphrase`/
`generateKeyMaterial`), and the sector builders
(`createContentSectors`/`createContentSectorsAutoSized`/
`createKeyCodeSector`/`createPaper`/
`createPaperAutoSized`) are byte-identical (same names, same bodies) to the
copies inlined in `tools/examples/index.html`, which adds only its own
example-data constants and rendering (`addCard`/`addContentSectorCards`/
`addPaperSectorCards`) on top. Signing helpers (`signContentSectors`/
`signPaperSectors`) are **generator-only** — examples has no signing
support. `tools/reader/index.html` has the decode-side mirror
(`base41Decode`, `cborDecodeSequencePrefix`, `RecordAssembler`,
`SectorAssembler`, etc.) plus its own UI/IndexedDB persistence layer. Both
generator and examples have their old-format (SPEC v1) sector-framing
encoders removed entirely now that Content and Paper are both on QDEF
Records — only the reader still carries an old-format decode path, kept
deliberately for backward compatibility with codes scanned before the port
(see "Two parallel wire-format implementations" above).

This is **browser-vs-browser** duplication (same APIs, same runtime), which
is lower-risk than the old Node-vs-browser split (`generate.mjs` was
removed — see below). A shared module would reduce drift further, **but**
note: `tools/generator/index.html`, `tools/reader/index.html`, and
`tools/examples/index.html` are deliberately **self-contained single HTML
files** (say so in their own header comments) — the only external dependency
is a CDN script for QR rendering (`qrcode`) and scanning (`zxing-wasm`), not
the codec logic. Splitting the codec into an importable `tools/shared/*.mjs`
would break that "download one file, it just works offline" property unless
paired with a build step that inlines it back into the HTML (extra tooling)
— not worth it for three files of this size. Instead there's a separate,
independent Node port for verification: `tools/test-qr-roundtrip.mjs`
(`tools/package.json`) builds Content sector payloads (single- and
multi-sector) and renders them as real QR images (`qrcode`), decodes them
back via zxing-wasm, and asserts round-trip correctness — run locally with
`cd tools && npm install && npm test`, and gated in CI as its own job
(`.github/workflows/ci.yml`, `web-tools-roundtrip`) alongside the Gradle
unit tests.

`tools/reader/` is the one exception to "single file": it ships
`manifest.json`, `sw.js`, and `icon.png` alongside `index.html` (all copied
by `.github/workflows/pages.yml` into the deployed `reader/` directory) so
it works fully offline via a service worker — see the header comment in
`tools/reader/index.html` for why. Generator and Examples don't need this:
they're normally used at a desk with a network connection, unlike Reader,
which is the tool someone relies on out in the field (and, since there's no
iOS app, the *only* offline option for iPhone users).

### Why the reader uses zxing-wasm, not jsQR, to scan QR codes

`tools/reader/index.html` decodes camera/image QR scans with **zxing-wasm**
(a WebAssembly port of ZXing — the same decoder family the Android app uses
via the platform ZXing library). jsQR was the original choice and is much
smaller (no `.wasm` fetch), but has a confirmed, unfixed bug
([cozmo/jsQR#155](https://github.com/cozmo/jsQR/issues/155)): it fails to
detect **any** QR symbol — alphanumeric `tagdrop:` URI or binary/byte-mode
chunk — that happens to be encoded as **QR version 23** specifically (other
versions are unaffected). Confirmed by direct testing: alphanumeric URIs of
1455–1582 chars and byte-mode payloads of 1004–1091 bytes both land on
version 23 and were 100% undetectable by jsQR (and by `jsqr-es6`, its only
maintained fork) regardless of pixel scale or mask pattern, while zxing-wasm
decodes the identical images correctly. If jsQR is ever reintroduced (e.g.
to shrink the dependency), re-verify against this version-23 case first.

### `tools/examples/` is self-contained, not generated

`tools/examples/index.html` used to be generated by `tools/examples/generate.mjs`
(Node, `node:crypto`/`node:zlib`, npm build step) — that whole pipeline
(`generate.mjs`, `package.json`, `package-lock.json`) was removed. The page is
now hand-written and self-contained like the generator/reader: example
payloads are inlined as JS data and encoded/rendered as QR codes in-browser on
page load. Edit the example data directly in `index.html` and reload — no
build step.

## Wire-format version policy

SPEC.md's `version` field (currently `8` — QDEF Records with Type IDs
`1`/`3`/`5`/`7` under a fixed implied namespace, §14; both the Kotlin
app and the web tools are on this shape now, see "Two parallel
wire-format implementations" above) is independent of the Android app's
`versionName` (currently `2.1.0`, already accepted by F-Droid as of June
2026) — bumping one never requires bumping the other.

SPEC.md as a whole is currently a **draft, not frozen** (see its `Status`
line): no real TagDrop code has been printed or distributed yet, so no
deployed content depends on its exact byte layout at any version number
to date. F-Droid accepting the app only means a binary *could* eventually
reach a stranger — it doesn't mean any content exists yet for that binary
to misread. Breaking changes (key reuse, envelope changes, semantics
changes) are fine without a version bump **until the first real code is
deployed** — printed, shared, or otherwise placed somewhere a third party
might scan it. At that point treat SPEC.md's then-current version as
frozen: breaking changes from then on require a version bump (SPEC.md
§14), and SPEC.md's `Status` line should flip back to `Stable`.

## Authoring tools: web generator is primary

`tools/generator/index.html` (Single File + Paper Layout tabs) is the
intended primary tool for *creating* TagDrop codes and printable paper
layouts — it's faster to develop/iterate on (plain browser HTML/JS, no
build step) and has more screen space for the multi-file paper form than a
phone.

The Android app's in-app creation screens (`CreateActivity` = single code,
`CreatePaperActivity` = multi-file paper + print/PDF via the system print
dialog) are considered **secondary/optional** — useful for "no computer
available" scenarios, but
not the priority for new authoring features. New paper-layout features
should land in the web generator first; porting to the Android app is
optional follow-up.

## Active-content (`text/html`/`text/markdown`) containment

Scanned `text/html`/`text/markdown` content renders as live, script-executing
HTML on both platforms — full threat model and containment approach is in
SPEC.md ("Active content containment"). In short: sandboxed/no-JS-bridge
rendering already prevented scanned JS from reaching app storage or the
signing identity; the containment work in this session closed the remaining
gap, which was **silent network egress** (a scanned code could otherwise
phone home on every scan via `<img>`/`fetch`/nested `<iframe>`, even with the
existing sandbox/no-bridge protections) — fixed via a CSP injected into the
web reader's rendered `srcdoc` and `WebSettings.blockNetworkLoads` in
`ViewDataUriActivity.kt`. An explicit user-tapped link (as opposed to a
silent/automatic request) is deliberately still allowed, just handed off to
the real system browser (`Intent.ACTION_VIEW` / `window.open`) rather than
rendered in-place or blocked outright, with scheme validation
(`http`/`https` only) on the receiving end so a `javascript:`/`data:` URL
can't be smuggled through that handoff.

## Branch/remote notes

- The repo's default branch is `master`, not `main`.

## Future ideas / backlog (not yet implemented)

Ideas raised and assessed but deliberately deferred — revisit if they come up
again or a concrete need emerges.

- **QDEF App Route Record (auto-launch routing)** (assessed, not
  implemented). Raised with the qdef bot while designing TagDrop's Paper
  port: could a generic QR/NFC reader auto-launch the right app on scan,
  the way NFC's Android Application Record (AAR) does — without a routing
  table that grows as apps × types-per-app? Landed upstream as QDEF's own
  Type 7 (`App Route`, QDEF-SPEC.md §4.4): a domain-verified form (Android
  App Links / iOS Universal Links style, real anti-spoofing) and a
  decentralized form (a private-use random uint, no anti-spoofing — a
  cheap pre-filter only, never a substitute for `group_id`), plus a
  session-scoped `Companion ID` (key 5) that lets a domain-verified code
  vouch for a linked ID on other codes in the same scan. Byte cost is real
  and non-trivial for the decentralized form specifically — it must repeat
  on every code in a multi-code group (41 bytes/code with a Hint name, so
  7×41=287 bytes across a 7-code group) — while the domain form only needs
  a SHOULD-repeat. Deliberately **not implemented here**: Companion ID is
  explicitly session-scoped, not durable — exactly the wrong shape for
  TagDrop's actual use (physical, printed, scanned asynchronously by
  unrelated people/devices, potentially months apart) — and TagDrop
  already has the right tool for *that* trust shape, the durable
  `signer_pubkey` TOFU cache (SPEC §10), if auto-launch or domain-verified
  dispatch ever becomes a real need. Full design exchange (byte-cost
  verification, split-ratio math for structured private-use Type IDs, the
  private-use tier's "closed/internal" documentation bug this surfaced and
  got fixed upstream) lives in this session's conversation history, not
  duplicated here. **Update:** Companion ID (key 5) was later removed
  upstream entirely, not just deprecated — see the namespace-scoping entry
  below for why (the mechanism that replaced it does the job better). §4.4
  is back to its pre-Companion-ID shape; the rest of this entry (domain vs.
  decentralized form, byte costs, the reasoning for not adopting it here)
  still applies.
- **QDEF Type-ID namespace-scoping** (assessed with real numbers,
  declined — unlike App Route above, this one was finalized upstream *in
  direct response* to a TagDrop-initiated ask, and the cost question was
  actually answered, not just deferred). QDEF-SPEC.md §3.1 classifies a
  Record's Type ID by CBOR shape/parity: an even uint is a "Standard
  record type," always globally interpreted; an odd uint is a "Scoped
  record type," requiring a declared namespace, or it MUST be treated as
  an abort. Checking TagDrop's four Type IDs against this rule surfaced a
  real compliance gap — see SPEC.md §14's version-4 entry — fixed there by
  re-minting Paper's two odd IDs to even, matching Content's existing
  (already-compliant) pair. That fix deliberately does not adopt
  namespace-scoping itself. Whether to go further — shrinking all four
  Type IDs from 9-byte CBOR uints down to 1-byte small odd integers behind
  a declared namespace — got a real answer via a relay to qdef bot: a
  namespace declaration MUST repeat on every code of a multi-code Split
  group, the same reason
  Preview already does (each code is independently parsed, no shared
  state). Real numbers, checked against their encoder: shrinking a Type ID
  saves ~6 bytes; the per-code namespace-declaration overhead costs ~8
  bytes (the exact figure moved again with issue #66 below, but the shape
  of the trade-off didn't). Breakeven needs ≥2 shrunk IDs sharing a code.
  TagDrop's own Split design means Body's real Type ID never sits bare
  per-code (it's inside the Split-wrapped fragment, surfacing once only
  after reassembly) — so a multi-code group only ever has *one* bare
  namespace-scoped ID (Preview) per code, which is the losing case: **net
  −2 bytes/code, scaling with group size** (a 7-code Paper comes out ~14
  bytes worse). Single-code payloads (Preview+Body sharing a code) do net
  a small win (~+4 bytes), but not enough on its own to justify adopting
  the mechanism. **Declined** for this reason, not for lack of upstream
  support. **Update (SPEC.md version 8):** the per-code repetition cost
  this math was built on turned out to be avoidable, not fundamental —
  see the "own-URI-scheme Type ID isolation" entry below for the
  resolution (an *implied*, never-transmitted namespace on carriers with
  their own dispatch context). Namespace-scoping is adopted after all,
  just not the way this entry originally evaluated it.
- **QDEF mandatory container discriminator** (checked in, settled — see
  github.com/mofosyne/tagdrop#66). Landed after the namespace-scoping
  entry above: the previously-optional Type-0 namespace header became a
  mandatory 1-byte container discriminator on any carrier using QDEF's
  magic header (ambiguity fix — a decoder couldn't otherwise tell "this is
  the header" from "this is the next Record's own Type ID"). Cost to
  TagDrop: the discriminator only applies where the magic header does —
  TagDrop's byte-mode QR/JABCode carrier, not implemented in any codec yet
  — and both real TagDrop carriers (`tagdrop:` URI, NFC NDEF) are
  explicitly exempt, same as they already were from the magic header
  itself, since their own dispatch signal (scheme, MIME type) already does
  the discriminator's job. Net effect: byte-mode QR framing grows from 4
  to 5 bytes (SPEC.md version 5); zero cost anywhere TagDrop actually
  ships today. Also landed: a per-Record namespace-pairing override
  (`[namespace, typeId]`) for multiple namespaces in one container —
  TagDrop, using at most one namespace context (currently zero) per code,
  would never need this, but it costs nothing when unused. No SPEC.md
  action beyond the version-5 framing update; no reason found to reopen
  the namespace-scoping decision above.
- ~~**QDEF own-URI-scheme Type ID isolation**~~ — **done, superseded
  twice more** (SPEC.md versions 6 → 7 → 8, current state is 8). A
  genuinely different mechanism from the namespace-scoping entry above,
  not a reopening of it, at least at first — QDEF-SPEC.md §2/§3.5
  formalize that an app carrying QDEF content under its own URI scheme
  (`tagdrop:`, in TagDrop's case) already provides the collision
  isolation a declared namespace exists to buy, but only on carriers
  that actually have that external dispatch context.
  - **v6:** all four Type IDs re-minted from 64-bit CSPRNG values to
    small even ones (`48250`/`56990`/`34456`/`58984`), claiming zero
    offsetting cost *anywhere*.
  - **v7:** v6's "anywhere" was wrong — byte-mode QR/JABCode has no
    scheme or MIME type to isolate it, so it needs a declared namespace
    after all. Fixed by declaring one (`SHA-256("io.github.mofosyne.
    tagdrop")[0:4]` = `89d414e0`) on that one carrier specifically.
  - **v8 (current):** qdef bot pointed out the decoder can imply that
    same namespace value on `tagdrop:` URI and NFC NDEF too — never
    transmitted, just hard-coded as "content reaching me via my own
    carrier gets my namespace" — which means the four Type IDs no
    longer need to stay even at all. Re-minted a third time, to small
    **odd** sequential values (`1`/`3`/`5`/`7`) under that implied
    namespace: cheaper again (2 bytes/occurrence vs. 4) and a real
    correctness upgrade, not just smaller — an odd Type ID with no
    namespace present is a spec-mandated abort, so bytes that escaped
    into a context that didn't already know to imply TagDrop's
    namespace fail closed instead of being silently, wrongly accepted
    the way an always-global even ID would be. This is effectively the
    namespace-scoping entry above, adopted after all, once the "must
    repeat namespace per code" assumption its cost math was built on
    turned out not to hold for TagDrop's own two real carriers.
  - Version 6 also surfaced a real implementation bug, not just a spec
    update: the reader/generator/examples/test scripts declared these
    Type IDs as JS `BigInt` (needed when they were 64-bit, since they
    exceed `Number.MAX_SAFE_INTEGER`), but the CBOR decoder returns a
    plain `Number` for small values — `48250 !== 48250n` under strict
    equality silently broke every Type-ID comparison once the values
    shrank. Fixed by dropping the `BigInt` suffix (matching how QDEF's
    own stdlib Wrapper Type IDs were already declared) — verified via
    the full regression suite plus all Playwright cross-tool scripts.
  - Implementing v8 surfaced a second, unrelated real bug while
    checking for Type ID collisions: `TYPE_COMPRESS` was hard-coded as
    `3` in all five JS/test files, but QDEF-SPEC.md §4.1's actual
    current allocation for the Compress Wrapper is `8` (`TYPE_SPLIT`'s
    `2` was already correct). This had been silently, harmlessly wrong
    for a while — TagDrop's own Type IDs were never small enough to
    collide with it before — until v8's new `Content-Body = 3` would
    have collided with it directly. Fixed alongside v8's own Type ID
    changes, including the three matching `QDEF-SPEC.md §4.1 Type 3`
    references in SPEC.md itself (now `Type 8`).
  - Also resolves a Kotlin-port item: `MiniCbor.kt`'s 32-bit-uint
    limitation (SPEC.md §15) is no longer a gap for Type IDs
    specifically, since all four now fit in a single byte.
- ~~**Paper "homepage" via `index` slug convention**~~ — **done.** A file
  whose `slug` is `index`, `index.html`, or `index.md` is now highlighted as
  the paper's primary "Open" action: `tools/reader/index.html`'s
  `renderPaper()` shows a "🏠 Open homepage" button in the paper header (and
  a 🏠 badge on its row) when that file is cached; the Android
  `CollectionDetailActivity`/`CollectionDetailAdapter` shows the same 🏠
  badge on the matching `PageItem.PaperFile` row, via
  `TagDropLinkResolver.HOME_SLUGS`. Pure naming convention, no SPEC.md
  change.
- ~~**Ad-hoc collection homepage via `filename` convention**~~ — **done**
  (Android only). The same `HOME_SLUGS` convention now also applies to an
  ad-hoc `collection_id` group's members, keyed on `FoundCache.filename`
  (Content's nearest equivalent to a paper file's `slug`, since
  `collection_id` has no manifest/directory of its own):
  `CollectionDetailAdapter` shows the 🏠 badge on a `PageItem.CacheEntry`
  row whose `cache.filename` is in `HOME_SLUGS`, same as a `PaperFile`
  row. `tools/reader/index.html` has no equivalent — it has no ad-hoc
  collection browsing screen at all (only single-scan and Paper views), so
  there's nothing to mirror there yet.
- **Collection "default" via `collection_home` field** (assessed:
  *later, possibly never*). A boolean field (CBOR key TBD), set on one
  paper/cache within a `collection_id` group, marking it as that
  collection's entry point — analogous to `retain_key`. Would need a new
  permanent CBOR key in SPEC.md plus updates to both codec implementations
  (see above). The `filename`-based convention above may be sufficient;
  only add this heavier field if a real case shows up where users need to
  designate/re-designate a collection's home item explicitly (e.g. no file
  named `index`/etc., or multiple candidates needing disambiguation).
- **Analog/graffiti "find" logger** (assessed: *rejected as a SPEC.md
  feature*). The idea: TagDrop's dead-drop spirit naturally extends to
  fully analog drops — graffiti, a poster, a handwritten note on a wall —
  scanned via a photo + OCR instead of a QR code. This doesn't fit the
  `tagdrop:` wire format: every payload type assumes byte-exact,
  author-encoded content (content-addressed `cache_id`/`sha256`, §4.5),
  but OCR output from a photo is never byte-reproducible (lighting, angle,
  handwriting), so there's nothing to hash, dedupe, or verify, and no
  author-side encode step ever happened. If this is ever built, it should
  be a separate Android-app-only capture flow (photo + transcription + GPS,
  stored like a scanned cache but with no `cache_id`/`sha256` semantics),
  not a SPEC.md change.
- **Fiducial-frame analog content capture** (assessed: *plausible,
  long-term, not started*). A middle ground between a normal `tagdrop:`
  code and the free-form graffiti idea above: a printed frame around an
  analog photo/drawing, where a small standard `tagdrop:` QR in one corner
  carries identity/metadata (Content, `cache_id` random — same exception
  §9 already makes for encrypted override maps, since captured bytes
  aren't reproducible either — plus `hint`/`collection_id`/`icon`, with
  `content` deliberately omitted), and the frame's printed border is
  detected and perspective-corrected by the app (same "document scanner"
  contour-detection problem as Adobe Scan/CamScanner — no custom fiducial
  markers like ArUco/AprilTag should be needed) to crop the interior into
  a bitmap stored against that `cache_id`. Needs zero SPEC.md changes — the
  QR/CBOR/Base41 half is just an existing Content payload — but is a
  substantial net-new Android subsystem: today `ReceiveActivity.kt`'s
  ZXing integration (`decodeContinuous`) only ever returns decoded barcode
  text, never raw camera frames/bitmaps, so this would need a parallel
  capture pipeline (frame access, contour detection, homography warp,
  crop) built from scratch.
- ~~**Willow-style deterministic tie-break for domain resolution**~~ —
  **done.** [sneakerweb.org](https://sneakerweb.org)'s underlying **Willow**
  protocol orders conflicting entries by newest timestamp, then greater hash
  as a tie-break. SPEC.md's "Picking the closest match" (domain resolution,
  §7) now uses the same rule as its second tier, between location proximity
  (still first) and local scan-recency (still the last-resort fallback):
  among candidates declaring `created_at` (key 52), prefer the newest, tied
  by greater `root_hash`. Implemented in
  `TagDropLinkResolver.pickClosestDomainMatch()` (Android only — the web
  reader has no domain/collection browsing screen to apply this to, per the
  homepage-convention note above). Required persisting `created_at` onto
  `ScannedPaper` (it was already parsed from the wire format onto
  `TagDropPayload.Paper` but never stored) — see `AppDatabase` migration
  22→23. No SPEC.md wire-format change; this is a pure resolution-algorithm
  refinement, since `created_at` already existed as an optional field.
- **Sneakerweb-inspired collection merge/sync** (assessed: *interesting
  research idea, not started*). Willow (the CRDT-style sync/forgery-
  prevention protocol sneakerweb.org is built on) has a real merge/versioning
  story that TagDrop's Paper format lacks entirely — a paper is an
  immutable, author-sealed snapshot, with no way to reconcile two
  independently-updated copies of the same collection later (the tie-break
  above picks *between* snapshots, it doesn't merge them). If TagDrop ever
  wants *living*, updatable collections instead of always publishing a new
  sealed paper, Willow's merge approach is worth studying in full. Not
  queued — unclear how mergeable state would interact with the current
  content-addressed `cache_id`/`sha256` model, and it's a substantial new
  subsystem (versioning, conflict resolution, forgery prevention) — but
  worth a revisit if a concrete need for updatable/synced collections shows
  up. Given a shoutout in the website's "Related Projects" section
  (`docs/index.html`).
- **Sneakerweb/Willow interop, one direction only** (assessed: *plausible,
  long-term, not started*). Willow's live bidirectional sync (WGPS) doesn't
  fit TagDrop at all — a printed QR/NFC code is a sealed, fire-and-forget
  monologue with no return channel, unlike Willow's assumption that both
  peers are reachable for a multi-round-trip negotiation. But a **one-way**
  export is plausible: a TagDrop `Paper`'s `files[]` already look like a
  restricted case of Willow `Entry`s (`slug`≈path, `sha256`≈payload_digest,
  `created_at`≈timestamp), so the app could in principle export a scanned
  collection as Willow entries/a `.snk`-like bundle for import into a real
  Willow store — never the reverse, since there's no way to push a Willow
  update back down onto an already-printed sticker. Not queued — no known
  Willow-side import format to target yet, and it's unclear anyone
  scanning TagDrop codes also runs Willow — but worth a revisit if that
  changes.
  - **Signature scheme mismatch, assessed and rejected as a reason to
    change TagDrop's own scheme.** Willow/Meadowcap authorizes entries with
    Ed25519; TagDrop signs with ML-DSA-44 (post-quantum, SPEC §10) on
    purpose — Ed25519 is smaller and has native platform support, but it's
    exactly the scheme Shor's algorithm breaks outright and retroactively,
    which matters far more for TagDrop's permanently-sealed printed
    artifacts than for Willow's live, migratable store. Condition for
    revisiting TagDrop's own scheme was "only if Willow's choice has
    technical parity or superiority" — it doesn't, on the axis TagDrop
    actually optimized for, so no change. If this interop is ever built,
    the narrower option is adding Ed25519 *verification only* to the
    reader, to check imported Willow entries' existing signatures, without
    TagDrop ever signing anything with Ed25519 itself.
  - **Outreach lead (not yet contacted):** Willow's protocol and
    (apparently) sneakerweb are maintained by **Aljoscha Meyer** — Codeberg
    `AljoschaMeyer` (org `worm-blossom`, e.g.
    [willow_rs](https://codeberg.org/worm-blossom/willow_rs)), personal
    site [aljoscha-meyer.de](https://aljoscha-meyer.de). No direct email
    found yet (site fetch kept hitting transient API errors mid-session).
    Mirrors the existing deaddrops.com outreach in readme.md ("Community
    conventions" → "Dead Drops project") — once actually sent, document it
    there the same way, not here.
- **NFC: auto-fallback to standard-record-readable tags when they'd fit
  unsplit** (assessed: *medium usefulness, medium complexity — reasonable
  to defer*; tracked in
  [#58](https://github.com/mofosyne/tagdrop/issues/58)). Today
  `WriteNfcTagActivity`'s "include standard record" option (added in #43)
  is opt-in and off by default, so non-TagDrop phones can't read a tag
  unless the user checks a box first. Making it the effective default for
  content that fits one tag either way isn't just a checkbox flip, though:
  `sectorsFittingTag()` currently refuses to split when a standard record
  is requested, so it would need a genuine try-with-standard-record-first,
  fall-back-to-TagDrop-only-and-split-if-needed order, decided only after
  `onTagDiscovered()` measures the real tapped tag's capacity, plus
  post-write UI feedback showing which mode actually got written. Useful
  mainly for the "no app installed" case — and since this app is
  F-Droid-distributed, the AAR's built-in fallback (Play Store redirect)
  is already weak for its actual users — but most taps come from people
  already in the ecosystem, so it's a nice-to-have, not core.
