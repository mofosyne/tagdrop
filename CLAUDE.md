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

### Content description field, title-preference display, Remote Drop Sources unification, and a real CI-blocking test bug (2.5.0 release prep)

Several smaller, independent changes landed while prepping the 2.5.0
F-Droid release (`versionCode` 9→10, `versionName` "2.4.0"→"2.5.0"):

- **Content description field.** The web generator's Single File tab
  gained a `description` input threaded into `createContentSectors`/
  `buildContentExtension` (mirrored in `tools/examples/index.html` for
  codec-copy consistency, per "Known duplication" below) plus
  `deriveDescriptionFromText()` — auto-fills it from an HTML page's
  `<meta name="description">` or a Markdown file's first non-heading
  line, same trigger as the existing title auto-fill. `description` was
  already a decoded, persisted `FoundCache`/`ScannedPaper` field from
  earlier work (issue #35) but was never actually rendered anywhere in
  the Android UI — still isn't, as of this change; only the generator-side
  authoring gap was closed.
- **Title-preference display.** `HistoryAdapter`/`CollectionListAdapter`/
  `CollectionDetailAdapter` now show `cache.title ?: cache.hint ?:
  cache.filename ?: <untitled>` (and the `Paper` equivalent) as the row's
  main title, instead of always showing `filename`/`hint`. Filename is
  still shown, as a `cacheSubtitleBase()` line under the title, unless
  it would be redundant with a title/hint already showing above it —
  filename never fully disappears, it just stops being the primary label
  once something more descriptive exists.
- **Remote Drop Sources unification.** `docs/db/sources.json` — the same
  file "Browse recommended sources" already fetches live from
  `mofosyne.github.io/tagdrop/db/sources.json` — is now also bundled as
  an Android raw resource (`app/build.gradle`'s new
  `copySourcesJsonToRawRes` task, mirroring `copySpecToRawRes`/
  `copyQdefSpecToRawRes`). `SourceFetcher.fetchDirectory()`'s parsing was
  split out into a pure `parseDirectory(text)` plus a new
  `readBundledDefaultSources(context)`, so fresh-install seeding
  (`AppDatabase.seedDefaultSources`, now needing a `Context` threaded
  through `migration20To21`/`seedCallback`) and "Reload default sources"
  (`SourcesActivity.reloadDefaults`) both read the same bundled file
  through the same parser instead of two separately hand-maintained
  hardcoded `DropSource`/`RelatedSource` lists (a third, already-dead
  `AppDatabase.DEFAULT_SOURCES` list — confirmed via grep to have zero
  callers — was deleted outright rather than migrated). Net effect:
  changing which sources ship with the app is now a `docs/db/sources.json`
  edit, not a code change in three separate places.
- **A real, CI-blocking bug, found only by actually checking CI rather
  than trusting the "known pre-existing, unrelated" label this file had
  been carrying it under.** `TagDropCodecTest.kt`'s
  `decodeRawMatchesDecodeOfEncodedUri`/`decodeRawStripsQdefFraming` had
  been failing on *every single push* to this branch since the SPEC.md
  v9 port — confirmed via the GitHub Actions API, not assumed — which
  meant `testDebugUnitTest` failed the whole `Unit tests & APK builds`
  CI job every time (skipping the APK build steps too), not merely
  "2 pre-existing unrelated failures" as several session's worth of
  CLAUDE.md notes had characterized them. Root causes were two different
  things bundled under one label:
  1. `TagDropScan.RecordScan` (`TagDropPayload.kt`) was the one data
     class holding a `ByteArray` (`rawWireBytes`) that never got a
     `contentEquals`-based `equals()`/`hashCode()` override — unlike its
     siblings `ScannedRecord.Content`/`Paper` and `SplitFragment`, which
     already had one. A real latent bug (Kotlin data classes default to
     reference equality on array properties), now fixed the same way.
  2. `decodeRawStripsQdefFraming`'s failure wasn't a reference-equality
     artifact at all — it compared whole `RecordScan`s including
     `rawWireBytes`, but a QDEF-magic-framed scan's `rawWireBytes`
     legitimately keeps the magic prefix it was scanned with (`decodeRaw`
     doesn't retroactively edit the bytes it returns), so it can never
     equal the unframed scan's `rawWireBytes`. The test itself was wrong;
     narrowed to compare just the decoded `.record`, which is what the
     test name actually claims to verify.

  Lesson for future sessions: "pre-existing failing test, unrelated to
  this change" is not the same claim as "not blocking CI" — the two were
  conflated here for a long time. Check the Actions API, don't just
  reason from a test's failure message matching an old note.

- **qdef bot flagged three small QDEF encoder-hardening changes**
  (upstream PR [mofosyne/qdef#44](https://github.com/mofosyne/qdef/pull/44),
  not yet merged there): `typeId` becoming a required argument in the JS
  reference encoder's own call-time API (no wire-format effect); payload
  becoming spec-required to be byte-string/text-string only, never a
  scalar or map (closes a real ambiguity bug in *that* encoder); and a
  payload requiring a nonzero `typeId` (Bundle/`0` can never carry one).
  Verified directly against both of TagDrop's own encoders rather than
  taking the relay's characterization on faith (this project's standing
  practice): Compress Wrapper and Media Payload both already only ever
  pass a raw `ByteArray` as `payload`, with a nonzero `typeId` (`8`/`6`);
  `encodeRootBundle`/its JS equivalents (the implied, never-transmitted
  Bundle) don't expose a payload parameter at all, so violating rule 3
  isn't structurally possible through TagDrop's own API. All three are
  confirmed no-ops for TagDrop — no SPEC.md or code change needed.

Released as `v2.5.0` (tag + GitHub Release), merged to `master` via PR #64.
SPEC.md's `Status:` line stays `Draft` — this release does not flip it.

### QDEF project moved; container discriminator removed entirely (byte-mode QR framing bug)

A user question about a real generator-produced byte-mode QR hex dump
surfaced two things at once. First, the QDEF project moved from
`github.com/mofosyne/qdef` to `github.com/qdef-format/qdef-format.github.io`
(spec now published at `qdef-format.github.io/spec.html`, with a
validator tool at `qdef-format.github.io/tools/validator.html`) —
`scripts/sync-qdef-spec.sh`'s `SOURCE_URL` was repointed there and
`QDEF-SPEC-cached.md` refreshed (a much bigger diff than just this fix:
QDEF gained a new §4.7 Signature record type in the interim too, not
otherwise acted on here).

Second, and more substantively: QDEF-SPEC.md §3.5 has dropped the
mandatory container discriminator entirely — the entry above
("QDEF mandatory container discriminator") is now superseded. There's no
longer a separate CBOR item after the magic header; a namespace is
declared exactly the same way for the container root as for any
subrecord — the ordinary namespace-pairing prefix, folded into the root
array's own first element. All four implementations (the three web
tools' `qdefFrame`/`stripQdefFraming`, and the Kotlin app's
`TagDropCodec.stripQdefFraming`, decode-only there per the existing
"byte-mode QR carrier this app doesn't implement" note) were still on the
old shape — magic + a standalone namespace bstr CBOR item sitting ahead
of the root array as a second sibling item, byte-identical to the
now-removed discriminator. Fixed by splicing the namespace bstr in as the
root array's own new first element instead (bumping its declared item
count by one): `qdefFrame`/`MiniCbor.unframeNamespaceFromRootArray` (new,
mirroring `encodeRootBundle`/`decodeRootBundle`) on encode/decode
respectively. Total byte cost unchanged (9 bytes of overhead) — only
where the namespace sits within the CBOR moved. Every "Inspect CBOR"
debug pretty-printer (`describeCbor` in all four implementations) was
fixed the same way. `tagdrop:` URI and NFC NDEF carriers are unaffected
(no on-wire namespace either before or after — this only touches the
byte-mode QR/JABCode carrier).

Verified two ways, not just self-consistently: (1) re-derived the fix
against a real generator-produced hex dump the user posted, confirming
the corrected bytes round-trip (`qdefFrame` → `unframeNamespaceFromRootArray`)
back to the exact original root-bundle bytes; (2) ran the corrected bytes
through qdef-format's own independently-written `validator.js` tool,
which correctly parsed the root array, recognized the namespace, and
walked the Bundle/Content-Extension/Media-Preview/Media-Payload
structure. That check also surfaced a real bug in the validator itself
(not in this fix): its `analyzeRecord` doesn't implement §3.5's namespace
*cascading* to subrecords, so it false-positives "odd typeId requires a
namespace" on a subrecord that legitimately inherits its parent's
declared namespace — confirmed against the live spec text before
concluding this was the validator's bug and not TagDrop's; reported
upstream (issue draft handed to the user to file, since this session's
repo scope couldn't attach a second GitHub owner tier to add the
`qdef-format` org's repo directly).

On the Kotlin side, `TagDropCodecTest.kt`'s `decodeRawStripsQdefFraming`
was rewritten to build its `framed` test input via the new splice instead
of old-style concatenation; `decodeRawRejectsPartialQdefMagic`'s old
premise (magic being a 9-byte discriminator unit that could be "partially
present") stopped being true (magic is a flat, atomic 4 bytes, full
stop) — renamed/repointed at an actually-partial 3-of-4-byte magic
mismatch, with a new `decodeRawToleratesMagicWithNoNamespacePresent`
covering the now-legitimate "magic present, no namespace item at all"
case the old test used to (incorrectly) reject. Verified via the same
standalone `kotlinc`+JUnit harness used throughout this project's history
(this environment's Gradle wrapper still can't build normally) — 144
tests across `TagDropCodecTest`/`MiniCborTest`/`SectorAssemblerTest`
green, 0 failures.

Released as `v2.5.1` (`versionCode` 10→11, `versionName` "2.5.0"→"2.5.1") — a
bugfix-only release, no wire-format version bump (SPEC.md's own `version`
field is unaffected; this was a bug in how the byte-mode QR carrier's
namespace was framed, not a change to what it means). Confirmed via
real-device testing that the symptom this fixes is real: an app build
predating this fix fails to recognize the current QDEF-framed byte-mode
output from this repo's own web tools (falls back to generic
non-TagDrop content handling — `application/octet-stream`, no filename
— since `decodeRaw` returns null on the pre-fix binary). `tagdrop:` URI
and NFC NDEF codes were never affected, only the byte-mode QR/JABCode
carrier.

