/**
 * QDEF wire-shape validation suite: builds TagDrop's version-2 wire shape
 * (SPEC.md §2-§5) — Preview/Body Records, QDEF Split/Compress Wrapper
 * Records, the root_hash/signed-message placeholder-then-strip discipline —
 * and round-trips it through encode → decode → reassemble → verify, entirely
 * in-memory (no QR rendering, no Base41, no real ML-DSA-44 — signatures here
 * are fixed-length mock bytes, standing in only to exercise the *byte
 * layout* discipline SPEC.md §10 requires, not the cryptography itself,
 * which is proven separately by the real sign/verify implementations in
 * tools/generator+reader/index.html and the Kotlin app).
 *
 * Originally written as a throwaway shape-prototyping tool (find bugs in the
 * new shape *before* porting it into the real codecs — the same discipline
 * mofosyne/qdef's own prototype+FINDINGS.md process used) — but its
 * adversarial/validation coverage (tamper detection via group_id/root_hash
 * recomputation, SPEC §2.2 even/odd key criticality enforcement, key-only
 * codes) isn't duplicated anywhere else, including tools/test-qr-
 * roundtrip.mjs (which focuses on real QR rendering/decoding round-trips,
 * not malformed/adversarial input), so it's kept as a permanent part of the
 * test suite and gated in CI (.github/workflows/ci.yml) rather than deleted
 * now that the real JS ports (Content and Paper, both generator and reader)
 * have landed. The Kotlin port, when it happens, should get this same
 * adversarial coverage in its own JUnit suite rather than relying on this
 * file, which only exercises the JS-side byte layout.
 *
 * Run with:
 *   cd tools
 *   node test-qdef-roundtrip.mjs
 */
import { deflateRawSync, inflateRawSync } from 'node:zlib';
import { createHash, randomBytes, createCipheriv, createDecipheriv } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

// ── Record Type IDs (SPEC.md §2.1, §4.1's Split/Compress from QDEF-SPEC.md §4.1) ──
// SPLIT/COMPRESS stay plain Numbers (small values, decoder returns Number);
// the four TagDrop Type IDs stay BigInt (exceed Number.MAX_SAFE_INTEGER,
// decoder returns BigInt) - see decodeItem's majorType-0 branch. Mixing
// these up is exactly the Number/BigInt equality trap that fix guards
// against elsewhere; the constants themselves have to match it too.
const TYPE = {
  SPLIT: 2,
  COMPRESS: 3,
  CONTENT_PREVIEW: 11040522420225562824n,
  CONTENT_BODY: 16141970035994251452n,
  PAPER_PREVIEW: 5378751847309657042n,
  PAPER_BODY: 3791774695141159602n,
};

// ── Minimal CBOR (RFC 8949) — maps, uints, byte/text strings, arrays, float64 ──
// Map keys are always encoded in ascending numeric order (SPEC.md's fixed,
// specified key-encoding order — not literally "canonical CBOR," just a
// convention both real codecs must independently reproduce byte-for-byte,
// same requirement the existing MiniCbor.kt/inline-JS codecs already have).

function encodeUInt(n, majorType = 0) {
  n = BigInt(n);
  const mt = majorType << 5;
  if (n < 24n) return Buffer.from([mt | Number(n)]);
  if (n < 256n) return Buffer.from([mt | 24, Number(n)]);
  if (n < 65536n) return Buffer.from([mt | 25, Number(n >> 8n), Number(n & 0xffn)]);
  if (n < 4294967296n) {
    const b = Buffer.alloc(5);
    b[0] = mt | 26;
    b.writeUInt32BE(Number(n), 1);
    return b;
  }
  const b = Buffer.alloc(9);
  b[0] = mt | 27;
  b.writeBigUInt64BE(n, 1);
  return b;
}

function encodeBytes(buf) {
  return Buffer.concat([encodeUInt(buf.length, 2), buf]);
}

function encodeText(str) {
  const buf = Buffer.from(str, 'utf8');
  return Buffer.concat([encodeUInt(buf.length, 3), buf]);
}

function encodeFloat64(n) {
  const b = Buffer.alloc(9);
  b[0] = (7 << 5) | 27;
  b.writeDoubleBE(n, 1);
  return b;
}

function encodeBool(b) {
  return Buffer.from([(7 << 5) | (b ? 21 : 20)]);
}

function encodeArray(items) {
  return Buffer.concat([encodeUInt(items.length, 4), ...items]);
}

// value: number(int) | {f64: number} | boolean | string | Buffer | Array<encoded-item-buffers>
function encodeValue(v) {
  if (typeof v === 'bigint') return encodeUInt(v, 0);
  if (typeof v === 'number') return Number.isInteger(v) ? encodeUInt(v, 0) : encodeFloat64(v);
  if (typeof v === 'boolean') return encodeBool(v);
  if (typeof v === 'string') return encodeText(v);
  if (Buffer.isBuffer(v)) return encodeBytes(v);
  if (v && v.f64 !== undefined) return encodeFloat64(v.f64);
  throw new Error(`encodeValue: unsupported type ${typeof v}`);
}

