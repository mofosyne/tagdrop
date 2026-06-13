# CLAUDE.md

Project-specific notes for Claude Code sessions working on this repo. For
user-facing docs see `readme.md`; for the wire format see `SPEC.md`; for
Android build setup see `DEVELOPING.md`.

## Three parallel wire-format implementations

The `tagdrop:<base45-cbor-sequence>` encoding (Base45 + CBOR-sequence
envelope + DEFLATE, see `SPEC.md`) is implemented **independently three
times**:

1. **Kotlin (Android app)** — `app/src/main/java/.../data/format/`
   (`Base45.kt`, `MiniCbor.kt`, `TagDropCodec.kt`). This is the canonical
   implementation; `app/src/test/.../TagDropCodecTest.kt` is the most
   thorough test suite.
2. **Browser JS** — inline `<script>` in `tools/generator/index.html`
   (encode side) and `tools/reader/index.html` (decode side). SHA-256 via
   `crypto.subtle`, DEFLATE via `CompressionStream`/`DecompressionStream`.
3. **Node JS** — `tools/examples/generate.mjs` (encode side only, used to
   regenerate `tools/examples/index.html`). SHA-256 via `node:crypto`,
   DEFLATE via `node:zlib`.

When SPEC.md changes (e.g. the CBOR-sequence envelope refactor done in this
session), **all three** need updating and re-verifying, or they silently
drift apart. There's currently no automated cross-check between them —
verification has so far been manual (decode every URI in
`tools/examples/index.html` and check version/type/fields match).

### Known duplication / refactor idea (not yet done)

`tools/generator/index.html`'s encode-side helpers (`base45Encode`,
`writeHead`/`cborValue`/`cborMap`/`cborSequence`/etc., `encodeSingle`,
`encodePaperManifest`) are near-byte-identical to the ones in
`tools/examples/generate.mjs` — the latter was copy-pasted from the former.
`tools/reader/index.html` has the decode-side mirror (`base45Decode`,
`cborDecodeSequence`, etc.).

A shared module would reduce drift, **but** note: `tools/generator/index.html`
and `tools/reader/index.html` are deliberately **self-contained single HTML
files** (say so in their own header comments) — the only external dependency
is a CDN script for QR rendering/scanning (`qrcode`/`jsQR`), not the codec
logic. Splitting the codec into an importable `tools/shared/*.mjs` would
break that "download one file, it just works offline" property unless paired
with a build step that inlines it back into the HTML (extra tooling).

Lower-risk options if this gets picked up:
- Extract a shared `tools/shared/tagdrop-codec.mjs` for **`generate.mjs`
  only** (it already requires `npm install`, so no single-file constraint) —
  dedupes the Node copy against a canonical JS source.
- Add an automated round-trip/cross-check test (e.g. encode with one
  implementation's logic, decode with another's, compare) instead of/as well
  as deduping, to catch drift between generator/reader/generate.mjs/Kotlin
  without forcing the single-file HTML files to change.

## Authoring tools: web generator is primary

`tools/generator/index.html` (Single File + Paper Layout tabs) is the
intended primary tool for *creating* TagDrop codes and printable paper
layouts — it's faster to develop/iterate on (plain browser HTML/JS, no
build step) and has more screen space for the multi-file paper form than a
phone.

The Android app's in-app creation screens (`CreateActivity` = single code,
`CreatePaperActivity` = multi-file paper + print/PDF via the system print
dialog, added on branch `claude/paper-pdf-export`) are considered
**secondary/optional** — useful for "no computer available" scenarios, but
not the priority for new authoring features. New paper-layout features
should land in the web generator first; porting to the Android app is
optional follow-up.

## Branch/remote notes

- The repo's default branch is `master`, not `main`.
