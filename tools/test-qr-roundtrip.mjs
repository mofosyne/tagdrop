/**
 * QR round-trip test: build real TagDrop QDEF Record wire-format payloads
 * (Base41 + QDEF Records + DEFLATE, per SPEC.md §2-5) → render as QR images
 * (alphanumeric `tagdrop:` URIs for single-code content, binary/byte-mode QR
 * for multi-code content) → decode with zxing-wasm → re-parse the TagDrop
 * Records → assert the decoded fields match what was encoded.
 *
 * This exercises the actual codec (Base41 packing, QDEF Record encoding,
 * SHA-256 content-addressing, DEFLATE compression, Split Wrapper Records,
 * Compress Wrapper Records) end to end through real QR images. It also
 * exercises **binary-mode** QR decoding specifically, which is the
 * sector-rendering path the web generator uses by default for multi-sector
 * content and the reason the reader (`tools/reader/index.html`) swapped its
 * QR-scanning library from jsQR to zxing-wasm.
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
// BigInt-based: handles 64-bit QDEF Record Type IDs (SPEC.md §2.1).
function writeHead(out, major, n) {
  n = typeof n === 'bigint' ? n : BigInt(n);
  const m = major << 5;
  if (n < 24n) { out.push(m | Number(n)); }
  else if (n < 256n) { out.push(m | 24, Number(n)); }
  else if (n < 65536n) { out.push(m | 25, Number(n >> 8n), Number(n & 0xffn)); }
  else if (n < 4294967296n) {
    out.push(m | 26, Number((n >> 24n) & 0xffn), Number((n >> 16n) & 0xffn), Number((n >> 8n) & 0xffn), Number(n & 0xffn));
  } else {
    out.push(m | 27);
    for (let shift = 56n; shift >= 0n; shift -= 8n) out.push(Number((n >> shift) & 0xffn));
  }
}

function cborFloat64(out, v) {
  out.push((7 << 5) | 27);
  const buf = new ArrayBuffer(8);
  new DataView(buf).setFloat64(0, v, false);
  new Uint8Array(buf).forEach(b => out.push(b));
}

function cfloat(v) { return { __float64: true, value: v }; }

function cborValue(out, v) {
  if (v === null || v === undefined) return;
  if (v && v.__float64) { cborFloat64(out, v.value); }
  else if (typeof v === 'boolean') { out.push(v ? 0xF5 : 0xF4); }
  else if (typeof v === 'bigint') { writeHead(out, 0, v); }
  else if (typeof v === 'number') { writeHead(out, 0, v); }
  else if (v instanceof Uint8Array) { writeHead(out, 2, v.length); v.forEach(b => out.push(b)); }
  else if (typeof v === 'string') {
    const b = Buffer.from(v, 'utf8');
    writeHead(out, 3, b.length); b.forEach(x => out.push(x));
  } else if (v && v.__map) {
    const pairs = v.pairs.filter(([, x]) => x !== null && x !== undefined);
    writeHead(out, 5, pairs.length);
    pairs.forEach(([k, x]) => { writeHead(out, 0, k); cborValue(out, x); });
  } else if (Array.isArray(v)) {
    writeHead(out, 4, v.length); v.forEach(item => cborValue(out, item));
  } else if (typeof v === 'object') {
    const entries = Object.entries(v).filter(([, val]) => val !== undefined);
    writeHead(out, 5, entries.length);
    for (const [k, val] of entries) {
      const b = Buffer.from(k, 'utf8');
      writeHead(out, 3, b.length); b.forEach(x => out.push(x));
      cborValue(out, val);
    }
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

// ── QDEF Records (SPEC.md §2's "Relationship to QDEF") ──────────────────
function cborFieldMap(fields) {
  const keys = Object.keys(fields).filter(k => fields[k] !== null && fields[k] !== undefined).map(Number).sort((a, b) => a - b);
  const out = [];
  writeHead(out, 5, keys.length);
  for (const k of keys) { writeHead(out, 0, k); cborValue(out, fields[k]); }
  return new Uint8Array(out);
}

function cborRecord(typeId, fields) {
  return cborFieldMap({ 0: typeId, ...fields });
}

const TYPE_SPLIT = 2, TYPE_COMPRESS = 3;
const TYPE_CONTENT_PREVIEW = 48250;
const TYPE_CONTENT_BODY = 56990;
const TYPE_PAPER_PREVIEW = 34456;
const TYPE_PAPER_BODY = 58984;

async function compressWrap(bytes) {
  return cborRecord(TYPE_COMPRESS, { 2: await zlibCompress(bytes) });
}

function splitFragments(bytes, groupId, fragmentCount, withParity) {
  const total = bytes.length;
  const chunkLen = Math.ceil(total / fragmentCount);
  const fragments = [];
  for (let i = 0; i < fragmentCount; i++) {
    const start = Math.min(i * chunkLen, total);
    const end = Math.min(start + chunkLen, total);
    fragments.push(cborRecord(TYPE_SPLIT, {
      2: groupId, 4: i, 6: fragmentCount, 8: bytes.slice(start, end),
      9: total, 11: withParity ? 1 : undefined,
    }));
  }
  if (withParity) {
    const parity = new Uint8Array(chunkLen);
    for (let i = 0; i < fragmentCount; i++) {
      const start = Math.min(i * chunkLen, total);
      const end = Math.min(start + chunkLen, total);
      const slice = bytes.slice(start, end);
      for (let j = 0; j < slice.length; j++) parity[j] ^= slice[j];
    }
    fragments.push(cborRecord(TYPE_SPLIT, { 2: groupId, 4: fragmentCount, 6: fragmentCount, 8: parity, 9: total, 11: 1 }));
  }
  return fragments;
}

function encodeCode(code) {
  return { uri: 'tagdrop:' + base41Encode(code.raw), raw: code.raw, code };
}

function buildContentPreview(f) {
  return cborRecord(TYPE_CONTENT_PREVIEW, {
    1: f.cacheId, 3: f.hint, 5: f.mimeType, 7: f.filename, 9: f.title, 11: f.description,
    13: f.collectionId, 15: f.collectionLabel, 17: f.collectionTag, 19: f.icon,
    21: f.pixelArt, 23: f.lat != null ? cfloat(f.lat) : undefined, 25: f.lng != null ? cfloat(f.lng) : undefined,
    27: f.radiusM != null ? cfloat(f.radiusM) : undefined, 29: f.preferDeclaredLocation, 31: f.locationLabel,
    33: f.keyMaterial, 35: f.retainKey, 37: f.encryption,
    39: f.kdfAlg, 41: f.kdfSalt, 43: f.kdfIters,
    45: f.signatureAlgorithm, 47: f.signerId, 49: f.signerLabel,
    51: f.inReplyTo, 53: f.createdAt, 55: f.sourceUrl,
  });
}

function buildContentBody(f) {
  return cborRecord(TYPE_CONTENT_BODY, { 1: f.content, 3: f.signature, 5: f.signerPubkey });
}

// ── Minimal CBOR decoder (decodes exactly `n` top-level items, returns
// { items, trailing } — mirrors cborDecodeSequencePrefix in
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
    if (info === 27) { return rbs(8); } // 8 raw bytes: float64 (major 7) or uint64 (major 0)
    throw new Error('Unsupported CBOR additional info: ' + info);
  }
  function readVal() {
    const b = rb(), major = b >> 5, a = readArg(b & 0x1F);
    switch (major) {
      case 0: {
        if (a instanceof Uint8Array) {
          let n = 0n;
          for (const byte of a) n = (n << 8n) | BigInt(byte);
          return n <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(n) : n;
        }
        return a;
      }
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
        if ((b & 0x1F) === 27) return new DataView(a.buffer, a.byteOffset, 8).getFloat64(0);
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

// ── CBOR payload-map keys (SPEC.md §3) ──────────────────────────────────
// Content-Preview keys (SPEC §3.1) — used by the QDEF Record path.
const PK = {
  CACHE_ID: 1, HINT: 3, MIME: 5, FILENAME: 7, TITLE: 9, DESCRIPTION: 11,
  COLLECTION_ID: 13, COLLECTION_LABEL: 15, COLLECTION_TAG: 17, ICON: 19,
  LAT: 21, LNG: 23, RADIUS_M: 25, PREFER_DECLARED_LOCATION: 27, LOCATION_LABEL: 29,
  SIGNATURE_ALGORITHM: 31, SIGNER_ID: 33, SIGNER_LABEL: 35,
  IN_REPLY_TO: 51, CREATED_AT: 53, SOURCE_URL: 55,
};
// Content-Body keys (SPEC §3.2)
const BK = { CONTENT: 1, SIGNATURE: 3, SIGNER_PUBKEY: 5 };
// Paper-Preview keys (SPEC §3.3)
const PPK = {
  ROOT_HASH: 1, HINT: 3, SET: 5, SLUG: 7, DOMAIN: 9, STEP: 11,
  COLLECTION_ID: 13, COLLECTION_LABEL: 15, COLLECTION_TAG: 17, ICON: 19,
  LAT: 21, LNG: 23, RADIUS_M: 25, PREFER_DECLARED_LOCATION: 27, LOCATION_LABEL: 29,
  SIGNATURE_ALGORITHM: 31, SIGNER_ID: 33, SIGNER_LABEL: 35,
  IN_REPLY_TO: 37, CREATED_AT: 39, SOURCE_URL: 41,
  TITLE: 43, DESCRIPTION: 45,
  KEY_MATERIAL: 47, RETAIN_KEY: 49,
};
// Paper-Body keys (SPEC §3.4)
const PBK = { FILES: 1, RELATED: 3, SIGNATURE: 5, SIGNER_PUBKEY: 7 };
// Split Wrapper keys
const SK = { GROUP_ID: 2, INDEX: 4, COUNT: 6, DATA: 8, TOTAL: 9, PARITY_FLAG: 11 };
// Compress Wrapper keys
const CK = { PAYLOAD: 2 };

// ── TagDrop Content encode (QDEF Record format, SPEC §3.1-§3.2) ────────────
// Builds Content-Preview + Content-Body Records, optionally Compress-wrapped
// and/or Split-wrapped. Returns {codes, cacheId, preview, bodyPlain}.
async function encodeContentSectors({ hint, filename, mimeType, rawBytes, compress, maxSectorDataBytes = Infinity, parity = false }) {
  const cacheId = await sha256first8(rawBytes);

  const preview = buildContentPreview({
    cacheId,
    hint: hint || undefined,
    filename: filename || undefined,
    mimeType,
  });

  const bodyPlain = buildContentBody({ content: rawBytes });
  const bodyForWire = compress ? await compressWrap(bodyPlain) : bodyPlain;

  const totalBytes = bodyForWire.length;
  if (totalBytes <= maxSectorDataBytes) {
    return {
      codes: [{ raw: concatBytes(preview, bodyForWire), cacheId, totalBytes, index: 0, count: 1, isParity: false }],
      preview, bodyPlain, cacheId,
    };
  }

  const fragmentCount = Math.ceil(totalBytes / maxSectorDataBytes);
  const groupId = await sha256first8(bodyForWire);
  const fragments = splitFragments(bodyForWire, groupId, fragmentCount, parity);
  return {
    codes: fragments.map((frag, i) => ({
      raw: concatBytes(preview, frag), cacheId, totalBytes,
      index: i, count: fragmentCount, isParity: parity && i === fragmentCount,
    })),
    preview, bodyPlain, cacheId,
  };
}

// ── TagDrop Content decode (mirrors tools/reader/index.html) ────────────────
// Decodes a QDEF Record Sequence: parses the first CBOR item (a map = QDEF
// Record), dispatches on typeId to identify Content-Preview, Content-Body,
// Split Wrapper, or Compress Wrapper, then reassembles and verifies.

function parseRecordPrefix(bytes) {
  const { items, trailing } = cborDecodeSequencePrefix(bytes, 1);
  return { record: items[0], trailing };
}

/**
 * Assembles Content from a sequence of QDEF Records. Each code starts with
 * a Content-Preview Record (identical on every code), followed by either a
 * Content-Body Record (single-code) or Split Wrapper Records (multi-code).
 * Handles Compress Wrapper unwrapping. Returns {preview, bodyPlain, cacheId}.
 */
