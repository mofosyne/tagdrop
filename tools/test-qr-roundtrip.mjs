/**
 * QR round-trip test: build real TagDrop wire-format payloads (Base41 + CBOR
 * sequence + DEFLATE, per SPEC.md §2-5) → render as QR images (alphanumeric
 * `tagdrop:` URIs for single-sector content, binary/byte-mode QR for
 * multi-sector content) → decode with zxing-wasm → re-parse the TagDrop
 * sector envelope/content stream → assert the decoded CBOR fields match what
 * was encoded.
 *
 * This exercises the actual codec (Base41 packing, CBOR-sequence sector
 * envelope, SHA-256 content-addressing, DEFLATE compression, multi-sector
 * assembly) end to end through real QR images, not just "can qrcode render
 * this many characters." It also exercises **binary-mode** QR decoding
 * specifically, which is the sector-rendering path the web generator uses by
 * default for multi-sector content (the "Use binary QR for sectors"
 * checkbox, checked by default) and the reason the reader
 * (`tools/reader/index.html`) swapped its QR-scanning library from jsQR to
 * zxing-wasm — jsQR has a confirmed, unfixed bug (cozmo/jsQR#155) that fails
 * to detect ANY QR symbol landing on QR version 23, alphanumeric or binary.
 * zxing-wasm's `readBarcodes` returns `bytes` — the symbol's raw decoded
 * content regardless of its internal encoding mode — which is what makes
 * verifying the binary-mode sector path possible here.
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
 *   npm install     # installs qrcode, zxing-wasm (see package.json)
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

// A raw top-level CBOR byte-string item (major type 2) — e.g. a sector's sector_bytes.
function cborBytesItem(bytes) {
  const out = [];
  writeHead(out, 2, bytes.length);
  bytes.forEach(b => out.push(b));
  return new Uint8Array(out);
}

function concatBytes(...arrays) {
  const total = arrays.reduce((s, a) => s + a.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const a of arrays) { out.set(a, off); off += a.length; }
  return out;
}

const VERSION = 1;
const TYPE_CONTENT = 0;

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

function toHex(bytes) {
  return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
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

// ── CBOR payload-map keys (SPEC.md §3/§4.1) ─────────────────────────────────
const K = {
  CACHE_ID: 2, HINT: 3, MIME: 4,
  TOTAL_BYTES: 7, CONTENT_SHA: 8,
  FILENAME: 11, COMPRESSION: 12,
  SECTOR_INDEX: 42, SECTOR_COUNT: 43, PARITY: 44,
};

// ── TagDrop sector encode (SPEC.md §2, §4.1) ────────────────────────────────
// tagdrop:<base41( CBOR(version) || CBOR(type) || CBOR(part_meta) || CBOR(sector_bytes) )>
// part_meta (SPEC §4.1): cache_id, total_bytes, 0-based sector_index, sector_count,
// parity_scheme (only on a parity sector — not exercised by this test).
function partMetaMap(pm) {
  return cborMap([
    [K.CACHE_ID,     pm.cacheId || null],
    [K.TOTAL_BYTES,  pm.totalBytes],
    [K.SECTOR_INDEX, pm.sectorIndex],
    [K.SECTOR_COUNT, pm.sectorCount],
    [K.PARITY,       pm.paritySchemeRaw ?? null],
  ]);
}

// A sector's four-item CBOR sequence (SPEC §2) — Base41-encoded for `tagdrop:`, or
// carried raw on byte carriers (SPEC §13, e.g. a binary/byte-mode QR).
function sectorCbor(sector) {
  return concatBytes(
    cborUInt(VERSION), cborUInt(sector.type),
    partMetaMap(sector.partMeta), cborBytesItem(sector.sectorBytes)
  );
}

function encodeSector(sector) {
  const raw = sectorCbor(sector);
  return { uri: 'tagdrop:' + base41Encode(raw), raw, sector };
}

// Slices a reassembled `stream` into data sectors of at most `maxSectorDataBytes`
// bytes each (SPEC §4.1). Mirrors tools/generator/index.html's sectorize verbatim.
function sectorize(type, cacheId, stream, maxSectorDataBytes) {
  const total = stream.length;
  const count = (maxSectorDataBytes <= 0 || total <= maxSectorDataBytes)
    ? 1 : Math.ceil(total / maxSectorDataBytes);
  const sectorSize = Math.ceil(total / count);
  const sectors = [];
  for (let i = 0; i < count; i++) {
    const start = Math.min(i * sectorSize, total);
    const end = Math.min(start + sectorSize, total);
    sectors.push({
      type,
      partMeta: { cacheId, sectorIndex: i, sectorCount: count, totalBytes: total, paritySchemeRaw: null },
      sectorBytes: stream.slice(start, end),
    });
  }
  return sectors;
}

// Concatenates CBOR(core_meta_item) || CBOR(bulky_meta_item) || content (SPEC §4.2).
function buildStream(corePairs, bulkyPairs, content) {
  return concatBytes(cborMap(corePairs), cborMap(bulkyPairs), content);
}

/**
 * Builds Content sector(s) (SPEC §4.1/§4.2) for a file/text payload. Mirrors
 * tools/generator/index.html's createContentSectors, minus the encryption/
 * collection/key fields this test doesn't exercise. content_sha256 is only
 * included in core_meta_item when the unsplit stream doesn't fit in one
 * sector — single-sector payloads omit it as redundant (the sector's own
 * QR/CBOR framing is already integrity-checked).
 */