// fields: plain object, integer-string keys, undefined values are omitted
function encodeRecord(fields) {
  const keys = Object.keys(fields)
    .filter((k) => fields[k] !== undefined)
    .map(Number)
    .sort((a, b) => a - b);
  const parts = [encodeUInt(keys.length, 5)];
  for (const k of keys) {
    parts.push(encodeUInt(k, 0));
    parts.push(encodeValue(fields[String(k)]));
  }
  return Buffer.concat(parts);
}

function encodeRecordWithTypeId(typeId, fields) {
  return encodeRecord({ 0: typeId, ...fields });
}

// Decoder: returns { value, rest } pairs, walking one CBOR item at a time.
function decodeItem(buf, offset) {
  const head = buf[offset];
  const majorType = head >> 5;
  const info = head & 0x1f;
  let argOffset = offset + 1;
  let arg;
  if (info < 24) {
    arg = BigInt(info);
  } else if (info === 24) {
    arg = BigInt(buf[argOffset]);
    argOffset += 1;
  } else if (info === 25) {
    arg = BigInt(buf.readUInt16BE(argOffset));
    argOffset += 2;
  } else if (info === 26) {
    arg = BigInt(buf.readUInt32BE(argOffset));
    argOffset += 4;
  } else if (info === 27) {
    arg = buf.readBigUInt64BE(argOffset);
    argOffset += 8;
  } else {
    throw new Error(`decodeItem: unsupported additional info ${info}`);
  }

  if (majorType === 0) {
    // Downgrade to a plain Number whenever it's safe (matches the encoder,
    // which accepts either) - keeps arithmetic/equality ergonomic for the
    // overwhelming majority of small fields, while still round-tripping the
    // rare genuinely-large value (e.g. a 64-bit Record Type ID) as BigInt
    // rather than silently losing precision.
    const value = arg <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(arg) : arg;
    return { value, next: argOffset };
  }
  if (majorType === 2) {
    const len = Number(arg);
    return { value: buf.subarray(argOffset, argOffset + len), next: argOffset + len };
  }
  if (majorType === 3) {
    const len = Number(arg);
    return { value: buf.toString('utf8', argOffset, argOffset + len), next: argOffset + len };
  }
  if (majorType === 4) {
    const count = Number(arg);
    const items = [];
    let cur = argOffset;
    for (let i = 0; i < count; i++) {
      const r = decodeItem(buf, cur);
      items.push(r.value);
      cur = r.next;
    }
    return { value: items, next: cur };
  }
  if (majorType === 5) {
    const count = Number(arg);
    const map = {};
    let cur = argOffset;
    for (let i = 0; i < count; i++) {
      const k = decodeItem(buf, cur);
      const v = decodeItem(buf, k.next);
      map[Number(k.value)] = v.value;
      cur = v.next;
    }
    return { value: map, next: cur };
  }
  if (majorType === 7 && info === 27) {
    return { value: buf.readDoubleBE(argOffset), next: argOffset + 8 };
  }
  if (majorType === 7 && (info === 20 || info === 21)) {
    return { value: info === 21, next: argOffset };
  }
  throw new Error(`decodeItem: unsupported major type ${majorType}`);
}

function decodeRecord(buf, offset = 0) {
  const r = decodeItem(buf, offset);
  if (typeof r.value !== 'object' || Array.isArray(r.value)) {
    throw new Error('decodeRecord: expected a map');
  }
  return r;
}

// Decode a CBOR Sequence (RFC 8742) of records from the front of buf; returns
// as many top-level items as fit, each consumed in order.
function decodeSequence(buf) {
  const items = [];
  let offset = 0;
  while (offset < buf.length) {
    const r = decodeItem(buf, offset);
    items.push(r.value);
    offset = r.next;
  }
  return items;
}

// ── Compress Wrapper (QDEF-SPEC.md §4.1 Type 3) ──
function compressWrap(bytes) {
  return encodeRecordWithTypeId(TYPE.COMPRESS, { 2: deflateRawSync(bytes) });
}

function isWrapperRecord(record, typeId) {
  return typeof record === 'object' && !Array.isArray(record) && record[0] === typeId;
}

// ── Split Wrapper (QDEF-SPEC.md §4.1 Type 2) — fragment / reassemble / XOR parity ──
function splitFragments(bytes, groupId, fragmentCount, withParity) {
  const totalBytes = bytes.length;
  const chunkLen = Math.ceil(totalBytes / fragmentCount);
  const fragments = [];
  for (let i = 0; i < fragmentCount; i++) {
    const slice = bytes.subarray(i * chunkLen, Math.min((i + 1) * chunkLen, totalBytes));
    fragments.push(
      encodeRecordWithTypeId(TYPE.SPLIT, {
        2: groupId,
        4: i,
        6: fragmentCount,
        8: slice,
        9: totalBytes,
        11: withParity ? 1 : undefined,
      })
    );
  }
  if (withParity) {
    const parity = Buffer.alloc(chunkLen);
    for (let i = 0; i < fragmentCount; i++) {
      const slice = bytes.subarray(i * chunkLen, Math.min((i + 1) * chunkLen, totalBytes));
      for (let j = 0; j < slice.length; j++) parity[j] ^= slice[j];
      // shorter final fragment: remaining parity bytes already XOR with an
      // implicit zero pad, matching QDEF-SPEC.md's zero-padding rule
    }
    fragments.push(
      encodeRecordWithTypeId(TYPE.SPLIT, {
        2: groupId,
        4: fragmentCount,
        6: fragmentCount,
        8: parity,
        9: totalBytes,
        11: 1,
      })
    );
  }
  return fragments;
}