async function assembleContent(codes) {
  // Parse the first code to get the Preview and body/compressed data
  const firstBytes = codes[0].raw;
  const { record: previewRecord, trailing: afterPreview } = parseRecordPrefix(firstBytes);

  // All codes share the same Preview — extract cacheId from it
  const cacheId = previewRecord[PK.CACHE_ID];

  // The remainder after Preview is the body (or Split Wrapper containing the body)
  const { record: secondRecord } = parseRecordPrefix(afterPreview);
  const typeId = secondRecord[0]; // key 0 = QDEF typeId

  let bodyPlain;
  if (typeId === TYPE_CONTENT_BODY) {
    // Single-code: second record is the Content-Body
    bodyPlain = secondRecord;
  } else if (typeId === TYPE_COMPRESS) {
    // Single-code with compression: Compress Wrapper → Content-Body
    const compressed = secondRecord[CK.PAYLOAD];
    const decompressed = await zlibDecompress(compressed);
    const { record: innerRecord } = parseRecordPrefix(decompressed);
    bodyPlain = innerRecord;
  } else if (typeId === TYPE_SPLIT) {
    // Multi-code: reassemble Split Wrapper fragments
    const fragments = [];
    for (const code of codes) {
      // Each code = Preview Record + Split Wrapper Record. Parse the Preview
      // (skip it), then grab the Split Wrapper.
      const { trailing: afterPreview2 } = parseRecordPrefix(code.raw);
      const { record: split } = parseRecordPrefix(afterPreview2);
      fragments.push(split);
    }

    // Sort by index, reassemble
    fragments.sort((a, b) => a[SK.INDEX] - b[SK.INDEX]);
    const totalCount = fragments[0][SK.COUNT];
    const total = fragments[0][SK.TOTAL];
    const groupId = fragments[0][SK.GROUP_ID];

    // Filter out parity fragments (index >= count)
    const dataFragments = fragments.filter(f => f[SK.INDEX] < f[SK.COUNT]);
    const chunkLen = Math.ceil(total / totalCount);
    const reassembled = new Uint8Array(total);
    for (const frag of dataFragments) {
      const start = frag[SK.INDEX] * chunkLen;
      reassembled.set(frag[SK.DATA], start);
    }

    // Verify integrity via groupId
    const actualHash = await sha256first8(reassembled);
    if (toHex(actualHash) !== toHex(groupId)) {
      throw new Error('Split Wrapper groupId mismatch');
    }

    // Check if reassembled data is a Compress Wrapper or a Content-Body
    const { record: innerRecord } = parseRecordPrefix(reassembled);
    const innerTypeId = innerRecord[0];
    if (innerTypeId === TYPE_COMPRESS) {
      const compressed = innerRecord[CK.PAYLOAD];
      const decompressed = await zlibDecompress(compressed);
      const { record: bodyRecord } = parseRecordPrefix(decompressed);
      bodyPlain = bodyRecord;
    } else if (innerTypeId === TYPE_CONTENT_BODY) {
      bodyPlain = innerRecord;
    } else {
      throw new Error('Unexpected inner record typeId: ' + innerTypeId);
    }
  } else {
    throw new Error('Unexpected second record typeId: ' + typeId);
  }

  return { preview: previewRecord, bodyPlain, cacheId };
}

