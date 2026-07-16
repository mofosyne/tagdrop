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
   thorough test suite. Currently on **version 1** wire format (4-item
   CBOR envelope) for both Content and Paper — the QDEF Record port (both
   payload types) is the next major piece of work, now that the JS side
   (below) has landed for both and can serve as a settled target to port
   against, rather than a moving one.
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

SPEC.md's `version` field (currently `5` — QDEF Records plus its outer
framing's mandatory namespace discriminator, §14; the Kotlin app still
uses the pre-QDEF version-1 wire format for both Content and Paper) is
independent of the Android app's `versionName` (currently `2.1.0`,
already accepted by F-Droid as of June 2026) — bumping one never
requires bumping the other.

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