### QDEF drops namespace-implication and Type-ID parity entirely (SPEC.md version 14)

Started from "qdef was updated, let's adjust tagdrop to the new system"
— a same-day (2026-07-28) commit burst on `qdef-format/qdef-format.
github.io`'s `docs/QDEF-SPEC.md`, all by `mofosyne` (the user, same as
this repo's maintainer). An initial research pass fetching the live spec
found it self-contradictory in places (worked examples in §4.5/§4.6
still citing pre-renumbering Type IDs; §4.7 saying "Type 16" while its
own registry table said `8`; §3 saying "range 2–22" instead of "1–22")
— evidence of a redesign genuinely still in progress, not a settled
target. Rather than guess at intent against a moving, self-contradicting
document, the open questions were relayed directly to qdef bot and
answered in three rounds, each correcting the last:

1. **First reply**: namespace must now be transmitted on the wire,
   never merely implied by carrier context — the odd/even Type-ID
   parity rule (and its fail-closed "scoped Type ID + no namespace ⇒
   abort" guarantee, which versions 6–8's implied-namespace design was
   built around) is gone with no replacement. TagDrop's four Type IDs
   renumbered `1`/`3`/`5`/`7` → `1`/`2`/`3`/`4` in `registry.rec`,
   sequential since parity no longer carries meaning.
2. **Follow-up, asking for an exact byte-level worked example** against
   TagDrop's actual structure (implied/never-transmitted root Bundle,
   Content Signature nested inside two non-namespace-declaring QDEF
   standard Types, Paper-Body a sibling not descendant of Paper-Preview)
   surfaced that the first reply's cascading model was incomplete: a
   Record's namespace slot being entirely *absent* (not `h''`) was said
   to reset the ambient namespace for everything nested inside it too
   — meaning Content Signature and Compress/Split-wrapped Paper-Body
   would each need their own explicit 5-byte namespace, unable to
   cascade through Media Preview/Payload or Compress/Split Wrapper.
3. **A third reply, unprompted** ("even better than last answer"):
   qdef bot revised the underlying rule again after further thought — a
   Record's own namespace token now governs only how *that Record's
   own* Type ID resolves, never whether the ambient namespace continues
   flowing to its own subrecords. A standard/global Type stays global
   for itself but still relays whatever ambient namespace it received
   through to whatever's nested inside it, regardless of whether it
   declared one itself. Net effect: **all of TagDrop's own Records now
   cascade uniformly via a 1-byte `h''`**, including Content Signature
   three levels deep through two non-namespaced ancestors — the
   intermediate (second-round) answer's "some Records need their own
   explicit namespace" was wrong and is explicitly documented as
   superseded, both in SPEC.md's version-14 history entry and here, per
   this project's standing practice of leaving a visible trail rather
   than silently overwriting an earlier documented belief that turned
   out wrong (see the FINDINGS.md #51 precedent, and the version-12
   key-ordering self-correction above).