// ── QR rendering + zxing-wasm decoding ──────────────────────────────────────
async function renderTextQr(uri, width) {
  return QRCode.toBuffer(uri, { errorCorrectionLevel: 'L', margin: 2, width, type: 'png' });
}

async function renderByteQr(bytes, width) {
  return QRCode.toBuffer([{ data: Buffer.from(bytes), mode: 'byte' }],
    { errorCorrectionLevel: 'L', margin: 2, width, type: 'png' });
}

async function scanQr(pngBuf) {
  const results = await readBarcodes(pngBuf, { formats: ['QRCode'], tryHarder: true });
  return results[0] || null;
}

// ── Test cases ───────────────────────────────────────────────────────────
let pass = 0, fail = 0;

function ok(label) { console.log(`  ${label} … ok`); pass++; }
function bad(label, msg) { console.log(`  ${label} … FAIL  ${msg}`); fail++; }

async function testSingleCode(label, { hint, filename, mimeType, rawBytes, compress }, width) {
  let result;
  try {
    result = await encodeContentSectors({ hint, filename, mimeType, rawBytes, compress, maxSectorDataBytes: Infinity });
  } catch (e) {
    bad(label, `encode threw: ${e.message}`); return;
  }
  if (result.codes.length !== 1) { bad(label, `expected a single code, got ${result.codes.length}`); return; }
  const encoded = encodeCode(result.codes[0]);

  let pngBuf;
  try {
    pngBuf = await renderTextQr(encoded.uri, width);
  } catch (e) {
    bad(label, `URI too long for any QR version (${encoded.uri.length} chars): ${e.message}`); return;
  }

  const scanResult = await scanQr(pngBuf);
  if (!scanResult) { bad(label, `rendered at ${width}px but zxing-wasm could not decode it`); return; }

  const decodedUri = Buffer.from(scanResult.bytes).toString('utf8');
  if (decodedUri !== encoded.uri) { bad(label, 'decoded URI text does not match encoded URI'); return; }

  // Decode: strip tagdrop:, Base41-decode, parse QDEF Records
  let decoded;
  try {
    const rawBytes2 = base41Decode(decodedUri.slice('tagdrop:'.length));
    decoded = await assembleContent([{ raw: rawBytes2 }]);
  } catch (e) {
    bad(label, `re-decoding failed: ${e.message}`); return;
  }

  if (toHex(decoded.cacheId) !== toHex(result.cacheId)) return bad(label, 'cacheId mismatch');
  if (decoded.bodyPlain[BK.CONTENT] === undefined) return bad(label, 'body has no content field');

  // The content inside Content-Body is always raw (uncompressed) — DEFLATE
  // compression is applied at the Compress Wrapper level, wrapping the entire
  // Content-Body Record, not the content bytes inside it. The decoder already
  // unwraps the Compress Wrapper, so decoded.bodyPlain[BK.CONTENT] is the
  // original rawBytes.
  const contentBytes = decoded.bodyPlain[BK.CONTENT];
  if (!bytesEqual(contentBytes, rawBytes)) return bad(label, 'decoded content bytes do not match original');

  // Verify Preview fields
  if (decoded.preview[PK.MIME] !== mimeType) return bad(label, 'mimeType mismatch in Preview');
  if ((decoded.preview[PK.HINT] || null) !== (hint || null)) return bad(label, 'hint mismatch in Preview');
  if ((decoded.preview[PK.FILENAME] || null) !== (filename || null)) return bad(label, 'filename mismatch in Preview');

  ok(`${label} (URI ${encoded.uri.length} chars, ${width}px, text-mode QR)`);
}