function reassembleSplit(fragmentRecords, expectedGroupId) {
  const byIndex = new Map();
  let count, totalBytes;
  for (const rec of fragmentRecords) {
    assert.ok(rec[2].equals(expectedGroupId), 'group_id mismatch across fragments');
    count = rec[6];
    totalBytes = rec[9];
    byIndex.set(rec[4], rec[8]);
  }
  const chunkLen = Math.ceil(totalBytes / count);
  const missing = [];
  for (let i = 0; i < count; i++) if (!byIndex.has(i)) missing.push(i);
  if (missing.length === 1 && byIndex.has(count)) {
    // recover the single missing data fragment via XOR parity
    const parity = Buffer.from(byIndex.get(count));
    for (let i = 0; i < count; i++) {
      if (i === missing[0]) continue;
      const frag = byIndex.get(i);
      for (let j = 0; j < frag.length; j++) parity[j] ^= frag[j];
    }
    const isLast = missing[0] === count - 1;
    const recoveredLen = isLast ? totalBytes - missing[0] * chunkLen : chunkLen;
    byIndex.set(missing[0], parity.subarray(0, recoveredLen));
  } else if (missing.length > 0) {
    throw new Error(`reassembleSplit: missing fragments ${missing.join(',')}, no recovery possible`);
  }
  const parts = [];
  for (let i = 0; i < count; i++) parts.push(byIndex.get(i));
  const reassembled = Buffer.concat(parts);
  const actualGroupId = sha256(reassembled).subarray(0, 8);
  assert.ok(actualGroupId.equals(expectedGroupId), 'group_id verification failed after reassembly');
  return reassembled;
}

// Recursively unwrap Compress/Split Wrapper Records until a plain Record remains.
// (Split reassembly needs all fragments up front, so this handles the
// single-code "already a plain/Compress-wrapped Record" case; multi-code
// reassembly is driven explicitly in the payload-level decode functions
// below, mirroring how a real decoder accumulates fragments across scans.)
function resolveNonSplitWrapperStack(bytes) {
  let cur = bytes;
  for (;;) {
    const { value: record } = decodeRecord(cur);
    if (isWrapperRecord(record, TYPE.COMPRESS)) {
      cur = inflateRawSync(record[2]);
      continue;
    }
    return record;
  }
}

function sha256(buf) {
  return createHash('sha256').update(buf).digest();
}

// ── cache_id / root_hash / signed-message (SPEC.md §4.4, §10) ──
// Mock signing: fixed-length random bytes standing in for real ML-DSA-44,
// per this file's header comment — exercises the placeholder-then-strip
// byte-layout discipline, not the cryptography.
const MOCK_SIGNATURE_LEN = 2420;
const MOCK_PUBKEY_LEN = 1312;

function mockSign(_message, signerId) {
  return { signature: randomBytes(MOCK_SIGNATURE_LEN), signerPubkey: randomBytes(MOCK_PUBKEY_LEN), signerId };
}

// ── Content-Preview / Content-Body (SPEC.md §3.1-§3.2) ──
function buildContentPreview(f) {
  return encodeRecordWithTypeId(TYPE.CONTENT_PREVIEW, {
    1: f.cacheId,
    3: f.hint,
    5: f.mimeType,
    33: f.keyMaterial,
    35: f.retainKey,
    37: f.encryption,
    45: f.signatureAlgorithm,
    47: f.signerId,
    49: f.signerLabel,
  });
}

// SPEC.md §2.2 / QDEF-SPEC.md §3.2: even/odd key criticality. TagDrop's own
// fields are all odd today (§2.2) — nothing here is exercised by ordinary
// encode/decode of a current-version payload — so this needs its own
// explicit test rather than falling out of the existing round-trips.
const KNOWN_KEYS = {
  CONTENT_PREVIEW: new Set([1, 3, 5, 33, 35, 37, 45, 47, 49]),
  CONTENT_BODY: new Set([1, 3, 5]),
  PAPER_PREVIEW: new Set([1, 3, 5, 7, 31, 33, 35]),
  PAPER_BODY: new Set([1, 3, 5, 7]),
};

function assertKnownKeys(record, knownKeys, label) {
  for (const keyStr of Object.keys(record)) {
    const key = Number(keyStr);
    if (key === 0 || knownKeys.has(key)) continue;
    if (key % 2 === 0) {
      throw new Error(`${label}: unrecognized CRITICAL (even) key ${key} - must abort this Record (QDEF-SPEC.md S3.2)`);
    }
    // unrecognized odd key: silently ignored, no action
  }
}

