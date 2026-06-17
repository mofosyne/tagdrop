/**
 * QR round-trip test: build real TagDrop wire-format payloads (Base41 + CBOR
 * sequence + DEFLATE, per SPEC.md §2-5/§16) → render as QR images (alphanumeric
 * `tagdrop:` URIs for Single/Manifest, binary/byte-mode QR for Chunks) → decode
 * with zxing-wasm → re-parse the TagDrop envelope/payload → assert the decoded
 * CBOR fields match what was encoded.
 *
 * This exercises the actual codec (Base41 packing, CBOR-sequence envelope,
 * SHA-256 content-addressing, DEFLATE compression, multi-chunk assembly) end
 * to end through real QR images, not just "can qrcode render this many
 * characters." It also exercises **binary-mode** QR decoding specifically,
 * which is the chunk-rendering path the web generator uses by default
 * (SPEC.md §16, "Binary mode vs alphanumeric Base41") and the reason the
 * reader (`tools/reader/index.html`) swapped its QR-scanning library from
 * jsQR to zxing-wasm — jsQR has a confirmed, unfixed bug
 * (cozmo/jsQR#155) that fails to detect ANY QR symbol landing on QR version
 * 23, alphanumeric or binary. zxing-wasm's `readBarcodes` returns `bytes` —
 * the symbol's raw decoded content regardless of its internal encoding mode —
 * which is what makes verifying the binary-mode chunk path possible here.
 *
 * The Base41/CBOR/SHA-256/DEFLATE encode+decode helpers below are a plain-JS
 * port of the logic inlined in `tools/generator/index.html` (encode side) and
 * `tools/reader/index.html` (decode side) — see this repo's CLAUDE.md
 * ("Known duplication (not yet deduped)") for why those two files don't share
 * a module (deliberately self-contained, no build step) and why this script
 * follows the same pattern instead of importing either HTML file.
 *
 * Run with:
 *   cd tools
 *   npm install     # installs qrcode, zxing-wasm, pngjs (see package.json)
 *   node test-qr-roundtrip.mjs
 */
import QRCode from 'qrcode';
import { readBarcodes } from 'zxing-wasm/reader';

// ── Base41 (QR/URI-safe alphabet, packed like RFC 9285 Base45 — SPEC.md §2) ─
const B41 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$*-.:';

function base41Encode(u8) {
  let s = '';
  for (let i = 0; i < u8.length - 1; i += 2) {
    const n = u8[i] * 256 + u8[i + 1];
    s += B41[n % 41] + B41[Math.floor(n / 41) % 41] + B41[Math.floor(n / 1681)];
  }
  if (u8.length & 1) {
    const n = u8[u8.length - 1];
    s += B41[n % 41] + B41[Math.floor(n / 41)];
  }
  return s;
}

function base41Decode(s) {
  s = s.replace(/[a-z]/g, c => c.toUpperCase());
  if (s.length % 3 === 1) throw new Error('Invalid Base41 length');
  const out = [];
  let i = 0;
  while (i + 2 < s.length) {
    const c0 = B41.indexOf(s[i]), c1 = B41.indexOf(s[i + 1]), c2 = B41.indexOf(s[i + 2]);
    if (c0 < 0 || c1 < 0 || c2 < 0) throw new Error('Invalid Base41 char');
    const n = c0 + c1 * 41 + c2 * 1681;
    if (n > 0xFFFF) throw new Error('Base41 value overflow');
    out.push((n >> 8) & 0xFF, n & 0xFF);
    i += 3;
  }
  if (i < s.length) {
    const c0 = B41.indexOf(s[i]), c1 = B41.indexOf(s[i + 1]);
    if (c0 < 0 || c1 < 0) throw new Error('Invalid Base41 char');
    out.push(c0 + c1 * 41);
  }
  return new Uint8Array(out);
}

// ── Minimal CBOR encoder ─────────────────────────────────────────────────
function writeHead(out, major, n) {
  const m = major << 5;
  if (n <= 23) { out.push(m | n); }
  else if (n <= 0xFF) { out.push(m | 24, n); }
  else if (n <= 0xFFFF) { out.push(m | 25, n >> 8, n & 0xFF); }
  else { out.push(m | 26, (n >>> 24) & 0xFF, (n >>> 16) & 0xFF, (n >>> 8) & 0xFF, n & 0xFF); }
}

