# CLAUDE.md

Project-specific notes for Claude Code sessions working on this repo. For
user-facing docs see `readme.md`; for the wire format see `SPEC.md`; for
Android build setup see `DEVELOPING.md`.

## Two parallel wire-format implementations

The `tagdrop:<base41-cbor-sequence>` encoding (Base41 + CBOR-sequence
envelope + DEFLATE, see `SPEC.md`) is implemented **independently twice**:

1. **Kotlin (Android app)** — `app/src/main/java/.../data/format/`
   (`Base41.kt`, `MiniCbor.kt`, `TagDropCodec.kt`). This is the canonical
   implementation; `app/src/test/.../TagDropCodecTest.kt` is the most
   thorough test suite.
2. **Browser JS** — inline `<script>` in `tools/generator/index.html` and
   `tools/examples/index.html` (encode side), `tools/reader/index.html`
   (decode side). SHA-256 via `crypto.subtle`, DEFLATE via
   `CompressionStream`/`DecompressionStream`.

When SPEC.md changes (e.g. the CBOR-sequence envelope refactor done in an
earlier session), **both** need updating and re-verifying, or they silently
drift apart. There's currently no automated cross-check between them —
verification has so far been manual (decode every URI in
`tools/examples/index.html` and check version/type/fields match).

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
no equivalent export/import yet — `SigningIdentityStore` is device-scoped
only; add it there too if cross-device continuity for the Android identity
is ever needed. The Kotlin app now also does real ML-DSA-44 sign/verify,
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
verified/invalid/pending. `CreatePaperActivity` does **not** yet have a
signing checkbox — per "web generator first" above, paper-layout signing UI
should land there before the Android app, and the web generator's Paper
Layout tab doesn't have one yet either (only its Single File tab does).

### Known duplication (not yet deduped)

`tools/generator/index.html`'s codec helpers — Base41 (`base41Encode`/
`base41Decode`), CBOR (`writeHead`/`cborValue`/`cborMap`/`cborUInt`/
`cborBytesItem`/`cborDecodeSequencePrefix`/etc.), sector framing
(`sectorCbor`/`encodeSector`/`sectorize`/`buildStream`/
`splitReassembledStream`), crypto (`encryptAesGcm`/`encryptOverrideMap`/
`deriveKeyFromPassphrase`/`generateKeyMaterial`), and the sector builders
(`createContentSectors`/`createContentSectorsAutoSized`/
`createKeyCodeSector`/`buildPaperStream`/`createPaper`/
`createPaperAutoSized`) are byte-identical (same names, same bodies) to the
copies inlined in `tools/examples/index.html`, which adds only its own
example-data constants and rendering (`addCard`/`addContentSectorCards`/
`addPaperSectorCards`) on top. `tools/reader/index.html` has the decode-side
mirror (`base41Decode`, `cborDecodeSequencePrefix`, `parseContentStream`,
`parsePaperStream`, `SectorAssembler`, etc.) plus its own UI/IndexedDB
persistence layer.

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

SPEC.md's `version` field (currently `1`) is independent of the Android
app's `versionName` (currently `2.1.0`, already accepted by F-Droid as of
June 2026) — bumping one never requires bumping the other.

Version 1 is currently a **draft, not frozen** (see SPEC.md's `Status`
line): no real TagDrop code has been printed or distributed yet, so no
deployed content depends on its exact byte layout. F-Droid accepting the
app only means a binary *could* eventually reach a stranger — it doesn't
mean any version-1 *content* exists yet for that binary to misread.
Breaking changes (key reuse, envelope changes, semantics changes) are fine
without a version bump **until the first real code is deployed** — printed,
shared, or otherwise placed somewhere a third party might scan it. At that
point treat version 1 as frozen: breaking changes from then on require a
version bump (SPEC.md §14), and SPEC.md's `Status` line should flip back to
`Stable`.

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

## Branch/remote notes

- The repo's default branch is `master`, not `main`.

## Future ideas / backlog (not yet implemented)

Ideas raised and assessed but deliberately deferred — revisit if they come up
again or a concrete need emerges.

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
