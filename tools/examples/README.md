# TagDrop Example Payloads

`index.html` is a **generated file** containing pre-rendered QR codes for
testing the TagDrop Android app and the [web reader](../reader/index.html).
It's fully static — open it directly in a browser, no internet connection
or JS crypto/compression APIs required.

## Contents

- **Standalone QR codes** — plain text, a compressed HTML page, an SVG
  graphic, and JSON data. Each is a complete payload on its own.
- **Multi-code cache** — a short story too large for one QR, split into a
  Manifest QR (`v1/m`) plus three Chunk QRs (`v1/c`). Scan the manifest and
  the chunks (any order) to test reassembly and SHA-256 verification.
- **Paper set** — a manifest QR plus three file QRs (`readme`, `note`,
  `badge`) demonstrating a multi-file paper drop. Scan the manifest first.
- **Multi-location trail** — three independent paper manifests ("Park Gate",
  "Duck Pond", "Lookout Tower"), each with its own icon and `related` hints to
  its neighbours, sharing one `collection_id`/label/tag. Scan each stop at a
  different location (or with a mock-location app) to see three separate pins
  on the Map tab that still group into one collection.

## Regenerating

The page is built by `generate.mjs` from the example definitions at the top
of that file. To change the examples or regenerate after editing:

```bash
cd tools/examples
npm install
npm run build
```

This overwrites `index.html`. Commit the regenerated file along with any
changes to `generate.mjs`.

## How it works

`generate.mjs` re-implements the same Base45/CBOR/SHA-256/DEFLATE encoding
used by [`tools/generator/index.html`](../generator/index.html) and the
Android app (see [`SPEC.md`](../../SPEC.md)), but runs it in Node at build
time using `node:crypto` and `node:zlib` instead of the browser's
SubtleCrypto/CompressionStream APIs. QR codes are rendered as inline SVG via
the [`qrcode`](https://www.npmjs.com/package/qrcode) package, so the output
page needs no `<canvas>` or CDN script.