function buildContentBody(f) {
  return encodeRecordWithTypeId(TYPE.CONTENT_BODY, {
    1: f.content,
    3: f.signature,
    5: f.signerPubkey,
  });
}

// ── Paper-Preview / Paper-Body (SPEC.md §3.3-§3.4) ──
function buildPaperPreview(f) {
  return encodeRecordWithTypeId(TYPE.PAPER_PREVIEW, {
    1: f.rootHash,
    3: f.hint,
    5: f.set,
    7: f.slug,
    31: f.signatureAlgorithm,
    33: f.signerId,
    35: f.signerLabel,
  });
}

function buildPaperBody(f) {
  return encodeRecordWithTypeId(TYPE.PAPER_BODY, {
    1: f.files,
    3: f.related,
    5: f.signature,
    7: f.signerPubkey,
  });
}

// ── High-level payload builders: return an array of "codes," each code a ──
// ── Buffer (the Record Sequence bytes for that one physical code, §2)    ──

function buildContentPayload({ hint, mimeType, content, maxBodyBytes = 900, split = false, compress = false, sign = false }) {
  const cacheId = sha256(content).subarray(0, 8);
  let bodyPlain = buildContentBody({ content });
  let bodyForWire = compress ? compressWrap(bodyPlain) : bodyPlain;

  const needsSplit = split || bodyForWire.length > maxBodyBytes;

  if (!needsSplit) {
    let preview = buildContentPreview({ cacheId, hint, mimeType });
    if (sign) {
      // SPEC.md S10/S4.4: sign the LOGICAL (uncompressed) bytes, not the
      // wire-transmitted (possibly Compress-wrapped) ones — bodyPlain here,
      // never bodyForWire, so the signature is independent of whether/how
      // this payload happens to be compressed on the wire.
      const message = sha256(Buffer.concat([preview, bodyPlain]));
      const { signature, signerPubkey, signerId } = mockSign(message, randomBytes(8));
      preview = buildContentPreview({
        cacheId, hint, mimeType,
        signatureAlgorithm: 1, signerId, signerLabel: 'Test Signer',
      });
      const signedBody = buildContentBody({ content, signature, signerPubkey });
      const signedBodyForWire = compress ? compressWrap(signedBody) : signedBody;
      return [Buffer.concat([preview, signedBodyForWire])];
    }
    return [Buffer.concat([preview, bodyForWire])];
  }

  // multi-code: Split-wrap bodyForWire, repeat Preview on every code
  assert.ok(!sign, 'multi-code signing not exercised in this prototype (single-code path already covers the placeholder/strip discipline; multi-code adds only Split framing on top, orthogonal to signing)');
  const preview = buildContentPreview({ cacheId, hint, mimeType });
  const fragmentCount = Math.ceil(bodyForWire.length / maxBodyBytes);
  const groupId = sha256(bodyForWire).subarray(0, 8);
  const fragments = splitFragments(bodyForWire, groupId, fragmentCount, true);
  return fragments.map((frag) => Buffer.concat([preview, frag]));
}

function encodedPreviewBytesFor(typeId, knownKeys, record) {
  const fields = {};
  for (const k of knownKeys) if (record[k] !== undefined) fields[k] = record[k];
  return encodeRecordWithTypeId(typeId, fields);
}

function decodeContentPayload(codes) {
  let preview;
  let previewBytesForComparison;
  const fragmentRecords = [];
  let plainBodyBytes = null;

  for (const code of codes) {
    const items = decodeSequence(code);
    assert.equal(items.length, 2, 'each code must carry exactly Preview + one Body-shaped Record');
    const [previewRecord, second] = items;
    assert.equal(previewRecord[0], TYPE.CONTENT_PREVIEW, 'first Record must be Content-Preview');
    assertKnownKeys(previewRecord, KNOWN_KEYS.CONTENT_PREVIEW, 'Content-Preview');

    // SPEC.md §5.1: Preview MUST be identical on every code in a group —
    // verify by re-encoding, not just "a Preview was present."
    const thisPreviewBytes = encodedPreviewBytesFor(TYPE.CONTENT_PREVIEW, KNOWN_KEYS.CONTENT_PREVIEW, previewRecord);
    if (previewBytesForComparison) {
      assert.ok(thisPreviewBytes.equals(previewBytesForComparison), 'Preview must be byte-identical on every code in the group');
    }
    previewBytesForComparison = thisPreviewBytes;
    preview = previewRecord;

    if (second[0] === TYPE.SPLIT) {
      fragmentRecords.push(second);
    } else {
      plainBodyBytes = second; // re-encode below via resolveNonSplitWrapperStack on raw bytes
    }
  }

  let body;
  if (fragmentRecords.length > 0) {
    const groupId = fragmentRecords[0][2];
    const reassembled = reassembleSplit(fragmentRecords, groupId);
    body = resolveNonSplitWrapperStack(reassembled);
  } else {
    // secondRecord was already decoded by decodeSequence; if it's a Compress
    // Wrapper, inflate its payload and decode that as the real Body Record.
    const secondRecord = plainBodyBytes;
    body = secondRecord[0] === TYPE.COMPRESS
      ? decodeRecord(inflateRawSync(secondRecord[2])).value
      : secondRecord;
  }
  assertKnownKeys(body, KNOWN_KEYS.CONTENT_BODY, 'Content-Body');

  return { preview, body };
}