async function testMultiCode(label, { hint, filename, mimeType, rawBytes, compress, maxSectorDataBytes }, width) {
  let result;
  try {
    result = await encodeContentSectors({ hint, filename, mimeType, rawBytes, compress, maxSectorDataBytes });
  } catch (e) {
    bad(label, `encode threw: ${e.message}`); return;
  }
  if (result.codes.length <= 1) { bad(label, `expected multiple codes, got ${result.codes.length}`); return; }
  const cacheIdHex = toHex(result.cacheId);

  // Every code is rendered in binary/byte-mode QR
  const decodedCodes = [];
  for (const code of result.codes) {
    let png;
    try {
      png = await renderByteQr(code.raw, width);
    } catch (e) {
      bad(`${label} code ${code.index}`, `byte-mode QR render failed: ${e.message}`); return;
    }
    const scanResult = await scanQr(png);
    if (!scanResult) { bad(`${label} code ${code.index}`, `zxing-wasm could not decode binary-mode QR at ${width}px`); return; }
    if (!bytesEqual(scanResult.bytes, code.raw)) { bad(`${label} code ${code.index}`, 'decoded raw bytes do not match encoded code bytes'); return; }
    decodedCodes.push({ raw: scanResult.bytes });
  }
  ok(`${label} ${result.codes.length} code(s) (${result.codes[0].totalBytes} assembled bytes, ~${Math.ceil(result.codes[0].totalBytes / result.codes.length)} bytes/code, ${width}px, binary-mode QR)`);

  // Reassemble and verify
  let decoded;
  try {
    decoded = await assembleContent(decodedCodes);
  } catch (e) {
    bad(`${label} assembly`, `reassembly failed: ${e.message}`); return;
  }

  if (toHex(decoded.cacheId) !== cacheIdHex) return bad(`${label} assembly`, 'cacheId mismatch');

  // Same as single-code: content is raw inside Content-Body; DEFLATE wraps
  // the whole Body Record, not the content bytes within it.
  const contentBytes = decoded.bodyPlain[BK.CONTENT];
  if (!bytesEqual(contentBytes, rawBytes)) return bad(`${label} assembly`, 'assembled content does not match original rawBytes');
  ok(`${label} assembly + integrity check`);
}