**The change, net:** every carrier (`tagdrop:` URI, NFC NDEF, byte-mode
QR/JABCode alike) now transmits TagDrop's namespace explicitly, once,
as the root array's own leading element — `tagdrop:` URI and NFC NDEF
never did this before (byte-mode QR already did, per the "container
discriminator removed" entry above). Every TagDrop-scoped Record
present on a code cascades from that one declaration via `h''` (1 byte),
regardless of nesting depth or how many QDEF standard Types sit in
between. TagDrop's four Type IDs are `1`/`2`/`3`/`4` now, which happen
to numerically collide with several of QDEF's own renumbered standard
Types (Split/Media Payload/Compress are also `1`/`3`/`4`) — expected and
safe, since the two sets resolve in different spaces (namespace-scoped
vs. global) and every decoder must resolve the namespace before
deciding which registry a given Type ID belongs to; this is also why
**getting namespace resolution right became a genuine security check**,
not just a byte-format detail. Separately, QDEF's own Common Field Key
registry shrank in the same redesign from 8 entries to just `-1`/`-3`
(ID/UUID), dropping the four keys TagDrop had migrated onto in version
11 (`contentHash`/`filename`/`label`/`source_url`) — reverted back to
their pre-version-11 Type-specific positions, since continuing to use
keys a shared registry no longer defines would no longer be backed by
the governance rationale that justified adopting them in the first
place. Full detail (exact field tables, byte costs, worked examples) is
in SPEC.md §2.1/§2.1a/§3.1/§3.1a/§3.2/§3.4/§14 — not duplicated here.

**Both codec implementations ported in parallel**, each as a
self-contained background task given the full technical spec derived
above, then reviewed by reading the actual diffs (not just trusting
the completion summary) before merging — per this project's own "trust
but verify" practice for delegated work:

- **Kotlin**: `MiniCbor.kt`'s `encodeRecord`/`decodeRecordPrefix` gained
  a `namespace`/`ambientNamespace` parameter implementing the final
  cascade rule (a Record's own `h''` resolves to whatever ambient value
  it received; a Record with no namespace item resolves to global for
  *itself* but still forwards whatever ambient value *it* received,
  untouched, to its own subrecords — the crux distinction the
  second-round wrong answer got backwards); `layoutOf` and
  `stripKeys`/`stripSubrecordType`/`stripAllSubrecords` updated to
  detect and carry through an optional leading namespace item;
  `encodeRootBundle`/`decodeRootBundle` thread the root's declaration.
  `TagDropCodec.kt`'s Type IDs and Common Field Keys renumbered/reverted
  per above, with a genuine security check added in `recordScanResult`
  (validates the root's resolved namespace before treating anything as
  a TagDrop Record). **A real bug caught by the port's own test suite,
  not by inspection**: `TYPE_COMPRESS` and `TYPE_PAPER_BODY` both being
  `4` is an intentional, namespace-disambiguated collision, but
  `unwrapPaperBody`'s `cur.typeId == TYPE_COMPRESS` check looked at the
  bare integer alone — misidentifying *every* uncompressed Paper as a
  Compress Wrapper, since Paper-Body's `h''` cascade and Compress
  Wrapper's absent-namespace-item both produce Type ID `4` at that
  position. All 12 Paper round-trip tests failed until fixed by
  requiring `cur.namespace == null` (genuinely unnamespaced/global)
  before taking that branch — applied symmetrically to the equivalent
  Content-side dispatch too, though that one turned out to be pure
  defensive hardening (Media Payload's Type ID is `3`, not `4`, so
  there's no legitimate way for genuine Content encoder output to hit
  the collision there — confirmed by tracing the call sites before
  assuming the fix needed to be symmetric). Verified via the same
  standalone `kotlinc`+JUnit harness this project has used throughout
  (fetched fresh — this environment resets between sessions — including
  a BouncyCastle version bump to 1.80, since 1.78.1 predates the
  `pqc.crypto.mldsa` package `MLDSA44.kt` needs): 163/163 tests pass.
- **JS** (generator/examples/reader, plus the independent
  `test-qdef-roundtrip.mjs` Node reimplementation — `test-qr-
  roundtrip.mjs` deliberately left untouched, per the version-10 entry's
  standing exemption): same design mirrored into each tool's own
  independently duplicated codec copy. The reader's `recordScanResult`
  gained the same root-namespace security check as Kotlin's.
  **A real bug found via a from-scratch cross-tool check** (loading the
  actual generator/reader script bodies in a Node `vm` context and
  driving `createContentSectors`/`recordScanResult` directly end to
  end, not just each tool's own self-consistency tests — the same
  technique that caught the negative-Common-Field-Key decode gap noted
  earlier in this file): `RECORD_TYPE_INFO`, in all three HTML tools,
  was a `Map` keyed by plain `String(typeId)` — since v14 deliberately
  makes TagDrop's Type IDs collide with QDEF's globals, later-inserted
  global entries silently clobbered the TagDrop ones sharing the same
  key, mislabeling Content Extension as "Split Wrapper" and Media
  Payload as "Paper-Preview" in the "🔍 Inspect CBOR" debug view. Never
  affected real decode logic (already correctly disambiguated by
  position + namespace) — fixed with a composite `(namespace-scope,
  typeId)` key. **Caught during this session's own review of the
  agent's diff, not by the agent itself**: the JS reader's
  `_unwrapMediaPayload` was missing the same defensive
  `cur.namespace === null` guard the Kotlin port added symmetrically to
  its equivalent — added to match, keeping the two implementations'
  defensive posture consistent even though (per the Kotlin bug analysis
  above) it isn't reachable via genuine Content encoder output either.
  Verified: `test-qdef-roundtrip.mjs` 11/11 pass (including a new
  `testWrongNamespaceRejected`), `qdef-lint.cjs` clean (15 codes, 0
  errors/warnings); `test-qr-roundtrip.mjs` fails in this sandbox on an
  unrelated, pre-existing `zxing-wasm` WASM MIME-type error (confirmed
  via an empty `git diff` on that file — not something this session's
  changes touched or could have caused).

**Outstanding gap, same as every prior port**: a full Android Studio
build (Room codegen, resource linking, the 15 caller Activities/
Fragments) hasn't run against this change — this environment's Gradle
wrapper still can't download its own distribution. The standalone
`kotlinc`+JUnit harness is the same substitute verification this
project has relied on throughout its QDEF history.

### QDEF removes the positional payload slot, folded into reserved map key 0 (SPEC.md version 15)

Found only because the user independently ran a real generator-produced
hex dump through QDEF's own validator tool (`qdef-format.github.io/
tools/validator.html`) after the version-14 port above shipped, and
noticed Media Payload's content sitting as a bare trailing array item
where the validator's own generic "payload (key 0)" annotation implied
it should be a map entry instead. This was a genuine miss, not a new
upstream change: the very first research pass into what changed
upstream (the one that led to version 14's namespace work) had already
flagged "payload slot removed, folded into map key 0" as **action
required** — it just got dropped when the namespace-transmission
question consumed the rest of that session's attention, and version 14
shipped without it.

**The change:** QDEF's grammar dropped the positional `payload` array
item entirely. A Record is now strictly `[namespace?, typeId, map?,
subrecord*]` — a Record's "one genuinely singular value," when it has
one, lives at reserved map key `0` (QDEF-SPEC.md §3.6) like any other
field, not a separate array position. Confirmed directly against the
live spec's own worked examples (§4.1, §4.3, §4.5), not the validator's
generic annotation alone — the validator turned out to check QDEF
grammar/namespace-cascade correctness, not whether a *standard* Type's
own fields match its documented shape, so it had accepted TagDrop's
stale version-14-only encoding as fully "valid" with no warning at all.
Renumbers every QDEF standard Type TagDrop uses that has (or had) a
payload:

- **Compress Wrapper (Type 4):** `[4, deflated_bytes]` → `[4, {0:
  deflated_bytes}]`.
- **Media Payload (Type 3):** `content` moves to map key `0`;
  `mediaType` (previously key `0`) displaced to key `1`.
- **Media Preview (Type 7):** never had a payload of its own, but key
  `0`'s reservation means it can't use it either — `mediaType`/
  `contentHash`/`filename`/`label` all shift up two keys
  (`0`/`1`/`3`/`5` → `2`/`3`/`5`/`7`), key `0` now unused on this Type
  entirely.
- **Split Wrapper (Type 1):** fragment data moves to map key `0`;
  `group_id`/`index`/`count` shift up two keys (`0`/`2`/`4` →
  `2`/`4`/`6`); `total_bytes`/`parity_scheme` (`7`/`9`) are untouched,
  since neither collided with anything vacating a slot.

None of TagDrop's own four namespace-scoped Types (Content Extension,
Content Signature, Paper-Preview, Paper-Body) are affected — none of
them ever had a payload.

**One real gap in the spec text itself, not guessed past:** Split
Wrapper's `parity_scheme` had never been given an explicit key number
anywhere in the current spec — discussed narratively in §4.1, never
shown in a worked example. Rather than assume the natural next-odd-slot
inference (`9`, matching its pre-redesign position) was right, it was
relayed to qdef bot, which confirmed `9` was correct — the prototype's
own working code already used it, only the spec prose had dropped it
during an earlier numbering pass — and fixed the spec's own gap
upstream in the same reply.

**New verification asset acquired this session, not just used once:**
`qdef-format.github.io`'s `scripts/qdef-validate.js` — a headless CLI
wrapper around the exact same `validateQDEF()` the browser validator
page runs, loading `tools/validator.js` + `assets/cbor-util.js` +
`registry.rec` into a plain Node context via `scripts/load-validator.js`
(itself a real, intentional export the qdef-format maintainer added "for
an external adopter (TagDrop) [who] asked for" a headless path — per
that file's own header comment). Fetched and assembled locally
(`raw.githubusercontent.com` was reachable throughout this session even
when the rendered `*.github.io` Pages site briefly 403'd on this
environment's egress policy — confirmed via the proxy's own status
endpoint/README, not assumed, and resolved once the user adjusted
permissions) — both codec ports used it as an independent cross-check
against real generated Content codes (single-code, Split multi-code,
Compress-wrapped), confirming the exact new key layout on every affected
Type, not just self-consistency. Worth keeping around for future spec
changes; consider vendoring it properly (alongside `qdef-lint.cjs`) if
this kind of drift recurs.

Both codec ports (Kotlin and JS) again ran as parallel background
tasks, fully specified this time (no ambiguity left after the parity_
scheme confirmation), then reviewed diff-by-diff before merging, same
discipline as version 14. Verified: Kotlin 163/163 tests
(`kotlinc`+JUnit); JS `test-qdef-roundtrip.mjs` 11/11, `qdef-lint.cjs`
clean (15 codes, 0 errors/warnings), `test-qr-roundtrip.mjs` still
failing only on its pre-existing, unrelated `zxing-wasm` sandbox issue.

**Real bug found by the user's own end-to-end device testing, missed by
every check above.** All of the version-15 verification — both codecs'
unit tests, `qdef-lint.cjs`, the independent `qdef-validate.js`
cross-check — passed clean, but none of it actually drove the real web
generator and scanned the result with the real Android app. Doing
exactly that surfaced a severe bug in `tools/generator/index.html` and
`tools/examples/index.html` (not `test-qdef-roundtrip.mjs`, a separate,
independently-duplicated codec copy per "Known duplication" below):
`buildContentExtension`/`buildContentSignature`/`buildPaperPreview`/
`buildPaperBody` still called `cborRecord(typeId, fields, [], undefined,
namespace)` — five arguments, left over from *before* this same
version-15 pass removed `cborRecord`'s `payload` parameter (shifting
`namespace` from the 5th argument position to the 4th). The trailing
`undefined` silently bound to the new `namespace` parameter instead, and
the real namespace value became a discarded 5th argument — every
TagDrop-scoped Record these four functions build (i.e. every real
generator-produced code) shipped with no `h''` cascade marker at all,
so the Android app's `recordScanResult` namespace check rejected every
one as "unsupported code." This is the identical bug pattern the
version-15 JS port's own background agent found and fixed in
`test-qdef-roundtrip.mjs` — but that fix didn't extend to the actual
browser tools, since they're a separately duplicated codec copy with no
automated cross-check between them (the same standing gap "Known
duplication" and the version-13 entry's Playwright note both already
flag). **This session's own diff review of the version-15 JS port did
not catch it either, despite quoting the exact buggy line
(`buildContentExtension`'s `}, [], undefined, namespace);`) verbatim
during that review** — the reviewer counted the pattern as "looks
right" without actually counting the arguments against `cborRecord`'s
just-changed 4-parameter signature. Fixed by the user directly: dropped
the stray `undefined`, added namespace assertions on QDEF global types
to `test-qdef-roundtrip.mjs` so a missing cascade marker fails the test
suite regardless of which encoder introduces it next time, and set the
examples page's binary-QR toggle to default on (so the QDEF-framed form
the validator actually checks is what a visitor sees first). Also
landed in the same commit: a multi-tier fallback in
`ReceiveActivity.kt`'s byte-mode QR scan handling (ZXing's
`BYTE_SEGMENTS` metadata isn't always populated; falls through to
`Result.rawBytes`, then the scanned text re-decoded as ISO-8859-1 —
ZXing's own default byte-mode encoding — then UTF-8) and a refreshed
`QDEF-SPEC-cached.md`.

**Lesson for future sessions, stated plainly:** a diff review that
pattern-matches "this looks like what I asked for" is not the same as
verifying it — counting an unfamiliar function's actual argument list
against its current signature is cheap and this session skipped it.
Self-consistent unit tests plus an independent spec-grammar validator
both passed clean on the broken code; only driving the real tool with
the real consuming app caught it. This is at least the fourth time this
project's history records exactly this pattern (see the version-13
entry's negative-Common-Field-Key gap, and the version-14 entry's
`RECORD_TYPE_INFO` collision) — cross-tool, real-artifact testing keeps
finding what layered self-consistency checks don't, and is still not a
committed, automated part of this repo's own test suite.

CI (GitHub Actions, not this session's local `kotlinc`/`npm` harnesses)
confirmed green after this fix, including — for the first time this
entire QDEF port's history — a real `./gradlew`-based `Unit tests & APK
builds` job succeeding, not just the standalone `kotlinc`+JUnit
substitute this environment has relied on throughout (this environment
itself still can't run Gradle; CI runs on GitHub's own runners, unaffected
by that limitation).

**The recurring gap above is now closed.** `tools/test-cross-tool-
roundtrip.mjs` (new) drives the real `generator/index.html` encode
functions into the real `reader/index.html` decode functions in one Node
process, via `jsdom` (`runScripts: 'dangerously'`, loading each file as a
whole HTML document rather than extracting just the `<script>` text, so
top-level DOM-touching statements resolve against real elements instead
of needing hand-stubbing — the friction every earlier ad hoc attempt at
this, per the notes above, ran into). Covers single-code Content,
multi-code Split Content, single-code Paper, and key-only codes,
including the `tagdrop:` URI carrier. Verified it actually catches what
it's meant to, not just that it runs: reintroduced the historical stale-
5-argument `cborRecord` bug into a scratch copy of `generator/index.html`
and confirmed all 4 tests fail with the exact "unsupported code" symptom
the real bug caused; confirmed all 4 pass again against the real, current
code. Wired into `tools/package.json` (`test:crosstool`) and
`.github/workflows/ci.yml`'s `web-tools-roundtrip` job, so this class of
bug — which has now shipped to `master` undetected at least four times —
finally has a standing, automated check watching for it.

### QDEF namespace scoping decided by typeId sign instead of h'' (SPEC.md version 16)

Started the same way as the version-14 namespace redesign did: "let's
update tagdrop to use the latest draft of qdef." Pulling the live
`docs/QDEF-SPEC.md` (`scripts/sync-qdef-spec.sh`) surfaced a real,
substantial change to §3.5 — traced to five upstream commits
(`5cbbaea` through `8132961`, all same-day) on
`qdef-format/qdef-format.github.io`. Unlike the version-14 exchange,
this one needed no relay to qdef bot — the upstream commit messages and
the resulting spec text were internally consistent and unambiguous on
a first read, so the whole port (spec rewrite, both codecs) happened
without any back-and-forth clarification round.

**The change:** a namespace byte string's job split into two, and only
one survived on the Record that carries it. Before, a Record's own
leading namespace item decided both (a) how *that Record's own* typeId
resolved (absent = global, `h''` = adopt ambient, explicit = new value)
and (b) what ambient value flowed to its subrecords. As of version 16,
a namespace bstr does **only** (b) — cascading to subrecords — and has
**zero effect** on the Record that carries it. Whether a Record's own
typeId is global or scoped is instead decided **purely by that typeId's
own sign**: non-negative = global (unconditionally, regardless of any
namespace bstr present), negative = scoped, adopting whatever namespace
is ambient from an ancestor. `h''` is gone from QDEF's grammar
entirely — sign carries the same signal at zero extra bytes instead of
`h''`'s 1 byte per occurrence.

**Structural consequence for TagDrop:** a Record can no longer both
introduce a namespace and be scoped by it in the same array (declaring
a namespace and being scoped by it now genuinely need two Records — a
namespace-carrying parent, a negative-typeId child). TagDrop's four
Record Types (Content Extension, Content Signature, Paper-Preview,
Paper-Body) keep their `registry.rec` declared magnitudes (`1`–`4`) but
now wire-encode **negated** (`-1`–`-4`) and carry no namespace item of
their own at all. The version-13 "a lone top-level Record needs no
Bundle wrapping" exception for key-only codes is gone as a direct
result — every TagDrop code, including a key-only code, is now a
namespace-declaring root Bundle wrapping one or two subrecords,
uniformly. Net byte cost: **saves** 1 byte per TagDrop-scoped Record
present on a code (no more `h''`), but **costs** key-only codes
specifically +1 byte (the now-mandatory wrapping array header) — see
SPEC.md's version-16 history entry for the exact per-shape accounting.
A genuine, if secondary, correctness improvement fell out of this for
free: since TagDrop's typeIds now wire-encode negative and every QDEF
standard Type wire-encodes non-negative, the two are disjoint CBOR
major types, not just disjoint by convention — a decoder can never
mistake TagDrop's wire `-1` for QDEF's global Split Wrapper (`1`) by
magnitude alone, the way version 14–15's design theoretically could
have without a namespace check first.

Separately, the same upstream commit burst simplified QDEF's Common
Field Key registry (already just `-1`/`-3` as of version 14) down to
`-1` only (UUID now travels as CBOR tag 37 wrapping that same key,
rather than its own `-3`), and re-tiered the global-typeId allocation
ranges by CBOR byte cost. Neither needed any TagDrop action: TagDrop
reverted off all shared Common Field Keys back at version 14 already
and never adopted UUID, and TagDrop's own typeId numbering is
namespace-scoped, which QDEF-SPEC.md §4 explicitly leaves to the
namespace owner regardless of the global-range boundaries.

**SPEC.md rewritten directly** (not delegated) — the namespace-scoping
section (§2.1a) needed the same close, careful reasoning the version-14
rewrite did, including working through the "declared vs. wire-encoded
sign" distinction upstream's own `8132961` commit had just made
explicit in `registry.rec`. Every worked CBOR example, the byte-cost
accounting, and the "why this still matters" security argument were
rewritten in place; a version-16 history entry added; the version-13
history entry's "single top-level Record, no Bundle indirection" claim
was marked superseded in place (this project's standing practice of
leaving a visible trail rather than silently rewriting an earlier
documented belief — see the version-12 key-ordering and FINDINGS.md #51
precedents) rather than deleted.

**Both codec ports ran as parallel background tasks**, each given the
full technical delta derived above — including the exact call sites
already traced by reading both codebases first, not left for the agent
to rediscover — then reviewed diff-by-diff before merging, same
discipline as versions 14/15. Both were correct on the first pass, a
change from versions 14/15's history (each of which had at least one
real bug caught during review or by the user's own later testing):

- **Kotlin**: `MiniCbor.kt`'s `encodeRecord` now encodes `typeId`
  through the same signed-int path already used for negative map keys
  (`encodeKey`, generalized rather than duplicated); `decodeRecordPrefix`
  separates "this Record's own resolved scope" (`ambientNamespace` if
  the decoded typeId is negative, else `null`) from "what ambient value
  passes to subrecords" (this Record's own explicit namespace item if
  present, else the incoming ambient passed through unchanged) — the
  same two-part cascade as before, just re-keyed off sign instead of
  namespace-item-presence. `encodeRootBundle`/`decodeRootBundle` lost
  their single-Record special case entirely — always wrap now.
  `TagDropCodec.kt`: every encode call site for the four TagDrop-scoped
  Types negates its typeId constant and drops the `NAMESPACE_CASCADE`
  (`h''`) argument, now deleted; `createKeyCodeSector` rebuilt to
  Bundle-wrap its lone Record via `encodeRootBundle`; decode-side typeId
  comparisons against a TagDrop-scoped constant negated to match. The
  `cur.typeId == TYPE_COMPRESS && cur.namespace == null`-style collision
  guards (disambiguating a shared magnitude between a TagDrop-scoped
  Record and a QDEF global Type, added at version 14 after a real bug)
  are now provably-always-true defense-in-depth, since sign alone makes
  the two disjoint CBOR values — left in place rather than removed,
  comments updated to say so. **Two real display-only bugs caught by
  this port**, both in the "🔍 Inspect CBOR" debug pretty-printer, not
  the wire format itself: the root namespace summary line was reading
  `records.firstOrNull()?.namespace`, which resolves to `null`
  whenever the first Record happens to be global-typed — reading the
  root's own leading item directly via
  `MiniCbor.unframeNamespaceFromRootArray` instead; and
  `TAGDROP_TYPE_NAMES`/`TAGDROP_KEY_NAMES_BY_TYPE` lookups (keyed by
  positive declared magnitude, matching `registry.rec`) needed
  `rec.typeId` negated back before indexing, since records now decode
  with a negative typeId. Verified via the same standalone
  `kotlinc`+JUnit harness this project's QDEF port has used throughout
  (fetched fresh, this environment resets between sessions): 164/164
  tests pass.
- **JS** (generator/examples/reader, plus the independent
  `test-qdef-roundtrip.mjs` — `test-qr-roundtrip.mjs` deliberately
  untouched, confirmed via an empty `git diff`, per the version-10
  entry's standing exemption): a new shared `writeSignedInt` helper
  (major-type-1 negative-int CBOR encoding at any value position, not
  just a map key) backs both `writeKey` (which now just delegates to
  it) and `cborValue`'s number/bigint branches, needed since a Record's
  typeId can now be negative too. `decodeRecordPrefix` split into the
  same two-part cascade as the Kotlin side. `encodeRootBundle`/
  `decodeRootBundle` lost their single-Record special case. The debug
  "🔍 Inspect CBOR" `RECORD_TYPE_INFO` table — a composite
  `(namespace-scope, typeId)` key as of version 14, to prevent
  TagDrop/QDEF-global entries sharing a magnitude from clobbering each
  other — simplified back down to a **plain signed-typeId key**, since
  sign alone now makes every key collision-free by construction (a
  genuine simplification the Kotlin side's equivalent tables don't
  parallel 1:1, since Kotlin already used two separate lookup tables
  rather than one composite-keyed map). **One real bug caught by this
  port**, in generator/examples/reader alike:
  `contentSignedMessageHash`'s `stripSubrecordType(mediaPayload,
  TYPE_CONTENT_SIGNATURE)` call was still matching against the
  *positive* declared constant instead of `-TYPE_CONTENT_SIGNATURE` —
  since `stripSubrecordType` matches the exact wire typeId, this would
  have silently failed to strip Content Signature before hashing,
  breaking every signed Content payload's signature verification
  (caught before it shipped, by this port's own diff review, not by
  the test suite — worth noting since the test suite's own real
  ML-DSA-44 sign/verify round trips evidently didn't exercise this
  path in a way that surfaced it, an actual coverage gap worth
  revisiting). Verified: `test-qdef-roundtrip.mjs` 11/11,
  `qdef-lint.cjs` clean (15 codes/11 fixtures, 0 errors/warnings),
  `verify-examples-lint.mjs` clean, `test-cross-tool-roundtrip.mjs`
  4/4 (real generator→reader HTML round trip via jsdom), and
  `test-qr-roundtrip.mjs` 14/14 (confirmed unaffected). Additionally
  cross-checked against `qdef-format.github.io`'s own
  `scripts/qdef-validate.js` (at its current HEAD, which includes the
  exact upstream commits introducing this rule) — all 15 fixture codes
  `VALID`, 0 errors, with `typeId=-1`/`-2` correctly resolving to
  Content Extension/Content Signature three levels deep through two
  non-namespaced global ancestors (Media Preview/Media Payload),
  matching `registry.rec`'s real entry for TagDrop's namespace. All
  test suites independently re-run and confirmed by this session
  directly, not just taken on the porting agent's word.

**Outstanding gap, same as every prior port**: a full Android Studio
build hasn't run against this change — this environment's Gradle
wrapper still can't download its own distribution. The standalone
`kotlinc`+JUnit harness remains the substitute verification this
project has relied on throughout its QDEF history.

**A real coverage gap worth flagging for a future session**, surfaced
by the `stripSubrecordType` bug above: this project's own real
ML-DSA-44 sign/verify round-trip tests (`testSingleCodeSignedContent`
in `test-qdef-roundtrip.mjs`, and `TagDropCodecTest.kt`'s equivalents)
evidently don't independently re-verify a signature against
*hand-recomputed* signed-message bytes — only against whatever the
encoder itself produced, so a bug that's consistently wrong on both
the sign and verify sides (as this one nearly was, had it shipped)
could plausibly round-trip clean anyway. Not chased down further this
session, since the bug was caught by diff review before it ever ran,
but worth a closer look at what these tests actually assert versus
what they'd need to assert to catch this specific failure mode.

### QDEF self-scoping amendment (declined for TagDrop, for now); NFC dropped from QDEF's scope, magic header now always included on NDEF (SPEC.md version 17)

Two independent changes, initiated back to back in the same session by
the user (who is also `qdef-format`'s maintainer, so both landed as
direct edits rather than relayed proposals — see CLAUDE.md's own
"drop the veil" framing for why that's notable: no qdef-bot round trip
was needed for the first change).

**QDEF-side: a Record may now self-scope** (`qdef-format/qdef-format.
github.io`, not this repo). Raised as a design question — since a
namespace bstr's presence next to a *global*-typed Record is legal but
inert (§3.5), was the "two Records needed to declare-and-scope in one
step" limitation actually load-bearing, or just an artifact of the v16
redesign's "check sign, not presence" simplification? Landed upstream
directly: a negative-typeId Record now checks for an explicit bstr on
its *own* array first, before falling back to ambient — `[h'ns', -N,
{...}]` is now valid and self-scoped, at zero extra cost either way. A
narrow, additive amendment, not a reversion to the pre-v16 decision
tree's complexity (still "check sign" as the primary rule, with one
small addition, not three cases at every level). Also fixed in the same
pass: a genuine pre-existing inconsistency in QDEF-SPEC.md's own §3
closing paragraph (contradicted the sign-only rule stated moments
earlier in the same section — unrelated to this change, just found
while editing nearby), and a real bug in `tools/validator.js`'s own
independent `analyzeRecord` copy that would have flagged a self-scoping
root Record as an error (it unconditionally required an *inherited*
namespace, never checking the Record's own bstr first). Verified via
the qdef-format repo's own `npm test`/`npm run build`/`npm run lint` —
38/38 tests passing, including new assertions decoding a hand-built
self-scoped example and checking it annotates correctly.

**Assessed for TagDrop, explicitly deferred, not adopted.** This would
let a key-only code (§9) drop its Bundle wrapper again — Content
Extension could declare `h'89d414e0'` and scope itself to it on the
same array, recovering the version-13-era "no Bundle indirection" byte
shape for that one case (a 1-byte saving). Noted in SPEC.md's
version-17 history entry as deliberately deferred rather than silently
skipped, so it doesn't get lost — revisit as a small follow-up pass if
prioritized. Every other TagDrop-scoped Record (Content Signature,
Paper-Body, and Content Extension/Paper-Preview in the common two-Record
case) already gets the zero-byte "no namespace item, adopt ambient"
path and has nothing to gain from this amendment, since only a Record
that's *simultaneously* the namespace's introduction point and itself
scoped benefits.

**QDEF drops NFC/NDEF from its own scope; TagDrop's own NDEF carrier now
always includes the QDEF magic header.** Separately, prompted by "isn't
excluding the magic header on NDEF kind of pointless when it's just 4
bytes" — assessed and agreed: NFC's capacity headroom (a Type 2 tag
alone is ~1 KB) makes the old "NDEF's own MIME type already
disambiguates it, so skip the redundant 4 bytes" reasoning genuinely
weak compared to the same argument for `tagdrop:` URI, where those same
4 bytes cost ~1.5× as many QR alphanumeric characters after Base41
encoding — real pressure `tagdrop:` URI's own exemption is worth
keeping, that NFC's never really had. In the same conversation, decided
to narrow QDEF's own scope to 2D barcodes only, dropping NFC/NDEF as a
QDEF-supported carrier entirely (`qdef-format/qdef-format.github.io`
again) — removed from QDEF-SPEC.md's philosophy/framing, §2's carrier
table and its NDEF magic-header exemption paragraph, §3.6/§3.5/§4.4/§4.7's
NDEF-analogy asides, README.md, index.html, and the embedded llms.txt
description in `scripts/build.js`; `docs/RELATED-WORK.md` keeps its NDEF
comparison intact (that page's whole purpose is surveying prior art) but
several cells/claims that had drifted into describing QDEF's *own* scope
rather than NDEF's were corrected, including a stale claim that QDEF's
own §2 still defines an NDEF MIME-type-embedding mechanism (it doesn't,
post this change). `llms-full.txt` (the single-page LLM reference,
manually maintained and copied verbatim into the build, not
auto-generated) turned out to be several spec versions stale in the
process of removing its own NFC mention — still described the old
`h''`-based namespace rule and mislabeled the standard-type range as
"2-22" instead of "1-22" — fully rewritten to match the spec's actual
current state while fixing the NFC mention, rather than leaving the rest
wrong.

Dropping NFC from QDEF's own scope removes the magic-header exemption
QDEF used to define for NDEF specifically, but **TagDrop's own NFC NDEF
carrier isn't going anywhere** — that's TagDrop's own carrier design
choice (§12), independent of whether QDEF's own spec still discusses
NFC. With QDEF's own NDEF-specific guidance gone, TagDrop now follows
QDEF's simplified general rule directly: only an application's own URI
scheme (`tagdrop:`) skips the magic header; every other carrier,
including NFC NDEF as of this version, always includes it (SPEC.md
version 17). Framing changes from "Record Sequence bytes only" to "QDEF
magic (4 bytes) + Record Sequence bytes" — identical to byte-mode
QR/JABCode's framing now. Cost: **+4 bytes per NDEF-carried code.**

**Implementation**: `TagDropCodec.kt` gained `addQdefFraming` (the
encode-side mirror of the existing decode-side `stripQdefFraming`, which
needed no changes at all — it was already tolerant of the prefix being
present or absent, just never previously exercised on a real NDEF
payload that had one). `NfcUtils.buildNdefMessage` — the one function
that actually builds the wire-level NDEF MIME record payload, called
from both of `WriteNfcTagActivity.kt`'s write sites — now calls
`addQdefFraming` before handing bytes to `NdefRecord.createMime`, so
every call site (including the `fitsCapacity` tag-capacity probe) picks
up the correct framing and byte-accurate capacity measurement
automatically, with no per-call-site changes needed. `ReceiveActivity.kt`'s
NDEF read path (`handleNfcIntent` → `TagDropCodec.decodeRaw`) needed no
code change at all, only a comment update — `decodeRaw`'s existing
`stripQdefFraming` step was already carrier-agnostic. Added
`addQdefFramingRoundTripsWithDecodeRaw` to `TagDropCodecTest.kt`
covering the new encode-side helper specifically. The JS web tools
implement no NDEF carrier at all (Android-only, confirmed by grep before
concluding no JS changes were needed) — only the Kotlin side needed
propagation, no parallel background-agent port this time, small enough
to do directly. Verified via the same standalone `kotlinc`+JUnit harness
this project's QDEF port has used throughout (freshly assembled again,
same jar versions as the version-16 port): 165/165 tests pass (the +1
over version 16's 164 being the new NDEF-framing round-trip test). The
three Android-framework-dependent files touched (`WriteNfcTagActivity.kt`,
`NfcUtils.kt`, `ReceiveActivity.kt`) can't compile in this harness
(`android.nfc.*` isn't on it) — verified by careful diff re-reading
instead, same as every prior session's honest limitation here.

### Decompression-bomb and Split resource-exhaustion guards

User question, not an upstream/spec-driven trigger this time: "hmmmmm...
zip bombs, is that an issue here?" — prompted by Compress Wrapper's
existence, followed by "yes and may want to note in spec so others don't
get zip bombed" once the answer came back yes. Two related but distinct
gaps, both real:

1. **Decompression bomb via Compress Wrapper.** Nothing about DEFLATE
   stopped an author (malicious or otherwise) from shipping a small,
   pathologically repetitive stream that inflates far past its own size.
   DEFLATE has no recursive container structure (unlike nested ZIP, which
   compounds this across several unzip passes), so the amplification from
   one inflate pass is bounded — but the bound is still large, ~1032:1
   worst case. Both codecs' `decompress()`/`zlibDecompress()` previously
   copied the inflate stream to output with no size check at all.
2. **Reassembly resource exhaustion via Split Wrapper.** `count`/
   `total_bytes` are attacker-controlled fields read off an untrusted
   scanned code, straight into sizing decisions for fragment-tracking
   storage — a single hostile code declaring an enormous `count` or
   `total_bytes` could force a large allocation (or repeated large
   `O(count)` scans) before a single real fragment arrives, no multi-code
   group actually needed.

**Fixed in both, with hard ceilings, not just observation.** Kotlin:
`TagDropCodec.kt` gained `MAX_DECOMPRESSED_BYTES` (64 MiB),
`MAX_SPLIT_TOTAL_BYTES` (16 MiB), `MAX_SPLIT_FRAGMENT_COUNT` (4096), and
a `DecompressionBombException`; `decompress()` now checks cumulative
output size incrementally against the cap as it reads from
`InflaterInputStream` (catching a bomb as soon as the ceiling is
crossed, not after allocating up to it), throwing rather than silently
truncating — all 3 production call sites already wrap it in
`runCatching { }.getOrNull()`, so no caller changes needed beyond the
guard itself. `SectorAssembler.kt`'s `add()` rejects (`State.Failed`)
any Split fragment whose declared `count`/`total` falls outside the caps
*before* `groups.getOrPut` ever runs. JS: `tools/reader/index.html`
(the only JS file that actually decodes — generator/examples got the
same `MAX_DECOMPRESSED_BYTES` constant and bounded-read pattern applied
to their own unused-by-any-live-encoder `zlibDecompress` copies, for
codec-copy consistency per "Known duplication" below, not because
they're a real attack surface) gained matching constants and the same
incremental-cap-check rewrite of `zlibDecompress` (reading
`DecompressionStream`'s output chunk-by-chunk rather than draining it
in one shot), plus the same count/total guard at both of
`RecordAssembler.add()`'s group-creation sites (Paper and Content) —
hit a real editing gotcha here, not a logic bug: the two sites have
different indentation (6-space vs 4-space), so a first `replace_all`
Edit silently matched only one of them; caught by re-grepping for the
new constant and finding a single occurrence, fixed with a second,
separately-indented Edit.

**SPEC.md gained two new subsections**, not just code — the user's ask
was explicit about this ("note in spec so others don't get zip
bombed"): §8 (Compression) gained "Decompression-bomb guard" (the
incremental-check requirement, the 1032:1 worst-case DEFLATE
amplification bound, why a hard MUST ceiling is required); §5 (Multi-
Code Assembly Protocol) gained "Reassembly resource-exhaustion guard"
(the count/total_bytes ceiling requirement, explicitly called out as a
*separate* guard from §8's — `total_bytes` bounds the reassembled bytes
as transmitted, which may themselves still be Compress-wrapped, so a
single-code Compressed payload with no Split involved still needs §8's
guard on its own). Both describe the requirement generically (decoders
MUST enforce hard ceilings, checked incrementally) with TagDrop's own
implementations' specific numbers given as a concrete example, so a
from-scratch third-party decoder gets the same warning this session's
question surfaced. **No SPEC.md version bump** — unlike every prior
version-N entry above, this is a decoder-side robustness/validation
addition, not a wire-format byte-layout change; nothing about what a
compliant encoder emits on the wire changes, only what a compliant
decoder must be prepared to reject. SPEC.md's version stays `17`.

**New test coverage on both sides**, not just the fix. Kotlin:
`TagDropCodecTest.kt` gained `decompressRejectsOutputExceedingCap`/
`decompressAcceptsOutputAtOrBelowCap` (using `decompress()`'s new
optional `maxBytes` parameter to test against a tiny 100-byte cap
rather than needing a real 64 MiB+ fixture); `SectorAssemblerTest.kt`
gained three tests under a new "Resource-exhaustion guards" section
(`oversizedDeclaredCountRejected`, `oversizedDeclaredTotalBytesRejected`,
`ordinarySmallMultiFragmentPayloadUnaffectedByGuards`). The first two
initially failed with an NPE unrelated to the guard itself — the
hand-built hostile `splitFragmentBytes(...)` fixtures were missing the
Media Preview subrecord `contentScanResult`'s multi-code decode path
requires, so the code was being rejected at an earlier, unrelated
validation layer before `SectorAssembler.add()` (and the new guard) was
ever reached; diagnosed via a standalone debug script calling
`MiniCbor.decodeRootBundle` directly, fixed by adding the missing
subrecord. JS: `tools/test-cross-tool-roundtrip.mjs` — the real
generator→real reader jsdom harness (see the version-15 history entry
above for why this file exists) — gained
`testOversizedSplitDeclarationsRejected`, building a real Content
Extension + Media Preview via the generator's own encoders and only
hand-constructing the hostile part (a Split fragment via the
generator's own low-level `cborRecord`/`TYPE_SPLIT` primitives,
declaring `count`/`total_bytes` one past the reader's caps), then
asserting the real reader's `RecordAssembler.add()` rejects it. Verified
this test actually catches a regression, not just that it runs: with
both of `reader/index.html`'s guard checks temporarily disabled, the
new test failed with the exact wrong-state symptom (`'Collecting'`
instead of `'Failed'`); restored, it passes again.

Verified via the same standalone `kotlinc`+JUnit harness this project's
QDEF port has used throughout (freshly assembled again for this
session, this environment resets its filesystem between sessions) —
196/196 tests pass across the full harness-eligible file set
(`TagDropCodecTest`/`SectorAssemblerTest`/`MiniCborTest`/`Base41Test`/
`TagDropPayloadTest`; `MarkdownRendererTest`/`LenientJsonTest`/
`TagDropLinkResolverTest` excluded from this harness as before, since
those source files pull in dependencies — `commonmark`, Room's
`AppDatabase`/DAOs — this lightweight jar-only harness was never set up
to vendor). JS: `npm test` (`test-qr-roundtrip.mjs`, 14/14 — notably
*not* failing on the sandbox `zxing-wasm` issue this session's earlier
CLAUDE.md entries flagged as pre-existing, apparently resolved in this
environment since then), `npm run test:qdef` (11/11),
`npm run test:crosstool` (5/5, including the new guard test),
`npm run lint:qdef`/`lint:examples` (both clean, 15 codes/11 fixtures, 0
errors/warnings) all pass; `qdef-fixtures.json` regenerated.

**Outstanding gap, same as every prior port**: a full Android Studio
build hasn't run against this change — this environment's Gradle
wrapper still can't download its own distribution. The standalone
`kotlinc`+JUnit harness remains the substitute verification this
project has relied on throughout its QDEF history.

### Legacy `data:` URI support removed; non-TagDrop scans gated behind an opt-in preview

Two related receiver-side changes, both user-requested and both scoped to
the Android app only (the JS web tools have no equivalent legacy path or
raw-scan auto-import behavior to begin with — confirmed by grep before
concluding no JS changes were needed).

**Legacy `data:` URI support removed.** SPEC.md §11 previously described
a **legacy mode**: a code containing a raw `data:` URI (not the
`tagdrop:` scheme) was recognized specially — a single code opened
directly, multiple codes dumb-appended in scan order (the original V1
behavior) — with the document stating "legacy support will be maintained
indefinitely." That promise is withdrawn: `TagDropCodec.decode()` no
longer special-cases a `data:` prefix (it's now just another
non-`tagdrop:` string, returning null same as any unrecognized scheme);
`TagDropPayload.Legacy` and `TagDropScan.LegacyScan` are deleted outright,
narrowing `decode()`/`decodeRaw()`'s return type from `TagDropScan?` to
`TagDropScan.RecordScan?` now that it's the sealed class's only remaining
case (a real type-checker catch during this change: `ReceiveActivity.kt`'s
`barcodeCallback`'s local `tryBytes()` helper still declared a `TagDropScan?`
return type feeding into `processScan(scan: TagDropScan.RecordScan)` —
narrowed to match, or it wouldn't have compiled). `ReceiveActivity.kt`
loses `legacyChunks`/`tryCompleteLegacy()`/`launchLegacyContent()`/
`parseLegacyDataUri()` and the "Launch Content (legacy)" button
(`activity_receive.xml`, `strings.xml`); a scanned `data:` URI now falls
through to the same generic non-TagDrop content path as a URL, vCard, or
plain text QR code (see below) instead of being auto-opened. SPEC.md §11's
heading and section number are kept as a deliberate placeholder ("removed"
appended to the title, content rewritten to say so) rather than deleting
the section and renumbering §12 onward — avoids invalidating every other
`§12`/`§13`/etc. cross-reference elsewhere in the document for a promise
that's simply being withdrawn, not replaced by a differently-numbered
one.

**Non-TagDrop scans no longer auto-import.** Follow-up ask, arriving
mid-session: a scanned code that isn't a TagDrop payload — a URL, vCard,
Wi-Fi config, or any other content `QrContentClassifier`/`MimeTypeGuesser`
can't (or can) make sense of — was previously cached and opened
immediately by `completeRawScan()`. Unlike a `tagdrop:` payload (an
intentional dead-drop with author-declared metadata), a stray non-TagDrop
scan is unauthenticated and unvetted — "a tag full of gibberish is not
something people would care about" was the concrete framing. `completeRawScan()`
now gates on a new suspend dialog, `askImportRawScan()` (same
`CompletableDeferred`-bridges-`AlertDialog` pattern already used by
`askAddSource`/`askPassphrase`): shows the first `RAW_SCAN_PREVIEW_BYTES`
(64) bytes as hex and as best-effort UTF-8 (Java's `String(bytes,
Charsets.UTF_8)` replaces invalid sequences with U+FFFD rather than
throwing, so this never crashes on arbitrary binary) side by side, so the
user can visually guess what it is, with explicit Import/Discard buttons —
nothing is cached or opened until Import is tapped. Two cases skip the
prompt, both because it would be pure friction for content the user has
already effectively vetted: content already declared by a Paper the user
is actively scanning (`paperFile` matches the currently-open Paper's
manifest — an expected, intentional part of a trail the user engaged with
by scanning the Paper itself, not a stray find) and anything already
cached (re-scanning something already imported shouldn't re-prompt every
time). This is reader-side UX, not a wire-format change — no SPEC.md
entry, matching how §11's rewrite above frames non-TagDrop content
handling as "an implementation detail, not part of this wire format."

Verified: the `data/format` package (Android-framework-free) recompiles
clean and the full suite passes (195/195, one fewer than before since the
removed `legacyDataUriDecodesToLegacyScan` test folded into
`navigationLinkAndUnknownSchemesReturnNull`, asserting a `data:` URI now
returns null same as any other unrecognized scheme) via the same
standalone `kotlinc`+JUnit harness this project's QDEF port has used
throughout. `ReceiveActivity.kt` itself can't compile in that harness
(Android framework classes aren't vendored, the same longstanding gap
noted throughout this file) — verified by careful hand re-reading instead,
which is what caught the `tryBytes()` return-type mismatch above before
it could ship.

Released as `v2.6.1` (`versionCode` 12→13, `versionName` "2.6.0"→"2.6.1")
— a bugfix/robustness release bundling everything landed on `master`
since `v2.6.0` (2026-07-29): the SPEC.md v16/v17 wire-format port
(namespace scoping by typeId sign, NFC magic-header-always-included),
the decompression-bomb/Split resource-exhaustion guards, an unrelated
real crash fix (a SQLite `NOT NULL` constraint failure in
`drop_sources` seeding, landed directly on `master` outside this
session's own changes), and this section's legacy-removal/raw-scan-
preview work. CI (`./gradlew`-based `Unit tests & APK builds`,
GitHub's own runners) confirmed green on the exact commit tagged.

### Split's `group_id` reframed as a correlation token; new mandatory `payload_hash` field (SPEC.md version 18)

Two related upstream QDEF-SPEC.md commits
([49d4962](https://github.com/qdef-format/qdef-format.github.io/commit/49d4962),
[f56220b](https://github.com/qdef-format/qdef-format.github.io/commit/f56220b))
surfaced a real gap in this repo's own SPEC.md, not just an upstream
grammar change to port. §4.1's Split Wrapper used to describe `group_id`
(key `2`) as a content hash "a decoder MUST verify" after reassembly —
an integrity check. Upstream reframed it as an opaque **correlation
token** only: every physical QR/Data Matrix/Aztec symbol already carries
its own Reed-Solomon error correction, so a scanned code either decodes
cleanly or fails outright — there's no realistic "decoded fine but
silently wrong bytes" case for a per-fragment field to catch (QR's own
Structured Append mode makes the same call for the same reason, ISO/IEC
18004). A new field fills the resulting gap for applications that do
want reassembly-integrity verification: `payload_hash` (Split Wrapper
key `11`, OPTIONAL/odd at the QDEF level) — a multihash of the fully
reassembled payload, present only on the `index == 0` fragment.

The bug this surfaced: SPEC.md's own "Why `contentHash` and `root_hash`
are scoped differently" note (§3.4) explicitly justified deleting the
old design's `content_sha256`/`bulky_meta_sha256` multi-sector integrity
hashes as "superseded by `group_id`'s mandatory decoder-side
verification" — a real guarantee this repo's own payloads had been
relying on, that had since been redefined out from under it upstream.
Fixing the prose alone would have left every real Split-wrapped TagDrop
payload actually unverified while SPEC.md claimed otherwise, so
`payload_hash` was adopted as a **TagDrop-MUST**, not left as QDEF's own
optional field — the same "tighten a QDEF-optional mechanism into a
TagDrop-MUST where TagDrop specifically needs it" pattern §2.1a's
mandatory namespace declaration already established: TagDrop encoders
MUST always set it; TagDrop decoders MUST reject a Split-wrapped payload
missing it, rather than silently proceeding the way skipping an
ordinary optional field normally would.

**SPEC.md** (§14's version-18 entry has the full detail): the
`contentHash`/`root_hash`/`group_id` scoping note (§3.4), §5.1's two
`group_id`-verification sentences (replaced with a new shared
"Reassembly integrity (`payload_hash`)" paragraph), §5.2's reassembly
steps and redundancy-reconstruction paragraph, §9's encrypted-
override-map ordering note, and §15's version-2 history entry (marked
superseded in place, this project's standing practice, rather than
silently rewritten) were all corrected. `QDEF-SPEC-cached.md` refreshed
via `scripts/sync-qdef-spec.sh`.

**Kotlin implementation (this task's actual scope — the JS web tools
were deliberately left untouched, unlike every prior QDEF-driven port in
this file's history):** `TagDropCodec.kt` gained `SK_PAYLOAD_HASH = 11`;
`splitFragments()` now computes a multihash (`0x12` sha2-256 prefix +
**8-byte truncated** digest, matching `contentHash`'s/`group_id`'s own
truncation — QDEF-SPEC.md §4.1 explicitly permits "truncated or full"
here, and this field only guards against accidental damage, not
deliberate tampering, which Encrypt/Signature already cover, so a
signature-grade full digest isn't needed. First implemented with a
full, untruncated digest, then revisited after a user question about
byte cost — `SectorAssembler.kt`'s `payloadHashMatches` reads the
digest length from the field itself (`hash.size - 1`) rather than
hardcoding 8, so a compliant non-TagDrop encoder's full 32-byte digest
still verifies correctly; `payloadHashAcceptsFullUntruncatedDigestToo`
covers that path directly) of the bytes being split and stamps it onto
the `index == 0` fragment only — both of `splitFragments`'s two call
sites (Paper's
`buildCodes`, Content's multi-code path) pick this up for free, since
neither builds fragments by any other route. `TagDropPayload.kt`'s
`SplitFragment` gained a `payloadHash: ByteArray?` field (with the same
`contentEquals`-based `equals`/`hashCode` override discipline its other
`ByteArray` properties already use, per the CI-blocking-test-bug lesson
above). `SectorAssembler.kt`'s `Group` gained a `payloadHash` field,
captured in `add()` whenever a fragment carries one; `computeState()`
was rewritten to require it (`?: return State.HashMismatch`) and verify
it (`payloadHashMatches`, new — recognizes only sha2-256, `0x12`,
treating an unrecognized multicodec function code as
present-but-unverifiable rather than a mismatch, mirroring the
reference JS `qdef` package's own "skip silently" handling of a hash
function it doesn't recognize) instead of the old
`sha256(wrapped).copyOf(8).contentEquals(group.groupId)` check — the
`State.HashMismatch` terminal state and its doc comment were kept and
repurposed rather than renamed, since it's still the correct "Split
reassembly integrity failed" signal, just triggered by a different
field now. `group_id`'s own generation (`SHA-256(body)[0:8]`, computed
once per group and reused across every fragment) was left unchanged —
still a valid correlation token under the new rules (QDEF's own
"RECOMMENDED: random" guidance is a recommendation, not a requirement;
"any encoder-chosen scheme producing a value shared identically across
a group's fragments is valid"), and changing it wasn't part of what
this task asked for.

**Tests:** `SectorAssemblerTest.kt`'s `splitFragmentBytes`/`splitRecords`
helpers gained a `payloadHash`/`includePayloadHash` parameter (default
on, so every existing happy-path test keeps working unmodified); the old
`groupIdMismatchDetected` was renamed `payloadHashMismatchDetected` with
its comment corrected to explain *why* the same tamper still gets
caught (fragment 0's `payload_hash`, not `group_id`, is what no longer
matches); a new `missingPayloadHashRejected` covers the mandatory-field
rejection path directly, and a `payloadHashAcceptsFullUntruncatedDigestToo`
covering the decoder's length-generic verification (a hand-built
full-32-byte-digest fragment 0, confirming it still verifies correctly
even though TagDrop's own encoder never emits one). `TagDropCodecTest.kt`
got the equivalent rename/fix (`multiCodePayloadHashMismatchIsHashMismatch`)
plus a new `multiCodeMissingPayloadHashOnFragmentZeroIsRejected`, built by
hand-stripping key `11` from a real encoder-built fragment 0 rather than a
synthetic fixture. Verified via the same standalone `kotlinc`+JUnit
harness this project's QDEF port has used throughout (freshly assembled
again this session — `kotlin-compiler`/`kotlin-stdlib` 2.0.21,
`kotlin-reflect` 1.6.10, `bcprov-jdk18on` 1.80, `room-common` 2.6.1 for
`ScannedPaper`'s `@Entity`/`@PrimaryKey` annotations, all fetched fresh
from Maven Central/`dl.google.com` since this environment resets
between sessions): 198/198 tests pass across
`TagDropCodecTest`/`SectorAssemblerTest`/`MiniCborTest`/`Base41Test`/
`TagDropPayloadTest` — the 2 formerly-pre-existing failures noted in
earlier sessions' entries are gone (already resolved by an intervening
session, not by this change).

**Outstanding gap, same as every prior port**: a full Android Studio
build hasn't run against this change — this environment's Gradle
wrapper still can't download its own distribution. The standalone
`kotlinc`+JUnit harness remains the substitute verification this
project has relied on throughout its QDEF history.

**Update: JS web tools ported too, closing the drift noted above.**
The initial task explicitly scoped this port to the Kotlin
`data/format` package only, leaving the JS web tools drifted (a
real interop break: the generator wasn't emitting `payload_hash`, so
the just-updated Android app would reject any multi-code payload the
generator built) — flagged as a real gap and followed up in the same
session rather than left for later, per this project's own established
"port both together, or they drift" practice.

`tools/generator/index.html` and `tools/examples/index.html` (byte-
identical codec copies, per "Known duplication" below) both gained the
same `splitFragments()` change as the Kotlin encoder: computes a
multihash (`0x12` sha2-256 prefix + 8-byte truncated digest, via the
already-existing `sha256first8` helper) of the bytes being split and
stamps it onto the `index == 0` fragment only, now `async` since the
digest computation needs to `await`. `tools/reader/index.html`'s
`RecordAssembler` (the decode-side mirror) gained a `payloadHash` field
on its internal `group` object (captured in `add()` from either of the
Paper/Content code paths, whichever fragment carries it), and
`_computeState()` now requires and verifies it (`payloadHashMatches`,
new — length-generic like the Kotlin decoder's equivalent, so a
compliant full-32-byte digest from a non-TagDrop encoder still verifies
correctly) instead of the old `group_id`-as-hash check. `tools/test-
qdef-roundtrip.mjs` (the independent Node reimplementation) got the
matching `splitFragments`/`reassembleSplit` changes, plus its existing
`testTamperedFragmentDetected` adversarial test rewritten to assert on
`payload_hash` mismatch instead of `group_id`, and a new
`testMissingPayloadHashRejected` covering the mandatory-field rejection
path. `tools/test-qr-roundtrip.mjs` deliberately left untouched, per
the version-10 entry's standing exemption (confirmed via an empty `git
diff`) — it was never migrated to array-wrapped Records at all.

**A genuinely new test, not just a ported one**: `tools/test-cross-
tool-roundtrip.mjs` (the real generator→real reader jsdom harness — see
the version-15 history entry above for why this file exists) gained
`testMissingPayloadHashRejected`, building a real Content Extension +
Media Preview via the generator's own encoders and only hand-
constructing the hostile part (a Split fragment via the generator's own
low-level `cborRecord`/`TYPE_SPLIT` primitives, omitting key `11`
entirely), then asserting the real reader's `RecordAssembler.add()`
rejects it as `HashMismatch`. Verified this test actually catches a
regression, not just that it runs: with the reader's new payload_hash
check temporarily disabled (`if (false)` in place of the real
condition), the test failed with the exact wrong-state symptom
(`'Failed'` instead of `'HashMismatch'`); restored, it passes again —
same discipline the file's own header comment already establishes for
its `testOversizedSplitDeclarationsRejected` neighbor.

Verified: `npm run test:qdef` (`test-qdef-roundtrip.mjs`) 12/12,
`npm run test:crosstool` 6/6, `npm run lint:qdef`/`lint:examples` both
clean (15 codes/12 fixtures, 0 errors/warnings), `npm test`
(`test-qr-roundtrip.mjs`) 14/14 — confirmed unaffected, as expected.
`qdef-fixtures.json` regenerated. This closes the drift the version-18
entry above originally flagged — **both implementations are on the
same `payload_hash` shape now**, and the "Wire-format version policy"
note below no longer needs its Kotlin-only caveat.

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

SPEC.md's `version` field (currently `18` — Split Wrapper's `group_id`
is now documented purely as an opaque correlation token, not a verified
content hash; TagDrop adopts a new field, `payload_hash` (Split Wrapper
key `11`), as its own MANDATORY reassembly-integrity check whenever
Split is used — QDEF itself leaves this field OPTIONAL/odd [§5.1,
version 18]; both the Kotlin app and the web tools are on this shape now
(the web tools landed in a same-session follow-up after initially
shipping Kotlin-only — see the version-18 CLAUDE.md history entry's
"Update" note). Older shape, both implementations still share: NFC NDEF
always includes the 4-byte QDEF magic header, same as
byte-mode QR/JABCode; only `tagdrop:` URI still skips it [§2/§12/§14,
version 17]; QDEF Records with declared Type IDs `1`/`2`/`3`/`4`
wire-encoded **negated** under an explicitly-transmitted namespace,
scope decided by typeId sign rather than namespace-item presence
[§2.1a, version 16]; QDEF's own standard Types' payload values at
reserved map key `0` rather than a positional payload slot [§3.1a/§5,
version 15]; see "Two parallel wire-format implementations" above and
the version-14/15/16/17/18 history entries) is independent of the
Android app's `versionName` (currently `2.6.1`, `versionCode` 13) —
bumping one never requires bumping the other. (This note has drifted
stale before — previously said `8`/`2.1.0`, then `15`/`2.5.1`, then
`16`, for a while — a reminder to re-check this line's own numbers
against SPEC.md §14's actual current entry and `app/build.gradle`
rather than trust it silently.)

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
  github.com/mofosyne/tagdrop#66) — **superseded, see "QDEF project moved;
  container discriminator removed entirely" above.** QDEF later dropped
  the separate discriminator item entirely; the byte-cost/exemption
  reasoning below is kept for history but the discriminator itself no
  longer exists on the wire. Landed after the namespace-scoping
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
- **Compile the canonical codec to WASM instead of hand-duplicating it
  across three JS files** (assessed, not started). Raised after the
  SPEC.md version-18 (`payload_hash`) port shipped Kotlin-only and left
  the web tools drifted — the JS side's independent codec copies
  (`tools/generator/index.html`, `tools/reader/index.html`,
  `tools/examples/index.html`, "Known duplication" above) have now
  caused a real, shipped bug at least four separate times per this
  file's own history (the negative-Common-Field-Key decode gap, the
  `RECORD_TYPE_INFO` collision, the stale-5-argument `cborRecord` bug,
  and this version's `payload_hash` gap). A single compiled artifact —
  most plausibly the *canonical* Kotlin codec (`data/format/`) built
  via Kotlin Multiplatform to target both JVM (Android, unchanged) and
  Wasm (all three web tools), rather than a fourth hand-written copy —
  would close off this whole bug class structurally instead of relying
  on remembering to port every change four times. Not unprecedented for
  this codebase: the reader already dynamically loads zxing-wasm from a
  CDN, so "reference a compiled `.wasm` blob at runtime" is an
  established pattern here. The real cost is that it would be *TagDrop's
  own* artifact this time, not a third-party one — someone has to own a
  real build/versioning pipeline for it (likely adopting Kotlin
  Multiplatform, a nontrivial restructuring of the current JVM-only
  Kotlin app), a genuinely bigger commitment than "Known duplication"'s
  already-rejected shared-`.mjs`-module idea (rejected only for needing
  a bundler to reinline) — it also moves editing the web-side codec from
  "open the HTML file, edit the function" to "edit Kotlin, rebuild wasm,
  re-embed/link it," a real workflow change from this project's current
  zero-build-step-for-its-own-code ethos. Deliberately not started;
  revisit if the JS-side drift keeps costing real bugs at this rate.