function cborValue(out, v) {
  if (v === null || v === undefined) return;
  if (typeof v === 'boolean') { out.push(v ? 0xF5 : 0xF4); }
  else if (typeof v === 'number') { writeHead(out, 0, v); }
  else if (v instanceof Uint8Array) { writeHead(out, 2, v.length); v.forEach(b => out.push(b)); }
  else if (typeof v === 'string') {
    const b = Buffer.from(v, 'utf8');
    writeHead(out, 3, b.length); b.forEach(x => out.push(x));
  } else if (Array.isArray(v)) {
    writeHead(out, 4, v.length); v.forEach(item => cborValue(out, item));
  }
}

function cborMap(pairs) {
  const nonNull = pairs.filter(([, v]) => v !== null && v !== undefined);
  const out = [];
  writeHead(out, 5, nonNull.length);
  nonNull.forEach(([k, v]) => { writeHead(out, 0, k); cborValue(out, v); });
  return new Uint8Array(out);
}

function cborUInt(n) {
  const out = [];
  writeHead(out, 0, n);
  return new Uint8Array(out);
}

function concatBytes(...arrays) {
  const total = arrays.reduce((s, a) => s + a.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const a of arrays) { out.set(a, off); off += a.length; }
  return out;
}

const ENVELOPE_VERSION = 1;
const TYPE_SINGLE = 0, TYPE_MANIFEST = 1, TYPE_CHUNK = 2;

function cborSequence(type, payload) {
  return concatBytes(cborUInt(ENVELOPE_VERSION), cborUInt(type), payload);
}

// ── Minimal CBOR decoder (decodes exactly `n` top-level sequence items,
// returns { items, trailing } — mirrors cborDecodeSequencePrefix in
// tools/reader/index.html, RFC 8742 CBOR Sequences) ────────────────────────
function cborDecodeSequencePrefix(bytes, n) {
  let pos = 0;
  function rb() {
    if (pos >= bytes.length) throw new Error('Unexpected end of CBOR');
    return bytes[pos++];
  }
  function rbs(m) {
    if (pos + m > bytes.length) throw new Error('Truncated CBOR');
    const slice = bytes.slice(pos, pos + m); pos += m; return slice;
  }
  function readArg(info) {
    if (info <= 23) return info;
    if (info === 24) return rb();
    if (info === 25) { return rb() * 256 + rb(); }
    if (info === 26) { let n2 = 0; for (let i = 0; i < 4; i++) n2 = n2 * 256 + rb(); return n2; }
    throw new Error('Unsupported CBOR additional info: ' + info);
  }
  function readVal() {
    const b = rb(), major = b >> 5, a = readArg(b & 0x1F);
    switch (major) {
      case 0: return a;
      case 2: return rbs(a);
      case 3: return Buffer.from(rbs(a)).toString('utf8');
      case 4: return Array.from({ length: a }, readVal);
      case 5: {
        const m = {};
        for (let i = 0; i < a; i++) { const k = readVal(); m[k] = readVal(); }
        return m;
      }
      case 7:
        if (b === 0xF6) return null;
        if (b === 0xF4) return false;
        if (b === 0xF5) return true;
        throw new Error('Unsupported CBOR simple value: 0x' + b.toString(16));
      default: throw new Error('Unsupported CBOR major type: ' + major);
    }
  }
  const items = [];
  for (let i = 0; i < n; i++) items.push(readVal());
  return { items, trailing: bytes.slice(pos) };
}

// ── SHA-256 (Node's native crypto.subtle, no extra dependency) ─────────────
async function sha256(bytes) {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
}
async function sha256first8(bytes) {
  return (await sha256(bytes)).slice(0, 8);
}