// ── Paper QDEF Record encode (SPEC §3.3-§3.4) ────────────────────────────
const PAPER_PREVIEW_SIGNATURE_KEYS = new Set([1, 31, 33, 35]);
const PAPER_BODY_SIGNATURE_KEYS = new Set([5, 7]);
// Paper files[]/related[] entry keys (SPEC §3.4 — int-keyed sub-maps)
const KF = { SLUG: 1, MIME: 2, FILE_ID: 3, DESCRIPTION: 4, PIXEL_ART: 5 };

// The raw major-4 CBOR array encoding of `items` (SPEC §3.4 field-value-shape
// rule: files/related travel as a byte string wrapping this array) — returned
// UNWRAPPED, since this becomes a map field's *value* via cborFieldMap's own
// cborValue(out, v), whose Uint8Array branch already wraps any Uint8Array as
// a byte string; wrapping here too would double that header.
function cborArrayBytes(items) {
  const inner = [];
  writeHead(inner, 4, items.length);
  items.forEach(item => cborValue(inner, item));
  return new Uint8Array(inner);
}

// Nested map (inside an array value)
function subMap(pairs) { return { __map: true, pairs }; }

function buildPaperPreview(f) {
  return cborRecord(TYPE_PAPER_PREVIEW, {
    1: f.rootHash, 3: f.hint, 5: f.set, 7: f.slug, 9: f.domain,
    11: f.step, 13: f.collectionId, 15: f.collectionLabel, 17: f.collectionTag,
    19: f.icon,
    21: f.lat != null ? cfloat(f.lat) : undefined, 23: f.lng != null ? cfloat(f.lng) : undefined,
    25: f.radiusM != null ? cfloat(f.radiusM) : undefined,
    27: f.preferDeclaredLocation, 29: f.locationLabel,
    31: f.signatureAlgorithm, 33: f.signerId, 35: f.signerLabel,
    37: f.inReplyTo, 39: f.createdAt, 41: f.sourceUrl,
    43: f.title, 45: f.description,
    47: f.keyMaterial, 49: f.retainKey,
  });
}
function buildPaperBody(f) {
  return cborRecord(TYPE_PAPER_BODY, {
    1: f.files, 3: f.related, 5: f.signature, 7: f.signerPubkey,
  });
}
function stripKeys(mapBytes, keysToStrip) {
  let pos = 0;
  function rb() { return mapBytes[pos++]; }
  function readArg(info) {
    if (info <= 23) return info;
    if (info === 24) return rb();
    if (info === 25) return rb() * 256 + rb();
    if (info === 26) { let n = 0; for (let i = 0; i < 4; i++) n = n * 256 + rb(); return n; }
    if (info === 27) { let n = 0; for (let i = 0; i < 8; i++) n = n * 256 + rb(); return n; }
    throw new Error('Unsupported CBOR additional info: ' + info);
  }
  function skipValue() {
    const b = rb(), major = b >> 5, a = readArg(b & 0x1F);
    if (major === 2 || major === 3) { pos += a; }
    else if (major === 4) { for (let i = 0; i < a; i++) skipValue(); }
    else if (major === 5) { for (let i = 0; i < a; i++) { skipValue(); skipValue(); } }
    else if (major === 7 && (b & 0x1F) === 27) { pos += 8; }
  }
  const head = rb();
  if (head >> 5 !== 5) throw new Error('Expected CBOR map (major 5), got major ' + (head >> 5));
  const count = readArg(head & 0x1F);
  const survivors = [];
  for (let i = 0; i < count; i++) {
    const pairStart = pos;
    const keyByte = rb(), keyMajor = keyByte >> 5, key = readArg(keyByte & 0x1F);
    if (keyMajor !== 0) throw new Error('Expected uint map key, got major ' + keyMajor);
    skipValue();
    if (!keysToStrip.has(key)) survivors.push(mapBytes.slice(pairStart, pos));
  }
  const newHeader = [];
  writeHead(newHeader, 5, survivors.length);
  return concatBytes(new Uint8Array(newHeader), ...survivors);
}
function paperSignedMessageHash(preview, bodyPlain) {
  const unsignedPreview = stripKeys(preview, PAPER_PREVIEW_SIGNATURE_KEYS);
  const unsignedBody = stripKeys(bodyPlain, PAPER_BODY_SIGNATURE_KEYS);
  return sha256(concatBytes(unsignedPreview, unsignedBody));
}