// ── Test vectors ──

function testSingleCodeContent() {
  const codes = buildContentPayload({ hint: 'under the bridge', mimeType: 'text/html', content: Buffer.from('<p>hello</p>') });
  assert.equal(codes.length, 1, 'small content must fit one code');
  const { preview, body } = decodeContentPayload(codes);
  assert.equal(preview[3], 'under the bridge');
  assert.equal(preview[5], 'text/html');
  assert.ok(body[1].equals(Buffer.from('<p>hello</p>')));
  return { name: 'single-code-content', codes: codes.map((c) => c.toString('hex')) };
}

function testSingleCodeSignedContent() {
  const codes = buildContentPayload({ hint: 'signed note', mimeType: 'text/plain', content: Buffer.from('hello, signed world'), sign: true });
  assert.equal(codes.length, 1);
  const { preview, body } = decodeContentPayload(codes);
  assert.equal(preview[45], 1, 'signature_algorithm must be present');
  assert.equal(preview[47].length, 8, 'signer_id must be 8 bytes');
  assert.equal(body[3].length, MOCK_SIGNATURE_LEN, 'signature field must be the fixed ML-DSA-44 length');
  assert.equal(body[5].length, MOCK_PUBKEY_LEN, 'signer_pubkey field must be the fixed ML-DSA-44 length');
  assert.ok(body[1].equals(Buffer.from('hello, signed world')), 'content must survive alongside the signature fields, unmodified');
  return { name: 'single-code-signed-content', codes: codes.map((c) => c.toString('hex')) };
}

function testMultiCodeContentSplitCompressParity() {
  const bigContent = Buffer.from('x'.repeat(5000), 'utf8');
  // maxBodyBytes deliberately tiny: 'x'.repeat(5000) DEFLATEs down to well
  // under 900 bytes, so a realistic threshold wouldn't actually force a
  // split here — this forces genuine multi-fragment Split regardless of
  // how well the (highly redundant, deliberately so) test content compresses.
  const codes = buildContentPayload({
    hint: 'a long essay', mimeType: 'text/plain', content: bigContent,
    maxBodyBytes: 10, compress: true,
  });
  assert.ok(codes.length > 1, 'large content must split across multiple codes');

  // full reassembly
  const { preview, body } = decodeContentPayload(codes);
  assert.equal(preview[3], 'a long essay');
  assert.ok(body[1].equals(bigContent), 'reassembled+decompressed content must match the original exactly');

  // drop one data fragment (not the parity one) and confirm recovery
  const droppedIndex = 1;
  const codesWithOneDropped = codes.filter((_, i) => i !== droppedIndex);
  assert.ok(codesWithOneDropped.length < codes.length);
  const recovered = decodeContentPayload(codesWithOneDropped);
  assert.ok(recovered.body[1].equals(bigContent), 'XOR parity must recover the missing fragment exactly');

  return {
    name: 'multi-code-content-split-compress-parity',
    codeCount: codes.length,
    codes: codes.map((c) => c.toString('hex')),
  };
}

// ── General Paper builder/decoder, mirroring buildContentPayload/decodeContentPayload ──

function buildPaperPayload({ hint, set, slug, files, related, maxBodyBytes = 900, split = false, compress = false }) {
  const bodyNoSig = buildPaperBody({ files, related });
  const bodyForWire = compress ? compressWrap(bodyNoSig) : bodyNoSig;

  // root_hash placeholder-then-strip discipline (SPEC.md §4.4/§10): hash
  // Preview' (no root_hash) || Body' (logical, pre-compression, no sig
  // fields — none present yet in this prototype's Paper builder anyway),
  // THEN fill root_hash into the real Preview.
  const previewNoHash = buildPaperPreview({ hint, set, slug });
  const rootHash = sha256(Buffer.concat([previewNoHash, bodyNoSig])).subarray(0, 8);
  const preview = buildPaperPreview({ rootHash, hint, set, slug });

  const needsSplit = split || bodyForWire.length > maxBodyBytes;
  if (!needsSplit) {
    return [Buffer.concat([preview, bodyForWire])];
  }

  const fragmentCount = Math.ceil(bodyForWire.length / maxBodyBytes);
  const groupId = sha256(bodyForWire).subarray(0, 8);
  const fragments = splitFragments(bodyForWire, groupId, fragmentCount, true);
  return fragments.map((frag) => Buffer.concat([preview, frag]));
}