// ── DEFLATE (zlib-wrapped, RFC 1950 — Node's native CompressionStream /
// DecompressionStream, matching Android's DeflaterOutputStream) ───────────
async function zlibCompress(bytes) {
  const cs = new CompressionStream('deflate');
  const w = cs.writable.getWriter();
  w.write(bytes); w.close();
  const chunks = [];
  const r = cs.readable.getReader();
  for (;;) { const { done, value } = await r.read(); if (done) break; chunks.push(value); }
  return concatBytes(...chunks);
}
async function zlibDecompress(bytes) {
  const ds = new DecompressionStream('deflate');
  const w = ds.writable.getWriter();
  w.write(bytes).catch(() => {}); w.close().catch(() => {});
  const chunks = [];
  const r = ds.readable.getReader();
  for (;;) { const { done, value } = await r.read(); if (done) break; chunks.push(value); }
  return concatBytes(...chunks);
}

// ── CBOR payload-map keys (SPEC.md §3) ──────────────────────────────────────
const K = {
  CACHE_ID: 2, HINT: 3, MIME: 4, CONTENT: 5,
  CHUNK_COUNT: 6, TOTAL_BYTES: 7, SHA256: 8, CHUNK_INDEX: 9, CHUNK_DATA: 10,
  FILENAME: 11, COMPRESSION: 12,
};

// ── TagDrop encode helpers (SPEC.md §4.1/§4.2/§4.3) ─────────────────────────

/** Builds a Single (type=0) payload — SPEC.md §4.1. */
async function encodeSingle({ hint, filename, mimeType, rawBytes, compress }) {
  let content = rawBytes, compression = null;
  if (compress) { content = await zlibCompress(rawBytes); compression = 1; }
  const cacheId = await sha256first8(rawBytes);
  const seq = cborSequence(TYPE_SINGLE, cborMap([
    [K.CACHE_ID, cacheId],
    [K.HINT, hint || null],
    [K.FILENAME, filename || null],
    [K.MIME, mimeType],
    [K.COMPRESSION, compression],
    [K.CONTENT, content],
  ]));
  return { uri: 'tagdrop:' + base41Encode(seq), cacheId, mimeType, hint, filename, compression, content, rawBytes };
}

/** Builds a Manifest (type=1) + Chunks (type=2) — SPEC.md §4.2/§4.3/§5. */
async function encodeManifestAndChunks({ hint, filename, mimeType, rawBytes, compress, chunkCount }) {
  let compression = null, assembled = rawBytes;
  if (compress) { assembled = await zlibCompress(rawBytes); compression = 1; }
  const cacheId = await sha256first8(rawBytes);
  const totalBytes = assembled.length;
  const sha256Hash = await sha256(assembled);

  const manifestSeq = cborSequence(TYPE_MANIFEST, cborMap([
    [K.CACHE_ID, cacheId],
    [K.HINT, hint || null],
    [K.FILENAME, filename || null],
    [K.MIME, mimeType],
    [K.COMPRESSION, compression],
    [K.CHUNK_COUNT, chunkCount],
    [K.TOTAL_BYTES, totalBytes],
    [K.SHA256, sha256Hash],
  ]));
  const manifestUri = 'tagdrop:' + base41Encode(manifestSeq);

  const chunkSize = Math.ceil(totalBytes / chunkCount);
  const chunks = [];
  for (let i = 0; i < chunkCount; i++) {
    const start = Math.min(i * chunkSize, totalBytes);
    const data = assembled.slice(start, Math.min(start + chunkSize, totalBytes));
    const chunkSeq = cborSequence(TYPE_CHUNK, cborMap([
      [K.CACHE_ID, cacheId],
      [K.CHUNK_INDEX, i],
      [K.CHUNK_DATA, data],
    ]));
    chunks.push({ index: i, raw: chunkSeq });
  }

  return { manifestUri, chunks, cacheId, totalBytes, sha256: sha256Hash, chunkCount, compression, mimeType, hint, filename, rawBytes };
}