async function createPaper({ hint, files, related, collectionId, maxCodeDataBytes = Infinity }) {
  const rootHashPlaceholder = new Uint8Array(8);
  let preview = buildPaperPreview({
    rootHash: rootHashPlaceholder, hint, collectionId, files: files.length,
  });
  // Encode files[] as byte string of int-keyed sub-maps (SPEC §3.4)
  const filesCbor = cborArrayBytes(files.map(f => subMap([
    [KF.SLUG, f.slug], [KF.MIME, f.mimeType], [KF.FILE_ID, f.sha256],
  ])));
  const bodyPlainBytes = buildPaperBody({ files: filesCbor, related: undefined });
  const bodyPlain = cborDecodeSequencePrefix(bodyPlainBytes, 1).items[0];
  const bodyForWire = await compressWrap(bodyPlainBytes);

  const totalBytes = bodyForWire.length;
  if (totalBytes <= maxCodeDataBytes) {
    const rootHash = await paperSignedMessageHash(preview, bodyPlainBytes);
    preview = buildPaperPreview({
      rootHash, hint, collectionId, files: files.length,
    });
    const codes = [{ raw: concatBytes(preview, bodyForWire), totalBytes, index: 0, count: 1, isParity: false }];
    return { rootHash, codes, preview, bodyPlain };
  }

  const fragmentCount = Math.ceil(totalBytes / maxCodeDataBytes);
  const groupId = await sha256first8(bodyForWire);
  const fragments = splitFragments(bodyForWire, groupId, fragmentCount, false);
  const codes = fragments.map((frag, i) => ({
    raw: concatBytes(preview, frag), totalBytes, index: i, count: fragmentCount, isParity: false,
  }));

  const rootHash = await paperSignedMessageHash(preview, bodyPlainBytes);
  const finalPreview = buildPaperPreview({
    rootHash, hint, collectionId, files: files.length,
  });
  for (let i = 0; i < codes.length; i++) {
    codes[i].raw = concatBytes(finalPreview, fragments[i]);
  }
  return { rootHash, codes, preview: finalPreview, bodyPlain };
}