async function encodeContentSectors({ hint, filename, mimeType, rawBytes, compress, maxSectorDataBytes = Infinity }) {
  let compression = null, content = rawBytes;
  if (compress) { content = await zlibCompress(rawBytes); compression = 1; }
  const cacheId = await sha256first8(rawBytes);

  async function core(withSha) {
    return [
      [K.HINT,        hint || null],
      [K.FILENAME,    filename || null],
      [K.MIME,        mimeType],
      [K.COMPRESSION, compression],
      [K.CONTENT_SHA, withSha ? await sha256(content) : null],
    ];
  }

  const single = buildStream(await core(false), [], content);
  const stream = single.length <= maxSectorDataBytes ? single : buildStream(await core(true), [], content);
  return sectorize(TYPE_CONTENT, cacheId, stream, maxSectorDataBytes);
}

// ── TagDrop sector decode (mirrors decodeSector() in tools/reader/index.html) ─
function decodeSector(bytes) {
  const { items } = cborDecodeSequencePrefix(bytes, 4);
  const [version, type, pm, sectorBytes] = items;
  if (version !== VERSION) throw new Error('Unsupported version: ' + version);
  const cacheIdBytes = pm[K.CACHE_ID];
  return {
    type,
    partMeta: {
      cacheId:         cacheIdBytes ? toHex(cacheIdBytes) : null,
      sectorIndex:     pm[K.SECTOR_INDEX] ?? 0,
      sectorCount:     pm[K.SECTOR_COUNT] ?? 1,
      totalBytes:      pm[K.TOTAL_BYTES] ?? sectorBytes.length,
      paritySchemeRaw: pm[K.PARITY] ?? null,
    },
    sectorBytes,
  };
}

// Concatenates sectors by ascending sector_index into the reassembled stream (SPEC §5).
function reassembleSectors(sectors) {
  const count = sectors[0].partMeta.sectorCount;
  const byIndex = new Array(count);
  for (const s of sectors) byIndex[s.partMeta.sectorIndex] = s.sectorBytes;
  if (byIndex.some(b => !b)) throw new Error('missing sector(s)');
  return concatBytes(...byIndex);
}

// Splits a reassembled stream into core_meta_item, bulky_meta_item, content (SPEC
// §4.2). Mirrors splitReassembledStream() in tools/reader/index.html (this test never
// exercises bulky_meta_compression, so that branch is omitted here).
function splitReassembledStream(stream) {
  const { items: coreItems, trailing: afterCore } = cborDecodeSequencePrefix(stream, 1);
  const core = coreItems[0];
  const { items: bulkyItems, trailing: afterBulky } = cborDecodeSequencePrefix(afterCore, 1);
  const bulky = bulkyItems[0];
  return { core, bulky, content: afterBulky };
}