// ── TagDrop decode helpers (mirrors parseEnvelope() in tools/reader/index.html) ─
function parseEnvelope(seq) {
  const { items, trailing } = cborDecodeSequencePrefix(seq, 3);
  if (items.length < 3) throw new Error('Expected version, type, payload');
  const [version, kind, m] = items;
  if (version !== ENVELOPE_VERSION) throw new Error('Unsupported version: ' + version);

  if (kind === TYPE_SINGLE) {
    return {
      type: 'single',
      cacheId: m[K.CACHE_ID],
      hint: m[K.HINT] || null,
      filename: m[K.FILENAME] || null,
      mimeType: m[K.MIME] || '',
      content: m[K.CONTENT] || new Uint8Array(0),
      compression: m[K.COMPRESSION] || 0,
      trailing,
    };
  }
  if (kind === TYPE_MANIFEST) {
    return {
      type: 'manifest',
      cacheId: m[K.CACHE_ID],
      hint: m[K.HINT] || null,
      filename: m[K.FILENAME] || null,
      mimeType: m[K.MIME],
      compression: m[K.COMPRESSION] || 0,
      chunkCount: m[K.CHUNK_COUNT],
      totalBytes: m[K.TOTAL_BYTES],
      sha256: m[K.SHA256],
    };
  }
  if (kind === TYPE_CHUNK) {
    return {
      type: 'chunk',
      cacheId: m[K.CACHE_ID],
      index: m[K.CHUNK_INDEX],
      data: m[K.CHUNK_DATA],
    };
  }
  throw new Error('Unknown type: ' + kind);
}