// ── Paper QDEF Record decode ──────────────────────────────────────────────
async function assemblePaper(codes) {
  const firstBytes = codes[0].raw;
  const { record: previewRecord, trailing: afterPreview } = parseRecordPrefix(firstBytes);
  const rootHash = previewRecord[PPK.ROOT_HASH];

  const { record: secondRecord } = parseRecordPrefix(afterPreview);
  const typeId = secondRecord[0];

  let bodyPlain;
  if (typeId === TYPE_PAPER_BODY) {
    bodyPlain = secondRecord;
  } else if (typeId === TYPE_COMPRESS) {
    const compressed = secondRecord[CK.PAYLOAD];
    const decompressed = await zlibDecompress(compressed);
    const { record: innerRecord } = parseRecordPrefix(decompressed);
    bodyPlain = innerRecord;
  } else if (typeId === TYPE_SPLIT) {
    const fragments = [];
    for (const code of codes) {
      const { trailing: afterPreview2 } = parseRecordPrefix(code.raw);
      const { record: split } = parseRecordPrefix(afterPreview2);
      fragments.push(split);
    }
    fragments.sort((a, b) => a[SK.INDEX] - b[SK.INDEX]);
    const totalCount = fragments[0][SK.COUNT];
    if (fragments.length !== totalCount) throw new Error(`Fragment count mismatch: expected ${totalCount}, got ${fragments.length}`);
    const assembled = concatBytes(...fragments.map(f => f[SK.DATA]));
    const { record: innerRecord } = parseRecordPrefix(assembled);
    if (innerRecord[0] === TYPE_COMPRESS) {
      const decompressed = await zlibDecompress(innerRecord[CK.PAYLOAD]);
      const { record: innerInner } = parseRecordPrefix(decompressed);
      bodyPlain = innerInner;
    } else {
      bodyPlain = innerRecord;
    }
  } else {
    throw new Error(`Unexpected Paper second record typeId: ${typeId}`);
  }

  return { preview: previewRecord, bodyPlain, rootHash };
}

async function testPaperSingleCode(label, opts, width) {
  const { hint, files, related, collectionId } = opts;
  const result = await createPaper({ hint, files, related, collectionId, maxCodeDataBytes: Infinity });
  const code = result.codes[0];
  const uri = 'tagdrop:' + base41Encode(code.raw);
  const pngBuf = await renderTextQr(uri, width);
  const decoded = await scanQr(pngBuf);
  if (!decoded) return bad(label, 'no barcode decoded');
  const decodedUri = Buffer.from(decoded.bytes).toString('utf8');
  if (decodedUri !== uri) return bad(label, 'decoded URI text does not match');
  try {
    const rawBytes = base41Decode(decodedUri.slice('tagdrop:'.length));
    const decodedCodes = [{ raw: rawBytes }];
    const assembled = await assemblePaper(decodedCodes);
    if (toHex(assembled.rootHash) !== toHex(result.rootHash)) return bad(label, 'rootHash mismatch');
    if (!bytesEqual(assembled.bodyPlain[PBK.FILES], result.bodyPlain[PBK.FILES])) return bad(label, 'files mismatch');
    ok(`${label} (URI ${uri.length} chars, ${width}px, text-mode QR)`);
  } catch (e) { bad(label, e.message); }
}

async function testPaperMultiCode(label, opts, width) {
  const { hint, files, related, collectionId, maxCodeDataBytes } = opts;
  const result = await createPaper({ hint, files, related, collectionId, maxCodeDataBytes });
  const decodedCodes = [];
  for (const code of result.codes) {
    const pngBuf = await renderByteQr(code.raw, width);
    const decoded = await scanQr(pngBuf);
    if (!decoded) return bad(label, `QR decode failed for code ${code.index}`);
    decodedCodes.push({ raw: decoded.bytes });
  }
  decodedCodes.sort((a, b) => {
    const { trailing: ta } = parseRecordPrefix(a.raw);
    const { record: sa } = parseRecordPrefix(ta);
    const { trailing: tb } = parseRecordPrefix(b.raw);
    const { record: sb } = parseRecordPrefix(tb);
    return sa[SK.INDEX] - sb[SK.INDEX];
  });
  ok(`${label} ${result.codes.length} code(s) (${result.codes[0].totalBytes} assembled bytes, ${width}px, binary-mode QR)`);
  try {
    const assembled = await assemblePaper(decodedCodes);
    if (toHex(assembled.rootHash) !== toHex(result.rootHash)) return bad(label, 'rootHash mismatch');
    if (!bytesEqual(assembled.bodyPlain[PBK.FILES], result.bodyPlain[PBK.FILES])) return bad(label, 'files mismatch');
    ok(`${label} assembly + rootHash check`);
  } catch (e) { bad(label, e.message); }
}

