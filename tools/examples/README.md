# TagDrop Example Payloads

`index.html` is a **self-contained page** — like
[`../generator/index.html`](../generator/index.html) and
[`../reader/index.html`](../reader/index.html) — containing a fixed set of
example payloads for testing the TagDrop Android app and the web reader. Open
it directly in a browser: it encodes each example and renders the resulting
`tagdrop:` QR codes on page load.

## Contents

- **Standalone QR codes** — plain text, a compressed HTML page, an SVG
  graphic, and JSON data. Each is a complete payload on its own.
- **Multi-code cache** — a short story too large for one QR, split into a
  Manifest QR (type 1) plus three Chunk QRs (type 2). Scan the manifest and
  the chunks (any order) to test reassembly and SHA-256 verification.
- **Paper set** — a manifest QR plus three file QRs (`readme`, `note`,
  `badge`) demonstrating a multi-file paper drop. Scan the manifest first.
- **Mini e-book** — "The Lighthouse Keeper's Almanac": a manifest plus a
  `style.css`, a Markdown cover/table-of-contents (`index.md`), and three
  Markdown chapters that link to each other and the cover via ordinary
  relative links, all sharing the stylesheet (SPEC.md §7). Scan the manifest
  and every file, then open `index.md` and tap through the chapters.
- **Multi-location trail** — three independent paper manifests ("Park Gate",
  "Duck Pond", "Lookout Tower"), each with its own icon and `related` hints to
  its neighbours, sharing one `collection_id`/label/tag. Scan each stop at a
  different location (or with a mock-location app) to see three separate pins
  on the Map tab that still group into one collection.

## Editing the examples

The example payloads are defined as plain JS objects (`STANDALONE_EXAMPLES`,
`MULTI_CHUNK_EXAMPLE`, `PAPER_EXAMPLE`, `BOOK_EXAMPLE`, `TRAIL_COLLECTION`,
`TRAIL_EXAMPLES`) near the top of the `<script>` block in `index.html`. Edit
them directly — there's no build step; just reload the page.

## How it works

`index.html` inlines the same Base45/CBOR-sequence/SHA-256/DEFLATE encoding
used by [`tools/generator/index.html`](../generator/index.html) and the
Android app (see [`SPEC.md`](../../SPEC.md)), using the browser's
SubtleCrypto and CompressionStream APIs. QR codes are rendered onto
`<canvas>` via the [`qrcode`](https://www.npmjs.com/package/qrcode) package,
dynamically imported from a CDN as an ES module on first use — the same one
the generator and reader use, so it requires an internet connection on first
load.