function bytesEqual(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

// ── QR rendering + zxing-wasm decoding ──────────────────────────────────────
async function renderTextQr(uri, width) {
  return QRCode.toBuffer(uri, { errorCorrectionLevel: 'L', margin: 2, width, type: 'png' });
}

async function renderByteQr(bytes, width) {
  return QRCode.toBuffer([{ data: Buffer.from(bytes), mode: 'byte' }],
    { errorCorrectionLevel: 'L', margin: 2, width, type: 'png' });
}

// Wraps zxing-wasm's readBarcodes. `result.bytes` is the symbol's raw decoded
// content regardless of its internal encoding mode (alphanumeric vs
// byte/binary) — exactly what tools/reader/index.html's scanQr() relies on,
// and the reason zxing-wasm replaced jsQR there.
async function scanQr(pngBuf) {
  const results = await readBarcodes(pngBuf, { formats: ['QRCode'], tryHarder: true });
  return results[0] || null;
}

// ── Test cases ───────────────────────────────────────────────────────────
let pass = 0, fail = 0;

function ok(label) { console.log(`  ${label} … ok`); pass++; }
function bad(label, msg) { console.log(`  ${label} … FAIL  ${msg}`); fail++; }

async function testSingle(label, { hint, filename, mimeType, rawBytes, compress }, width) {
  let encoded;
  try {
    encoded = await encodeSingle({ hint, filename, mimeType, rawBytes, compress });
  } catch (e) {
    bad(label, `encode threw: ${e.message}`); return;
  }

  let pngBuf;
  try {
    pngBuf = await renderTextQr(encoded.uri, width);
  } catch (e) {
    bad(label, `URI too long for any QR version (${encoded.uri.length} chars): ${e.message}`); return;
  }

  const result = await scanQr(pngBuf);
  if (!result) { bad(label, `rendered at ${width}px but zxing-wasm could not decode it`); return; }

  const decodedUri = Buffer.from(result.bytes).toString('utf8');
  if (decodedUri !== encoded.uri) { bad(label, 'decoded URI text does not match encoded URI'); return; }

  let parsed;
  try {
    parsed = parseEnvelope(base41Decode(decodedUri.slice('tagdrop:'.length)));
  } catch (e) {
    bad(label, `re-decoding TagDrop envelope failed: ${e.message}`); return;
  }

  if (parsed.type !== 'single') return bad(label, `expected type single, got ${parsed.type}`);
  if (!bytesEqual(parsed.cacheId, encoded.cacheId)) return bad(label, 'cache_id mismatch');
  if (parsed.mimeType !== mimeType) return bad(label, 'mime_type mismatch');
  if (parsed.hint !== (hint || null)) return bad(label, 'hint mismatch');
  if (parsed.filename !== (filename || null)) return bad(label, 'filename mismatch');
  if (parsed.compression !== (compress ? 1 : 0)) return bad(label, 'compression flag mismatch');

  const finalContent = parsed.compression ? await zlibDecompress(parsed.content) : parsed.content;
  if (!bytesEqual(finalContent, rawBytes)) return bad(label, 'decoded content bytes do not match original');

  ok(`${label} (URI ${encoded.uri.length} chars, ${width}px, text-mode QR)`);
}

async function testManifestAndChunks(label, { hint, filename, mimeType, rawBytes, compress, chunkCount }, width) {
  let encoded;
  try {
    encoded = await encodeManifestAndChunks({ hint, filename, mimeType, rawBytes, compress, chunkCount });
  } catch (e) {
    bad(label, `encode threw: ${e.message}`); return;
  }

  // Manifest: always alphanumeric tagdrop: URI (SPEC.md §16) — text-mode QR.
  let manifestPng;
  try {
    manifestPng = await renderTextQr(encoded.manifestUri, width);
  } catch (e) {
    bad(`${label} manifest`, `URI too long for any QR version: ${e.message}`); return;
  }
  const manifestResult = await scanQr(manifestPng);
  if (!manifestResult) { bad(`${label} manifest`, 'zxing-wasm could not decode rendered manifest QR'); return; }
  const manifestUriDecoded = Buffer.from(manifestResult.bytes).toString('utf8');
  if (manifestUriDecoded !== encoded.manifestUri) { bad(`${label} manifest`, 'decoded manifest URI mismatch'); return; }

  let manifestParsed;
  try {
    manifestParsed = parseEnvelope(base41Decode(manifestUriDecoded.slice('tagdrop:'.length)));
  } catch (e) {
    bad(`${label} manifest`, `re-decode failed: ${e.message}`); return;
  }
  if (manifestParsed.type !== 'manifest') { bad(`${label} manifest`, `expected type manifest, got ${manifestParsed.type}`); return; }
  if (!bytesEqual(manifestParsed.cacheId, encoded.cacheId)) { bad(`${label} manifest`, 'cache_id mismatch'); return; }
  if (manifestParsed.chunkCount !== chunkCount) { bad(`${label} manifest`, 'chunk_count mismatch'); return; }
  if (manifestParsed.totalBytes !== encoded.totalBytes) { bad(`${label} manifest`, 'total_bytes mismatch'); return; }
  if (!bytesEqual(manifestParsed.sha256, encoded.sha256)) { bad(`${label} manifest`, 'sha256 mismatch'); return; }
  if (manifestParsed.mimeType !== mimeType) { bad(`${label} manifest`, 'mime_type mismatch'); return; }
  ok(`${label} manifest (URI ${encoded.manifestUri.length} chars, ${width}px, text-mode QR)`);

  // Chunks: rendered in binary/byte-mode QR (the web generator's default,
  // SPEC.md §16) — raw CBOR sequence bytes, no tagdrop:/Base41 wrapper.
  const assembledParts = new Array(chunkCount);
  for (const chunk of encoded.chunks) {
    let chunkPng;
    try {
      chunkPng = await renderByteQr(chunk.raw, width);
    } catch (e) {
      bad(`${label} chunk ${chunk.index}`, `byte-mode QR render failed: ${e.message}`); return;
    }
    const chunkResult = await scanQr(chunkPng);
    if (!chunkResult) { bad(`${label} chunk ${chunk.index}`, `zxing-wasm could not decode binary-mode QR at ${width}px`); return; }
    if (!bytesEqual(chunkResult.bytes, chunk.raw)) { bad(`${label} chunk ${chunk.index}`, 'decoded raw bytes do not match encoded chunk bytes'); return; }

    let chunkParsed;
    try {
      chunkParsed = parseEnvelope(chunkResult.bytes);
    } catch (e) {
      bad(`${label} chunk ${chunk.index}`, `re-decode failed: ${e.message}`); return;
    }
    if (chunkParsed.type !== 'chunk') { bad(`${label} chunk ${chunk.index}`, `expected type chunk, got ${chunkParsed.type}`); return; }
    if (!bytesEqual(chunkParsed.cacheId, encoded.cacheId)) { bad(`${label} chunk ${chunk.index}`, 'cache_id mismatch'); return; }
    if (chunkParsed.index !== chunk.index) { bad(`${label} chunk ${chunk.index}`, 'chunk_index mismatch'); return; }
    assembledParts[chunkParsed.index] = chunkParsed.data;
  }
  ok(`${label} ${chunkCount} chunk(s) (${encoded.totalBytes} assembled bytes, ~${Math.ceil(encoded.totalBytes / chunkCount)} bytes/chunk, ${width}px, binary-mode QR)`);

  // Assembly protocol (SPEC.md §5): concatenate by ascending chunk_index, verify SHA-256.
  const assembled = Buffer.concat(assembledParts.map(p => Buffer.from(p)));
  const assembledHash = await sha256(assembled);
  if (!bytesEqual(assembledHash, encoded.sha256)) { bad(`${label} assembly`, 'SHA-256 of assembled chunks does not match manifest sha256'); return; }

  const finalContent = manifestParsed.compression ? await zlibDecompress(assembled) : assembled;
  if (!bytesEqual(finalContent, rawBytes)) { bad(`${label} assembly`, 'assembled+decompressed content does not match original rawBytes'); return; }
  ok(`${label} assembly + integrity check`);
}

// ── Run ──────────────────────────────────────────────────────────────────
const WIDTHS = [400, 1024]; // 400px on-screen preview, 1024px Download PNG.

console.log('QR round-trip test (real TagDrop wire format)\n');

// Small Single: short plain-text content, no compression — fits comfortably
// in one QR as an alphanumeric tagdrop: URI.
const smallText = 'Under the bridge, leave no trace. See you on the trail!';
for (const w of WIDTHS) {
  console.log(`Small Single (~${smallText.length}-byte text/plain, uncompressed):`);
  await testSingle('preview/download', {
    hint: 'under the bridge', filename: null, mimeType: 'text/plain',
    rawBytes: new TextEncoder().encode(smallText), compress: false,
  }, w);
}

// Medium Single: a repetitive HTML page large enough that DEFLATE
// meaningfully shrinks it back under a comfortable single-QR size.
const mediumHtml = '<!doctype html><html><body>' +
  '<p>Trail stop 3 — Oak Tree. '.repeat(40) +
  '</body></html>';
for (const w of WIDTHS) {
  console.log(`Medium Single (~${mediumHtml.length}-byte text/html, DEFLATE-compressed):`);
  await testSingle('preview/download', {
    hint: 'oak tree', filename: 'story.html', mimeType: 'text/html',
    rawBytes: new TextEncoder().encode(mediumHtml), compress: true,
  }, w);
}

// Large content: too big for one QR — split into a Manifest + multiple
// Chunks (SPEC.md §4.2/§4.3/§5). Manifest stays alphanumeric tagdrop:;
// chunks render as binary/byte-mode QR, exercising the exact path the
// jsQR -> zxing-wasm swap was fixing. Built from varied (non-repeating)
// pseudo-random words rather than one repeated phrase, so DEFLATE only
// achieves a realistic ~50-70% reduction (SPEC.md §8) instead of collapsing
// to a few dozen bytes — each chunk ends up a realistic size, in the
// ballpark of the ~600-bytes-decoded chunk size SPEC.md §6 recommends.
function pseudoRandomStory(targetBytes, seed) {
  const words = ['trail', 'stop', 'oak', 'tree', 'sticker', 'hunt', 'finder', 'scan', 'code', 'paper',
    'manifest', 'chunk', 'spring', 'trailhead', 'letterbox', 'compass', 'forest', 'creek', 'bridge',
    'lantern', 'map', 'clue', 'token', 'badge', 'ranger', 'meadow', 'summit', 'cache', 'token', 'wander'];
  let s = 0x9e3779b9 ^ seed;
  function next() { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; s |= 0; return (s >>> 0) / 0xFFFFFFFF; }
  let out = '<!doctype html><html><body><p>';
  while (out.length < targetBytes) {
    out += words[Math.floor(next() * words.length)] + ' ';
  }
  out += '</p></body></html>';
  return out;
}
const largeHtml = pseudoRandomStory(5000, 42);
for (const w of WIDTHS) {
  console.log(`Large content (~${largeHtml.length}-byte text/html, Manifest + 4 Chunks, DEFLATE-compressed):`);
  await testManifestAndChunks('Manifest+Chunks', {
    hint: 'spring trail story', filename: 'trail-story.html', mimeType: 'text/html',
    rawBytes: new TextEncoder().encode(largeHtml), compress: true, chunkCount: 4,
  }, w);
}

console.log(`\n${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