function decodePaperPayload(codes) {
  let preview;
  let previewBytesForComparison;
  const fragmentRecords = [];
  let secondRecord = null;

  for (const code of codes) {
    const items = decodeSequence(code);
    assert.equal(items.length, 2, 'each code must carry exactly Preview + one Body-shaped Record');
    const [previewRecord, second] = items;
    assert.equal(previewRecord[0], TYPE.PAPER_PREVIEW, 'first Record must be Paper-Preview');
    assertKnownKeys(previewRecord, KNOWN_KEYS.PAPER_PREVIEW, 'Paper-Preview');

    const thisPreviewBytes = encodedPreviewBytesFor(TYPE.PAPER_PREVIEW, KNOWN_KEYS.PAPER_PREVIEW, previewRecord);
    if (previewBytesForComparison) {
      assert.ok(thisPreviewBytes.equals(previewBytesForComparison), 'Preview must be byte-identical on every code in the group');
    }
    previewBytesForComparison = thisPreviewBytes;
    preview = previewRecord;

    if (second[0] === TYPE.SPLIT) fragmentRecords.push(second);
    else secondRecord = second;
  }

  let body;
  if (fragmentRecords.length > 0) {
    const groupId = fragmentRecords[0][2];
    const reassembled = reassembleSplit(fragmentRecords, groupId);
    body = resolveNonSplitWrapperStack(reassembled);
  } else {
    body = secondRecord[0] === TYPE.COMPRESS
      ? decodeRecord(inflateRawSync(secondRecord[2])).value
      : secondRecord;
  }
  assertKnownKeys(body, KNOWN_KEYS.PAPER_BODY, 'Paper-Body');

  // Verify root_hash independently of however this payload happened to be
  // carried (single code, or Split/Compress-wrapped across several) — same
  // recomputation the placeholder-then-strip discipline requires at build
  // time, now run in reverse.
  const previewNoHash = buildPaperPreview({ hint: preview[3], set: preview[5], slug: preview[7] });
  const bodyNoSig = buildPaperBody({ files: body[1], related: body[3] });
  const recomputedRootHash = sha256(Buffer.concat([previewNoHash, bodyNoSig])).subarray(0, 8);
  assert.ok(preview[1].equals(recomputedRootHash), "root_hash must match the recomputed Preview'||Body' hash");

  return { preview, body };
}

function testSingleCodePaper() {
  const files = encodeArray([
    encodeRecord({ 1: 'index', 2: 'text/html', 3: randomBytes(8) }),
    encodeRecord({ 1: 'map', 2: 'image/svg+xml', 3: randomBytes(8) }),
  ]);
  const related = encodeArray([encodeRecord({ 1: 'Next stop: the red letterbox', 2: 'sunset-trail', 10: 4 })]);

  const codes = buildPaperPayload({ hint: 'Trail Stop 3', set: 'sunset-trail', slug: 'oak-tree', files, related });
  assert.equal(codes.length, 1, 'small paper must fit one code');

  const { preview, body } = decodePaperPayload(codes);
  assert.equal(preview[3], 'Trail Stop 3');
  const decodedFiles = decodeItem(body[1], 0).value;
  assert.equal(decodedFiles.length, 2);
  assert.equal(decodedFiles[0][1], 'index');

  return { name: 'single-code-paper', rootHash: preview[1].toString('hex'), codes: codes.map((c) => c.toString('hex')) };
}

function testMultiCodePaper() {
  const files = encodeArray(
    Array.from({ length: 60 }, (_, i) =>
      encodeRecord({ 1: `file-${i}`, 2: 'text/plain', 3: randomBytes(8), 4: `Teaser for file ${i}` }))
  );
  const related = encodeArray([
    encodeRecord({ 1: 'Next stop: the red letterbox', 2: 'sunset-trail', 10: 4 }),
    encodeRecord({ 1: 'Start of trail: town square', 2: 'sunset-trail', 10: 1 }),
  ]);

  const codes = buildPaperPayload({
    hint: 'Trail Stop 3 — the big one', set: 'sunset-trail', slug: 'oak-tree',
    files, related, maxBodyBytes: 900, compress: true,
  });
  assert.ok(codes.length > 1, '60 files must not fit one code even compressed');

  // Preview repeated on every code (SPEC.md §5.1) — verify identically, not just "present"
  const previews = codes.map((c) => decodeSequence(c)[0]);
  const firstPreviewBytes = encodeRecordWithTypeId(TYPE.PAPER_PREVIEW, { 1: previews[0][1], 3: previews[0][3], 5: previews[0][5], 7: previews[0][7] });
  for (const p of previews.slice(1)) {
    const pBytes = encodeRecordWithTypeId(TYPE.PAPER_PREVIEW, { 1: p[1], 3: p[3], 5: p[5], 7: p[7] });
    assert.ok(pBytes.equals(firstPreviewBytes), 'Preview must be byte-identical on every code in the group');
  }

  const { preview, body } = decodePaperPayload(codes);
  assert.equal(preview[3], 'Trail Stop 3 — the big one');
  const decodedFiles = decodeItem(body[1], 0).value;
  assert.equal(decodedFiles.length, 60);
  assert.equal(decodedFiles[59][1], 'file-59');

  return { name: 'multi-code-paper', codeCount: codes.length, rootHash: preview[1].toString('hex'), codes: codes.map((c) => c.toString('hex')) };
}