function bytesEqual(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
function cborEqual(a, b) {
  const ea = [], eb = [];
  cborValue(ea, a); cborValue(eb, b);
  return bytesEqual(new Uint8Array(ea), new Uint8Array(eb));
}

// ── Run ──────────────────────────────────────────────────────────────────
const WIDTHS = [400, 1024]; // 400px on-screen preview, 1024px Download PNG.

console.log('QR round-trip test (real TagDrop QDEF Record wire format)\n');

// Small single-code Content: short plain-text content, no compression — fits
// comfortably in one QR as an alphanumeric tagdrop: URI.
const smallText = 'Under the bridge, leave no trace. See you on the trail!';
for (const w of WIDTHS) {
  console.log(`Small Content (~${smallText.length}-byte text/plain, uncompressed):`);
  await testSingleCode('preview/download', {
    hint: 'under the bridge', filename: null, mimeType: 'text/plain',
    rawBytes: new TextEncoder().encode(smallText), compress: false,
  }, w);
}

// Medium single-code Content: a repetitive HTML page large enough that DEFLATE
// meaningfully shrinks it back under a comfortable single-QR size.
const mediumHtml = '<!doctype html><html><body>' +
  '<p>Trail stop 3 — Oak Tree. '.repeat(40) +
  '</body></html>';
for (const w of WIDTHS) {
  console.log(`Medium Content (~${mediumHtml.length}-byte text/html, DEFLATE-compressed):`);
  await testSingleCode('preview/download', {
    hint: 'oak tree', filename: 'story.html', mimeType: 'text/html',
    rawBytes: new TextEncoder().encode(mediumHtml), compress: true,
  }, w);
}

// Large content: too big for one QR — split into multiple equal-status codes
// (SPEC §4.1/§5), every code rendered as binary/byte-mode QR. Built from
// varied (non-repeating) pseudo-random words rather than one repeated phrase,
// so DEFLATE only achieves a realistic ~50-70% reduction instead of collapsing
// to a few dozen bytes — each code ends up a realistic size.
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
  console.log(`Large content (~${largeHtml.length}-byte text/html, multi-code, DEFLATE-compressed):`);
  await testMultiCode('Multi-code', {
    hint: 'spring trail story', filename: 'trail-story.html', mimeType: 'text/html',
    rawBytes: new TextEncoder().encode(largeHtml), compress: true, maxSectorDataBytes: 600,
  }, w);
}

// ── Paper QDEF round-trip tests ──────────────────────────────────────────
// Small Paper: a 2-file collection with short text files — fits in one QR.
const smallFiles = [
  { slug: 'hello.txt', sha256: await sha256first8(new TextEncoder().encode('hello world')), size: 11, mimeType: 'text/plain' },
  { slug: 'note.txt',  sha256: await sha256first8(new TextEncoder().encode('a short note')),  size: 12, mimeType: 'text/plain' },
];
for (const w of WIDTHS) {
  console.log(`Small Paper (${smallFiles.length} files, single-code, DEFLATE-compressed):`);
  await testPaperSingleCode('Paper single-code', {
    hint: 'small collection', files: smallFiles, collectionId: 'example.org/test',
  }, w);
}

// Large Paper: many files with long filenames — too big for one QR, requires splitting.
const largeFiles = [];
for (let i = 0; i < 40; i++) {
  const slug = `trail-stop-${String(i).padStart(2, '0')}-detailed-description-with-long-name.txt`;
  const body = `Content for trail stop ${i}. `.repeat(10);
  largeFiles.push({
    slug,
    sha256: await sha256first8(new TextEncoder().encode(body)),
    size: body.length,
    mimeType: 'text/plain',
  });
}
for (const w of WIDTHS) {
  console.log(`Large Paper (${largeFiles.length} files, multi-code, DEFLATE-compressed):`);
  await testPaperMultiCode('Paper multi-code', {
    hint: 'large trail collection', files: largeFiles, collectionId: 'example.org/trail',
    maxCodeDataBytes: 600,
  }, w);
}

console.log(`\n${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