function bytesEqual(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/**
 * Parses a reassembled Content stream into its fields (SPEC §4.2, §5), verifying
 * content_sha256 if present. Mirrors parseContentStream() in
 * tools/reader/index.html. Returns { kind: 'Ok', content } | { kind: 'HashMismatch' }.
 */
async function parseContentStream(stream, partMeta) {
  const { core, content: slot } = splitReassembledStream(stream);
  const declaredSha = core[K.CONTENT_SHA];
  if (declaredSha && !bytesEqual(await sha256(slot), declaredSha)) {
    return { kind: 'HashMismatch' };
  }
  return {
    kind: 'Ok',
    content: {
      cacheId:     partMeta.cacheId,
      hint:        core[K.HINT] ?? null,
      filename:    core[K.FILENAME] ?? null,
      mimeType:    core[K.MIME] ?? '',
      compression: core[K.COMPRESSION] ?? 0,
      content:     slot,
    },
  };
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

async function testSingleSector(label, { hint, filename, mimeType, rawBytes, compress }, width) {
  let sectors;
  try {
    sectors = await encodeContentSectors({ hint, filename, mimeType, rawBytes, compress, maxSectorDataBytes: Infinity });
  } catch (e) {
    bad(label, `encode threw: ${e.message}`); return;
  }
  if (sectors.length !== 1) { bad(label, `expected a single sector, got ${sectors.length}`); return; }
  const encoded = encodeSector(sectors[0]);

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

  let sector;
  try {
    sector = decodeSector(base41Decode(decodedUri.slice('tagdrop:'.length)));
  } catch (e) {
    bad(label, `re-decoding sector failed: ${e.message}`); return;
  }
  if (sector.type !== TYPE_CONTENT) return bad(label, `expected type Content, got ${sector.type}`);
  if (sector.partMeta.cacheId !== toHex(sectors[0].partMeta.cacheId)) return bad(label, 'cache_id mismatch');

  let parsed;
  try {
    parsed = await parseContentStream(sector.sectorBytes, sector.partMeta);
  } catch (e) {
    bad(label, `re-decoding content stream failed: ${e.message}`); return;
  }
  if (parsed.kind !== 'Ok') return bad(label, `expected kind Ok, got ${parsed.kind}`);
  if (parsed.content.mimeType !== mimeType) return bad(label, 'mime_type mismatch');
  if (parsed.content.hint !== (hint || null)) return bad(label, 'hint mismatch');
  if (parsed.content.filename !== (filename || null)) return bad(label, 'filename mismatch');
  if (parsed.content.compression !== (compress ? 1 : 0)) return bad(label, 'compression flag mismatch');

  const finalContent = parsed.content.compression ? await zlibDecompress(parsed.content.content) : parsed.content.content;
  if (!bytesEqual(finalContent, rawBytes)) return bad(label, 'decoded content bytes do not match original');

  ok(`${label} (URI ${encoded.uri.length} chars, ${width}px, text-mode QR)`);
}

async function testMultiSector(label, { hint, filename, mimeType, rawBytes, compress, maxSectorDataBytes }, width) {
  let sectors;
  try {
    sectors = await encodeContentSectors({ hint, filename, mimeType, rawBytes, compress, maxSectorDataBytes });
  } catch (e) {
    bad(label, `encode threw: ${e.message}`); return;
  }
  if (sectors.length <= 1) { bad(label, `expected multiple sectors, got ${sectors.length}`); return; }
  const cacheIdHex = toHex(sectors[0].partMeta.cacheId);

  // Every sector is rendered in binary/byte-mode QR — the generator's default for
  // multi-sector content ("Use binary QR for sectors", checked by default) — exercising
  // the exact path the jsQR -> zxing-wasm swap was fixing.
  const decodedSectors = [];
  for (const sector of sectors) {
    const raw = sectorCbor(sector);
    let png;
    try {
      png = await renderByteQr(raw, width);
    } catch (e) {
      bad(`${label} sector ${sector.partMeta.sectorIndex}`, `byte-mode QR render failed: ${e.message}`); return;
    }
    const result = await scanQr(png);
    if (!result) { bad(`${label} sector ${sector.partMeta.sectorIndex}`, `zxing-wasm could not decode binary-mode QR at ${width}px`); return; }
    if (!bytesEqual(result.bytes, raw)) { bad(`${label} sector ${sector.partMeta.sectorIndex}`, 'decoded raw bytes do not match encoded sector bytes'); return; }

    let decoded;
    try {
      decoded = decodeSector(result.bytes);
    } catch (e) {
      bad(`${label} sector ${sector.partMeta.sectorIndex}`, `re-decode failed: ${e.message}`); return;
    }
    if (decoded.type !== TYPE_CONTENT) { bad(`${label} sector ${sector.partMeta.sectorIndex}`, `expected type Content, got ${decoded.type}`); return; }
    if (decoded.partMeta.cacheId !== cacheIdHex) { bad(`${label} sector ${sector.partMeta.sectorIndex}`, 'cache_id mismatch'); return; }
    if (decoded.partMeta.sectorCount !== sectors.length) { bad(`${label} sector ${sector.partMeta.sectorIndex}`, 'sector_count mismatch'); return; }
    decodedSectors.push(decoded);
  }
  ok(`${label} ${sectors.length} sector(s) (${sectors[0].partMeta.totalBytes} assembled bytes, ~${Math.ceil(sectors[0].partMeta.totalBytes / sectors.length)} bytes/sector, ${width}px, binary-mode QR)`);

  // Assembly protocol (SPEC §5): reassemble by ascending sector_index, verify content_sha256.
  let parsed;
  try {
    const stream = reassembleSectors(decodedSectors);
    parsed = await parseContentStream(stream, decodedSectors[0].partMeta);
  } catch (e) {
    bad(`${label} assembly`, `reassembly/re-decode failed: ${e.message}`); return;
  }
  if (parsed.kind !== 'Ok') { bad(`${label} assembly`, `expected kind Ok, got ${parsed.kind}`); return; }

  const finalContent = parsed.content.compression ? await zlibDecompress(parsed.content.content) : parsed.content.content;
  if (!bytesEqual(finalContent, rawBytes)) { bad(`${label} assembly`, 'assembled+decompressed content does not match original rawBytes'); return; }
  ok(`${label} assembly + integrity check`);
}

// ── Run ──────────────────────────────────────────────────────────────────
const WIDTHS = [400, 1024]; // 400px on-screen preview, 1024px Download PNG.

console.log('QR round-trip test (real TagDrop sectored wire format)\n');

// Small single-sector Content: short plain-text content, no compression — fits
// comfortably in one QR as an alphanumeric tagdrop: URI.
const smallText = 'Under the bridge, leave no trace. See you on the trail!';
for (const w of WIDTHS) {
  console.log(`Small Content (~${smallText.length}-byte text/plain, uncompressed):`);
  await testSingleSector('preview/download', {
    hint: 'under the bridge', filename: null, mimeType: 'text/plain',
    rawBytes: new TextEncoder().encode(smallText), compress: false,
  }, w);
}

// Medium single-sector Content: a repetitive HTML page large enough that DEFLATE
// meaningfully shrinks it back under a comfortable single-QR size.
const mediumHtml = '<!doctype html><html><body>' +
  '<p>Trail stop 3 — Oak Tree. '.repeat(40) +
  '</body></html>';
for (const w of WIDTHS) {
  console.log(`Medium Content (~${mediumHtml.length}-byte text/html, DEFLATE-compressed):`);
  await testSingleSector('preview/download', {
    hint: 'oak tree', filename: 'story.html', mimeType: 'text/html',
    rawBytes: new TextEncoder().encode(mediumHtml), compress: true,
  }, w);
}

// Large content: too big for one QR — split into multiple equal-status sectors
// (SPEC §4.1/§5), every sector rendered as binary/byte-mode QR, exercising the
// exact path the jsQR -> zxing-wasm swap was fixing. Built from varied
// (non-repeating) pseudo-random words rather than one repeated phrase, so
// DEFLATE only achieves a realistic ~50-70% reduction (SPEC.md §8) instead of
// collapsing to a few dozen bytes — each sector ends up a realistic size.
function pseudoRandomStory(targetBytes, seed) {
  const words = ['trail', 'stop', 'oak', 'tree', 'sticker', 'hunt', 'finder', 'scan', 'code', 'paper',
    'sector', 'parity', 'spring', 'trailhead', 'letterbox', 'compass', 'forest', 'creek', 'bridge',
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
  console.log(`Large content (~${largeHtml.length}-byte text/html, multi-sector, DEFLATE-compressed):`);
  await testMultiSector('Multi-sector', {
    hint: 'spring trail story', filename: 'trail-story.html', mimeType: 'text/html',
    rawBytes: new TextEncoder().encode(largeHtml), compress: true, maxSectorDataBytes: 600,
  }, w);
}

console.log(`\n${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