function testEncryptedContent() {
  // SPEC.md §9: a hidden, encrypted override map, discovered by trial
  // decryption — never declared, never assumed absent from an `encryption`
  // hint. Real AES-256-GCM here (unlike signing's mock bytes), since
  // Node's crypto module makes that free — no reason to fake this one.
  const keyMaterial = randomBytes(32);
  const realContent = Buffer.from('the real secret map location');
  const overrideMap = encodeRecord({ 1: 'treasure map', 3: 'image/png', 5: realContent, 7: 'map.png' });
  const compressedOverride = deflateRawSync(overrideMap);

  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', keyMaterial, nonce);
  const ciphertext = Buffer.concat([cipher.update(compressedOverride), cipher.final()]);
  const tag = cipher.getAuthTag();
  const blob = Buffer.concat([nonce, ciphertext, tag]);

  // cache_id MUST be random, not content-addressed, whenever a hidden
  // override map might be present (§4.4/§9) — using the cover story's own
  // hash here would leak whether the "real" content matches a known
  // cache_id, defeating the whole point.
  const cacheId = randomBytes(8);
  const coverHint = 'just a locked note';
  const preview = buildContentPreview({ cacheId, hint: coverHint, mimeType: 'text/plain', encryption: 1 });
  const body = buildContentBody({ content: blob });
  const codes = [Buffer.concat([preview, body])];

  // Cover story is what's visible without the key.
  const items = decodeSequence(codes[0]);
  assert.equal(items[0][3], coverHint, 'cover hint must be visible with no key present');

  // Trial decryption (§9 "Discovery, not declaration") — try the candidate
  // blob against a known key_material; a wrong key must fail the GCM
  // authentication tag, not silently produce garbage plaintext.
  const candidateBlob = items[1][1];
  const candidateNonce = candidateBlob.subarray(0, 12);
  const rest = candidateBlob.subarray(12);
  const candidateTag = rest.subarray(rest.length - 16);
  const candidateCiphertext = rest.subarray(0, rest.length - 16);

  const wrongKey = randomBytes(32);
  assert.throws(() => {
    const wrongDecipher = createDecipheriv('aes-256-gcm', wrongKey, candidateNonce);
    wrongDecipher.setAuthTag(candidateTag);
    Buffer.concat([wrongDecipher.update(candidateCiphertext), wrongDecipher.final()]);
  }, /auth/i, 'a wrong key_material MUST fail GCM authentication, not silently decrypt to garbage');

  const decipher = createDecipheriv('aes-256-gcm', keyMaterial, candidateNonce);
  decipher.setAuthTag(candidateTag);
  const decompressed = inflateRawSync(Buffer.concat([decipher.update(candidateCiphertext), decipher.final()]));
  const overrideDecoded = decodeRecord(decompressed).value;

  assert.equal(overrideDecoded[1], 'treasure map');
  assert.equal(overrideDecoded[3], 'image/png');
  assert.ok(overrideDecoded[5].equals(realContent), 'override map content must survive compress+encrypt+decrypt+decompress exactly');
  assert.equal(overrideDecoded[7], 'map.png');

  return { name: 'encrypted-content-override-map', codes: codes.map((c) => c.toString('hex')) };
}

function testTamperedFragmentDetected() {
  // The actual security property SPEC.md §3 states group_id exists for:
  // "an adversary who substitutes one sector of a multi-sector payload
  // after the fact... goes undetected [without this check]." Every prior
  // Split test only ever exercised a *missing* fragment (recovered via
  // parity) — never a *present but corrupted* one, which is the real
  // threat the hash check, not the parity check, is what defends against.
  const bigContent = Buffer.from('y'.repeat(5000), 'utf8');
  const codes = buildContentPayload({
    hint: 'tamper test', mimeType: 'text/plain', content: bigContent,
    maxBodyBytes: 10, compress: true,
  });
  assert.ok(codes.length > 2, 'need at least two data fragments plus parity to isolate one non-parity fragment');

  const code = codes[1];
  const previewItem = decodeItem(code, 0);
  const rawPreviewBytes = code.subarray(0, previewItem.next);
  const fragmentItem = decodeItem(code, previewItem.next);
  const fragmentRecord = fragmentItem.value;
  assert.equal(fragmentRecord[0], TYPE.SPLIT);

  // Flip one bit in the fragment's data, leaving its declared group_id (key
  // 2) field untouched — the tamper must be caught by recomputing the hash
  // from the actual reassembled bytes, not by comparing declared fields.
  const tamperedFragmentBytes = Buffer.from(fragmentRecord[8]);
  tamperedFragmentBytes[0] ^= 0xff;
  const tamperedFragmentRecordBytes = encodeRecordWithTypeId(TYPE.SPLIT, {
    2: fragmentRecord[2], 4: fragmentRecord[4], 6: fragmentRecord[6],
    8: tamperedFragmentBytes, 9: fragmentRecord[9], 11: fragmentRecord[11],
  });
  const tamperedCode = Buffer.concat([rawPreviewBytes, tamperedFragmentRecordBytes]);
  const tamperedCodes = codes.map((c, i) => (i === 1 ? tamperedCode : c));

  assert.throws(
    () => decodeContentPayload(tamperedCodes),
    /group_id/,
    'a tampered (not missing) fragment must be caught by group_id verification, not silently reassembled'
  );

  return { name: 'tampered-fragment-detected-via-group-id' };
}

function testTamperedPaperRootHashDetected() {
  const files = encodeArray([encodeRecord({ 1: 'index', 2: 'text/html', 3: randomBytes(8) })]);
  const related = encodeArray([]);
  const codes = buildPaperPayload({ hint: 'Tamper Test Paper', set: 'x', slug: 'y', files, related });
  assert.equal(codes.length, 1);

  const previewItem = decodeItem(codes[0], 0);
  const rawPreviewBytes = codes[0].subarray(0, previewItem.next);
  const bodyItem = decodeItem(codes[0], previewItem.next);
  const bodyRecord = bodyItem.value;

  const tamperedFiles = encodeArray([encodeRecord({ 1: 'index-RENAMED', 2: 'text/html', 3: randomBytes(8) })]);
  const tamperedBodyBytes = encodeRecordWithTypeId(TYPE.PAPER_BODY, { 1: tamperedFiles, 3: bodyRecord[3] });
  const tamperedCode = Buffer.concat([rawPreviewBytes, tamperedBodyBytes]);

  assert.throws(
    () => decodePaperPayload([tamperedCode]),
    /root_hash/,
    'a Body tampered after root_hash was computed must be caught on decode, not silently accepted'
  );

  return { name: 'tampered-paper-root-hash-detected' };
}

function testKeyOnlyCode() {
  // SPEC.md §9 "Decryption keys": a Content-Preview carrying key_material
  // but no Body at all — a code can be just a key, with nothing of its own
  // to identify (no cache_id) or display.
  const keyMaterial = randomBytes(32);
  const code = buildContentPreview({ keyMaterial, retainKey: false });

  const items = decodeSequence(code);
  assert.equal(items.length, 1, 'a key-only code carries Preview alone, no Body Record');
  assert.equal(items[0][0], TYPE.CONTENT_PREVIEW);
  assert.ok(items[0][33].equals(keyMaterial));
  assert.equal(items[0][35], false, 'retain_key must round-trip as a real boolean, not a truthy placeholder');
  assert.equal(items[0][1], undefined, 'a key-only code has no cache_id — nothing of its own to identify');

  return { name: 'key-only-code', codes: [code.toString('hex')] };
}

function testEvenOddCriticality() {
  // SPEC.md §2.2 / QDEF-SPEC.md §3.2. Nothing in this file's own field set
  // exercises this — every TagDrop key defined today is odd — so this
  // synthesizes a "future" field of each parity to prove the mechanism
  // itself, not just TagDrop's current (all-odd) usage of it.
  const withUnknownOdd = encodeRecordWithTypeId(TYPE.CONTENT_PREVIEW, { 1: randomBytes(8), 3: 'hi', 101: 'a future optional field' });
  const decodedOdd = decodeRecord(withUnknownOdd).value;
  assertKnownKeys(decodedOdd, KNOWN_KEYS.CONTENT_PREVIEW, 'Content-Preview'); // must not throw
  assert.equal(decodedOdd[3], 'hi', 'known fields must stay readable alongside an unrecognized odd key');

  const withUnknownEven = encodeRecordWithTypeId(TYPE.CONTENT_PREVIEW, { 1: randomBytes(8), 3: 'hi', 100: 'a future critical field' });
  const decodedEven = decodeRecord(withUnknownEven).value;
  assert.throws(
    () => assertKnownKeys(decodedEven, KNOWN_KEYS.CONTENT_PREVIEW, 'Content-Preview'),
    /CRITICAL \(even\) key 100/,
    'an unrecognized even key must abort processing this Record'
  );

  return { name: 'even-odd-criticality' };
}

function main() {
  const results = [];
  console.log('QDEF-redesign round-trip prototype (SPEC.md v2)\n');

  for (const test of [
    testSingleCodeContent,
    testSingleCodeSignedContent,
    testMultiCodeContentSplitCompressParity,
    testEncryptedContent,
    testSingleCodePaper,
    testMultiCodePaper,
    testTamperedFragmentDetected,
    testTamperedPaperRootHashDetected,
    testKeyOnlyCode,
    testEvenOddCriticality,
  ]) {
    const label = test.name;
    try {
      const result = test();
      console.log(`  PASS  ${label}` + (result.codeCount ? ` (${result.codeCount} codes)` : ''));
      results.push(result);
    } catch (err) {
      console.error(`  FAIL  ${label}: ${err.message}`);
      process.exitCode = 1;
    }
  }

  const fixturesPath = new URL('./qdef-fixtures.json', import.meta.url);
  writeFileSync(fixturesPath, JSON.stringify(results, null, 2));
  console.log(`\nFixtures written to ${fixturesPath.pathname}`);
}

main();
