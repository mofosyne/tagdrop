/**
 * QDEF wire-shape validation suite: builds TagDrop's version-9 wire shape
 * (SPEC.md §2-§5) — array-wrapped Records with subrecords, Content Extension/
 * Media Preview/Media Payload/Content Signature, Paper-Preview/Paper-Body,
 * QDEF Split/Compress Wrapper Records, the root_hash/signed-message
 * placeholder-then-strip discipline — and round-trips it through
 * encode → decode → reassemble → verify, entirely in-memory (no QR
 * rendering, no Base41, no real ML-DSA-44 — signatures here are
 * fixed-length mock bytes, standing in only to exercise the *byte layout*
 * discipline SPEC.md §10 requires, not the cryptography itself, which is
 * proven separately by the real sign/verify implementations in
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
 * and the Kotlin port (app/src/test/.../TagDropCodecTest.kt +
 * SectorAssemblerTest.kt) have all landed on version 9.
 *
 * Run with:
 *   cd tools
 *   node test-qdef-roundtrip.mjs
 */
import { deflateRawSync, inflateRawSync } from 'node:zlib';
import { createHash, randomBytes, createCipheriv, createDecipheriv } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

// ── Record Type IDs (SPEC.md §2.1, §3.1a; Split/Compress from QDEF-SPEC.md §4.1) ──
// All eight values fit in one CBOR byte (0-23) — no BigInt handling needed
// anywhere in this file; decodeItem's own BigInt-downgrade-when-safe logic
// (majorType-0 branch) is generic decoder infrastructure, not something
// these specific values ever actually exercise.
const TYPE = {
  SPLIT: 2,
  COMPRESS: 8,
  CONTENT_EXTENSION: 1,
  CONTENT_SIGNATURE: 3,
  PAPER_PREVIEW: 5,
  PAPER_BODY: 7,
  MEDIA_PREVIEW: 14,
  MEDIA_PAYLOAD: 6,
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

// fields: plain object, integer-string keys, undefined values are omitted.
// A negative key (a QDEF Common Field Key, QDEF-SPEC.md §3.6 — always odd/
// optional) encodes as a CBOR negative integer (major type 1: argument
// `-(k+1)`) instead of the ordinary major-type-0 uint.
function encodeRecord(fields) {
  const keys = Object.keys(fields)
    .filter((k) => fields[k] !== undefined)
    .map(Number)
    .sort((a, b) => a - b);
  const parts = [encodeUInt(keys.length, 5)];
  for (const k of keys) {
    parts.push(k >= 0 ? encodeUInt(k, 0) : encodeUInt(-k - 1, 1));
    parts.push(encodeValue(fields[String(k)]));
  }
  return Buffer.concat(parts);
}

// A QDEF Record (QDEF-SPEC.md §3.1, v11): a self-delimited CBOR array,
// [typeId, map?, payload?, subrecord*]. The map is omitted entirely only
// when `fields` itself has no declared keys at all (a static, per-call-
// site choice — e.g. Compress Wrapper, whose one value moved to the
// payload slot) — NOT whenever every declared field's value happens to be
// undefined, since Types that always declare fields (Content Extension,
// Paper-Preview/Body) need a stable map for stripKeys/signature-hash
// formulas to work against even when every optional field is unset.
// `payload`, if given, is a Buffer encoded as a CBOR byte string (major
// type 2) — never array-shaped, so it's always unambiguous against
// `subrecords` (array, major type 4) with no marker needed. `subrecords`
// are already-encoded array-Record byte sequences (each itself built by a
// nested encodeArrayRecord call), spliced in as their own array items.
function encodeArrayRecord(typeId, fields, subrecords = [], payload = null) {
  const items = [encodeUInt(typeId, 0)];
  if (Object.keys(fields).length > 0) items.push(encodeRecord(fields));
  if (payload !== null) items.push(encodeBytes(payload));
  items.push(...subrecords);
  return encodeArray(items);
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
    // rare genuinely-large value as BigInt rather than silently losing
    // precision.
    const value = arg <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(arg) : arg;
    return { value, next: argOffset };
  }
  if (majorType === 1) {
    // A QDEF Common Field Key (QDEF-SPEC.md §3.6) — always small in TagDrop's
    // own usage (-1..-15), so a plain Number downgrade (unlike majorType 0
    // above) needs no BigInt-safety check.
    const value = -(Number(arg) + 1);
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

/**
 * Decodes one QDEF Record (a self-delimited CBOR array, `[typeId, map?,
 * payload?, subrecord*]`, QDEF-SPEC.md §3.1, v11) from the head of `buf` at
 * `offset`. Returns `{ typeId, record, payload, raw, subrecords, next }` —
 * `record` is `{}` when the map was omitted, `payload` the payload-slot
 * Buffer (or null if absent — never array-shaped, so it's always
 * unambiguous against a following subrecord by major type alone: major 5
 * is the map, anything else non-major-4 right after is the payload, and an
 * array (major 4) always means subrecords start here), `raw` is this
 * Record's own exact byte range (what signature/group-id hashes are
 * computed over), `subrecords` an array of the same shape (recursively —
 * each with `raw` relative to the ORIGINAL `buf`, not sliced first), `next`
 * the offset immediately after this Record.
 */
function decodeArrayRecord(buf, offset) {
  const head = buf[offset];
  if (head >> 5 !== 4) throw new Error('decodeArrayRecord: expected a CBOR array (major 4)');
  const info = head & 0x1f;
  let argOffset = offset + 1;
  let count;
  if (info < 24) {
    count = info;
  } else if (info === 24) {
    count = buf[argOffset];
    argOffset += 1;
  } else if (info === 25) {
    count = buf.readUInt16BE(argOffset);
    argOffset += 2;
  } else {
    throw new Error('decodeArrayRecord: unsupported array length encoding');
  }
  if (count < 1) throw new Error('decodeArrayRecord: a Record needs at least [typeId]');

  let cur = argOffset;
  const typeIdItem = decodeItem(buf, cur);
  cur = typeIdItem.next;
  let remaining = count - 1;

  let record = {};
  if (remaining > 0 && (buf[cur] >> 5) === 5) {
    const mapItem = decodeItem(buf, cur);
    record = mapItem.value;
    cur = mapItem.next;
    remaining--;
  }

  let payload = null;
  if (remaining > 0 && (buf[cur] >> 5) !== 4) {
    const payloadItem = decodeItem(buf, cur);
    if (!Buffer.isBuffer(payloadItem.value)) throw new Error('decodeArrayRecord: expected payload to be a byte string');
    payload = payloadItem.value;
    cur = payloadItem.next;
    remaining--;
  }

  const subrecords = [];
  for (let i = 0; i < remaining; i++) {
    const sub = decodeArrayRecord(buf, cur);
    subrecords.push(sub);
    cur = sub.next;
  }

  const typeId = typeof typeIdItem.value === 'bigint'
    ? (typeIdItem.value <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(typeIdItem.value) : typeIdItem.value)
    : typeIdItem.value;

  return { typeId, record, payload, raw: buf.subarray(offset, cur), subrecords, next: cur };
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

// ── Compress Wrapper (QDEF-SPEC.md §4.1 Type 8) — DEFLATEs `bytes` into the
// payload slot (SPEC.md v11): [8, deflated_bytes], no field map at all. ──
function compressWrap(bytes) {
  return encodeArrayRecord(TYPE.COMPRESS, {}, [], deflateRawSync(bytes));
}

// ── Split Wrapper (QDEF-SPEC.md §4.1 Type 2) — fragment / reassemble / XOR parity ──
// `subrecords` (SPEC.md §3.1a) is attached to every fragment Record unwrapped
// and repeated — used to carry Content's Media Preview alongside a
// Split-wrapped Media Payload; Paper has none, so it's `[]` by default.
function splitFragments(bytes, groupId, fragmentCount, withParity, subrecords = []) {
  const totalBytes = bytes.length;
  const chunkLen = Math.ceil(totalBytes / fragmentCount);
  const fragments = [];
  for (let i = 0; i < fragmentCount; i++) {
    const slice = bytes.subarray(i * chunkLen, Math.min((i + 1) * chunkLen, totalBytes));
    fragments.push(
      encodeArrayRecord(TYPE.SPLIT, {
        0: groupId,
        2: i,
        4: fragmentCount,
        6: slice,
        7: totalBytes,
        9: withParity ? 1 : undefined,
      }, subrecords)
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
      encodeArrayRecord(TYPE.SPLIT, {
        0: groupId,
        2: fragmentCount,
        4: fragmentCount,
        6: parity,
        7: totalBytes,
        9: 1,
      }, subrecords)
    );
  }
  return fragments;
}

function reassembleSplit(fragmentRecords, expectedGroupId) {
  const byIndex = new Map();
  let count, totalBytes;
  for (const rec of fragmentRecords) {
    assert.ok(rec[0].equals(expectedGroupId), 'group_id mismatch across fragments');
    count = rec[4];
    totalBytes = rec[7];
    byIndex.set(rec[2], rec[6]);
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

// Recursively unwrap a Compress Wrapper stack until a plain Record remains.
// (Split reassembly needs all fragments up front, so this handles the
// single-code "already a plain/Compress-wrapped Record" case; multi-code
// reassembly is driven explicitly in the payload-level decode functions
// below, mirroring how a real decoder accumulates fragments across scans.)
function resolveNonSplitWrapperStack(bytes) {
  let cur = bytes;
  for (;;) {
    const decoded = decodeArrayRecord(cur, 0);
    if (decoded.typeId === TYPE.COMPRESS) {
      cur = inflateRawSync(decoded.payload);
      continue;
    }
    return decoded;
  }
}

function sha256(buf) {
  return createHash('sha256').update(buf).digest();
}

// ── contentHash / root_hash / signed-message (SPEC.md §4.4, §10) ──
// Mock signing: fixed-length random bytes standing in for real ML-DSA-44,
// per this file's header comment — exercises the placeholder-then-strip
// byte-layout discipline, not the cryptography. Because the mock signature
// is fixed-length and its message argument is never actually checked
// against anything, this file uses the simpler "build unsigned once (for
// the hash), then build the final signed version" pattern rather than the
// real codecs' same-length-placeholder-then-strip discipline — both
// produce byte-identical unsigned fields either way (SPEC §10 "signing
// happens last and feeds back into nothing"), so nothing here that this
// suite actually checks would tell the two approaches apart.
const MOCK_SIGNATURE_LEN = 2420;
const MOCK_PUBKEY_LEN = 1312;

function mockSign(_message, signerId) {
  return { signature: randomBytes(MOCK_SIGNATURE_LEN), signerPubkey: randomBytes(MOCK_PUBKEY_LEN), signerId };
}

// ── Content Extension / Media Preview / Media Payload / Content Signature (SPEC.md v9 §3.1/§3.1a) ──

// Content Extension (Type 1) — TagDrop-specific fields only; file
// identification (contentHash/mediaType/filename/label) lives in Media
// Preview, large signing fields in Content Signature.
function buildContentExtension(f) {
  return encodeArrayRecord(TYPE.CONTENT_EXTENSION, {
    3: f.hint,
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
  CONTENT_EXTENSION: new Set([3, 33, 35, 37, 45, 47, 49]),
  MEDIA_PREVIEW: new Set([0, -11, -15]), // contentHash/filename moved to Common Field Keys (v11)
  MEDIA_PAYLOAD: new Set([0]), // content moved to the payload slot (v11)
  CONTENT_SIGNATURE: new Set([3, 5]),
  PAPER_PREVIEW: new Set([1, 3, 5, 7, 31, 33, 35]),
  PAPER_BODY: new Set([1, 3, 5, 7]),
};

function assertKnownKeys(record, knownKeys, label) {
  for (const keyStr of Object.keys(record)) {
    const key = Number(keyStr);
    if (knownKeys.has(key)) continue;
    if (key % 2 === 0) {
      throw new Error(`${label}: unrecognized CRITICAL (even) key ${key} - must abort this Record (QDEF-SPEC.md S3.2)`);
    }
    // unrecognized odd key: silently ignored, no action
  }
}

// Media Preview (QDEF standard Type 14, SPEC.md v9 §3.1a) — file
// identification. `contentHash`/`filename` are QDEF Common Field Keys
// (§3.6, SPEC.md v11: -11/-15) -- mediaType is the only field left that's
// specific to this Type. `contentHash` is multihash-style: a 1-byte
// function code (0x12 = sha2-256) prepended to the 8-byte digest.
// `subrecords` carries Media Payload when it fits nested here (single-code
// case) or is omitted (multi-code case, where Media Preview itself becomes
// Split's subrecord instead — SPEC.md §5.1).
function buildMediaPreview(f, subrecords = []) {
  return encodeArrayRecord(TYPE.MEDIA_PREVIEW, {
    0: f.mediaType,
    [-11]: f.contentHash ? Buffer.concat([Buffer.from([0x12]), f.contentHash]) : undefined,
    [-15]: f.filename,
  }, subrecords);
}

// Media Payload (QDEF standard Type 6, SPEC.md v9 §3.1a) — mediaType stays
// in the field map; the content bytes moved to the payload slot (SPEC.md
// v11), since Media Payload's whole point is carrying exactly one blob.
// `subrecords` carries Content Signature (Type 3) when the payload is
// signed, so `signature`/`signer_pubkey` travel wherever Media Payload's
// own bytes travel (nested once, not repeated per code — SPEC.md §3.1).
function buildMediaPayload(f, subrecords = []) {
  return encodeArrayRecord(TYPE.MEDIA_PAYLOAD, { 0: f.mediaType }, subrecords, f.content);
}

// Content Signature (Type 3, TagDrop-scoped, SPEC.md v9 §3.1a) —
// signature/signer_pubkey for a signed Content payload, nested as Media
// Payload's own subrecord. Absent entirely (no Record at all) when unsigned.
function buildContentSignature(f) {
  return encodeArrayRecord(TYPE.CONTENT_SIGNATURE, { 3: f.signature, 5: f.signerPubkey });
}

// ── Paper-Preview / Paper-Body (SPEC.md §3.3-§3.4, unaffected by v9's Content restructuring) ──
function buildPaperPreview(f) {
  return encodeArrayRecord(TYPE.PAPER_PREVIEW, {
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
  return encodeArrayRecord(TYPE.PAPER_BODY, {
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

  const extension = buildContentExtension({ hint });
  // The LOGICAL (bare, no subrecord) Media Preview bytes — for hashing, and
  // reused unwrapped as Split's own repeated subrecord in the multi-code case.
  const mediaPreviewBare = buildMediaPreview({ mediaType: mimeType, contentHash: cacheId });

  const mediaPayloadBare = buildMediaPayload({ mediaType: mimeType, content });
  const mediaPayloadForWire = compress ? compressWrap(mediaPayloadBare) : mediaPayloadBare;

  const needsSplit = split || mediaPayloadForWire.length > maxBodyBytes;

  if (!needsSplit) {
    if (sign) {
      // SPEC.md §10: sign SHA-256(MediaPreview' || MediaPayload'' || Extension'),
      // the LOGICAL (uncompressed, no Content Signature subrecord, no signing
      // fields) bytes — never the wire-transmitted (possibly Compress-wrapped,
      // possibly-already-signed) ones.
      const message = sha256(Buffer.concat([mediaPreviewBare, mediaPayloadBare, extension]));
      const { signature, signerPubkey, signerId } = mockSign(message, randomBytes(8));
      const signedExtension = buildContentExtension({ hint, signatureAlgorithm: 1, signerId, signerLabel: 'Test Signer' });
      const contentSignature = buildContentSignature({ signature, signerPubkey });
      const signedMediaPayloadBare = buildMediaPayload({ mediaType: mimeType, content }, [contentSignature]);
      const signedMediaPayloadForWire = compress ? compressWrap(signedMediaPayloadBare) : signedMediaPayloadBare;
      const wireMediaPreview = buildMediaPreview({ mediaType: mimeType, contentHash: cacheId }, [signedMediaPayloadForWire]);
      return [Buffer.concat([signedExtension, wireMediaPreview])];
    }
    // Single code: Media Payload (or its Compress Wrapper) nests as Media
    // Preview's own subrecord (§3.1a) — not a separate sibling Record.
    const wireMediaPreview = buildMediaPreview({ mediaType: mimeType, contentHash: cacheId }, [mediaPayloadForWire]);
    return [Buffer.concat([extension, wireMediaPreview])];
  }

  // multi-code: Split-wrap mediaPayloadForWire; Media Preview becomes Split's
  // own repeated subrecord instead (§3.1a); Content Extension stays a
  // separate top-level Record, repeated per code exactly as in the
  // single-code case above.
  assert.ok(!sign, 'multi-code signing not exercised in this prototype (single-code path already covers the placeholder/strip discipline; multi-code adds only Split framing on top, orthogonal to signing)');
  const fragmentCount = Math.ceil(mediaPayloadForWire.length / maxBodyBytes);
  const groupId = sha256(mediaPayloadForWire).subarray(0, 8);
  const fragments = splitFragments(mediaPayloadForWire, groupId, fragmentCount, true, [mediaPreviewBare]);
  return fragments.map((frag) => Buffer.concat([extension, frag]));
}

function decodeContentPayload(codes) {
  let extension;
  let extensionRawForComparison;
  const fragmentRecords = [];
  let mediaPreviewRecord;
  let plainMediaPayloadWireRaw = null;

  for (const code of codes) {
    const first = decodeArrayRecord(code, 0);
    assert.equal(first.typeId, TYPE.CONTENT_EXTENSION, 'first Record must be Content Extension');
    assertKnownKeys(first.record, KNOWN_KEYS.CONTENT_EXTENSION, 'Content Extension');

    // SPEC.md §5.1: Content Extension MUST be byte-identical on every code
    // in a group — verify by comparing raw wire bytes directly.
    if (extensionRawForComparison) {
      assert.ok(first.raw.equals(extensionRawForComparison), 'Content Extension must be byte-identical on every code in the group');
    }
    extensionRawForComparison = first.raw;
    extension = first.record;

    assert.ok(first.next < code.length, 'a Content code must carry a second Record after Content Extension');
    const second = decodeArrayRecord(code, first.next);

    if (second.typeId === TYPE.MEDIA_PREVIEW) {
      // Single-code case: Media Payload is Media Preview's own (sole) subrecord.
      assertKnownKeys(second.record, KNOWN_KEYS.MEDIA_PREVIEW, 'Media Preview');
      assert.equal(second.subrecords.length, 1, 'single-code Media Preview must nest exactly one subrecord');
      mediaPreviewRecord = second.record;
      plainMediaPayloadWireRaw = second.subrecords[0].raw;
    } else if (second.typeId === TYPE.SPLIT) {
      // Multi-code case: Media Preview is Split's own subrecord instead.
      fragmentRecords.push(second.record);
      const mp = second.subrecords.find((s) => s.typeId === TYPE.MEDIA_PREVIEW);
      assert.ok(mp, 'multi-code Split fragment must carry Media Preview as its own subrecord');
      assertKnownKeys(mp.record, KNOWN_KEYS.MEDIA_PREVIEW, 'Media Preview');
      mediaPreviewRecord = mp.record;
    } else {
      throw new Error(`decodeContentPayload: unexpected second Record type ${second.typeId}`);
    }
  }

  let mediaPayloadWireBytes;
  if (fragmentRecords.length > 0) {
    const groupId = fragmentRecords[0][0];
    mediaPayloadWireBytes = reassembleSplit(fragmentRecords, groupId);
  } else {
    mediaPayloadWireBytes = plainMediaPayloadWireRaw;
  }

  const unwrapped = resolveNonSplitWrapperStack(mediaPayloadWireBytes);
  assert.equal(unwrapped.typeId, TYPE.MEDIA_PAYLOAD, 'reassembled bytes must decode as Media Payload');
  assertKnownKeys(unwrapped.record, KNOWN_KEYS.MEDIA_PAYLOAD, 'Media Payload');

  return {
    extension, mediaPreview: mediaPreviewRecord, mediaPayload: unwrapped.record,
    mediaPayloadContent: unwrapped.payload, // content moved to the payload slot (SPEC.md v11)
  };
}

// ── Test vectors ──

function testSingleCodeContent() {
  const codes = buildContentPayload({ hint: 'under the bridge', mimeType: 'text/html', content: Buffer.from('<p>hello</p>') });
  assert.equal(codes.length, 1, 'small content must fit one code');
  const { extension, mediaPreview, mediaPayloadContent } = decodeContentPayload(codes);
  assert.equal(extension[3], 'under the bridge');
  assert.equal(mediaPreview[0], 'text/html');
  assert.ok(mediaPayloadContent.equals(Buffer.from('<p>hello</p>')));
  return { name: 'single-code-content', codes: codes.map((c) => c.toString('hex')) };
}

function testSingleCodeSignedContent() {
  const codes = buildContentPayload({ hint: 'signed note', mimeType: 'text/plain', content: Buffer.from('hello, signed world'), sign: true });
  assert.equal(codes.length, 1);
  const { extension, mediaPayloadContent } = decodeContentPayload(codes);
  assert.equal(extension[45], 1, 'signature_algorithm must be present');
  assert.equal(extension[47].length, 8, 'signer_id must be 8 bytes');

  // Content Signature travels as Media Payload's own subrecord (SPEC.md v9
  // §3.1a) — re-locate it from the raw wire bytes directly, since
  // decodeContentPayload's returned `mediaPayload` is just the field map
  // (subrecords aren't map values).
  const codeFirst = decodeArrayRecord(codes[0], 0);
  const mediaPreviewRec = decodeArrayRecord(codes[0], codeFirst.next);
  const contentSignatureRec = mediaPreviewRec.subrecords[0].subrecords.find((s) => s.typeId === TYPE.CONTENT_SIGNATURE);
  assert.ok(contentSignatureRec, 'Content Signature subrecord must be present on a signed payload');
  assertKnownKeys(contentSignatureRec.record, KNOWN_KEYS.CONTENT_SIGNATURE, 'Content Signature');
  assert.equal(contentSignatureRec.record[3].length, MOCK_SIGNATURE_LEN, 'signature field must be the fixed ML-DSA-44 length');
  assert.equal(contentSignatureRec.record[5].length, MOCK_PUBKEY_LEN, 'signer_pubkey field must be the fixed ML-DSA-44 length');
  assert.ok(mediaPayloadContent.equals(Buffer.from('hello, signed world')), 'content must survive alongside the signature fields, unmodified');
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
  const { extension, mediaPayloadContent } = decodeContentPayload(codes);
  assert.equal(extension[3], 'a long essay');
  assert.ok(mediaPayloadContent.equals(bigContent), 'reassembled+decompressed content must match the original exactly');

  // drop one data fragment (not the parity one) and confirm recovery
  const droppedIndex = 1;
  const codesWithOneDropped = codes.filter((_, i) => i !== droppedIndex);
  assert.ok(codesWithOneDropped.length < codes.length);
  const recovered = decodeContentPayload(codesWithOneDropped);
  assert.ok(recovered.mediaPayloadContent.equals(bigContent), 'XOR parity must recover the missing fragment exactly');

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
  let previewRawForComparison;
  const fragmentRecords = [];
  let plainBodyRaw = null;

  for (const code of codes) {
    const first = decodeArrayRecord(code, 0);
    assert.equal(first.typeId, TYPE.PAPER_PREVIEW, 'first Record must be Paper-Preview');
    assertKnownKeys(first.record, KNOWN_KEYS.PAPER_PREVIEW, 'Paper-Preview');

    if (previewRawForComparison) {
      assert.ok(first.raw.equals(previewRawForComparison), 'Preview must be byte-identical on every code in the group');
    }
    previewRawForComparison = first.raw;
    preview = first.record;

    const second = decodeArrayRecord(code, first.next);
    if (second.typeId === TYPE.SPLIT) fragmentRecords.push(second.record);
    else plainBodyRaw = second.raw;
  }

  let bodyWireBytes;
  if (fragmentRecords.length > 0) {
    const groupId = fragmentRecords[0][0];
    bodyWireBytes = reassembleSplit(fragmentRecords, groupId);
  } else {
    bodyWireBytes = plainBodyRaw;
  }
  const unwrapped = resolveNonSplitWrapperStack(bodyWireBytes);
  assert.equal(unwrapped.typeId, TYPE.PAPER_BODY, 'reassembled bytes must decode as Paper-Body');
  const body = unwrapped.record;
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
  const previews = codes.map((c) => decodeArrayRecord(c, 0).record);
  const firstPreviewBytes = encodeArrayRecord(TYPE.PAPER_PREVIEW, { 1: previews[0][1], 3: previews[0][3], 5: previews[0][5], 7: previews[0][7] });
  for (const p of previews.slice(1)) {
    const pBytes = encodeArrayRecord(TYPE.PAPER_PREVIEW, { 1: p[1], 3: p[3], 5: p[5], 7: p[7] });
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

  // contentHash MUST be random, not content-addressed, whenever a hidden
  // override map might be present (§4.4/§9) — using the cover story's own
  // hash here would leak whether the "real" content matches a known
  // contentHash, defeating the whole point.
  const cacheId = randomBytes(8);
  const coverHint = 'just a locked note';
  const extension = buildContentExtension({ hint: coverHint, encryption: 1 });
  // The override blob occupies Media Payload's payload slot (SPEC.md v11) —
  // the same slot a Content payload's cache normally occupies (SPEC §9).
  const mediaPreview = buildMediaPreview({ mediaType: 'text/plain', contentHash: cacheId }, [
    buildMediaPayload({ mediaType: 'text/plain', content: blob }),
  ]);
  const codes = [Buffer.concat([extension, mediaPreview])];

  // Cover story is what's visible without the key.
  const first = decodeArrayRecord(codes[0], 0);
  assert.equal(first.record[3], coverHint, 'cover hint must be visible with no key present');

  // Trial decryption (§9 "Discovery, not declaration") — try the candidate
  // blob against a known key_material; a wrong key must fail the GCM
  // authentication tag, not silently produce garbage plaintext.
  const second = decodeArrayRecord(codes[0], first.next);
  const candidateBlob = second.subrecords[0].payload; // content moved to the payload slot (SPEC.md v11)
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
  const overrideDecoded = decodeItem(decompressed, 0).value;

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
  const first = decodeArrayRecord(code, 0);
  const rawExtensionBytes = first.raw;
  const second = decodeArrayRecord(code, first.next);
  assert.equal(second.typeId, TYPE.SPLIT);
  const fragmentRecord = second.record;
  const mediaPreviewSubRaw = second.subrecords.find((s) => s.typeId === TYPE.MEDIA_PREVIEW).raw;

  // Flip one bit in the fragment's data, leaving its declared group_id (key
  // 0) field untouched — the tamper must be caught by recomputing the hash
  // from the actual reassembled bytes, not by comparing declared fields.
  // Media Preview's subrecord (carried on every Split fragment, §3.1a) must
  // be preserved so the tampered fragment still decodes.
  const tamperedFragmentBytes = Buffer.from(fragmentRecord[6]);
  tamperedFragmentBytes[0] ^= 0xff;
  const tamperedFragmentRecordBytes = encodeArrayRecord(TYPE.SPLIT, {
    0: fragmentRecord[0], 2: fragmentRecord[2], 4: fragmentRecord[4],
    6: tamperedFragmentBytes, 7: fragmentRecord[7], 9: fragmentRecord[9],
  }, [mediaPreviewSubRaw]);
  const tamperedCode = Buffer.concat([rawExtensionBytes, tamperedFragmentRecordBytes]);
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

  const first = decodeArrayRecord(codes[0], 0);
  const rawPreviewBytes = first.raw;
  const second = decodeArrayRecord(codes[0], first.next);
  const bodyRecord = second.record;

  const tamperedFiles = encodeArray([encodeRecord({ 1: 'index-RENAMED', 2: 'text/html', 3: randomBytes(8) })]);
  const tamperedBodyBytes = encodeArrayRecord(TYPE.PAPER_BODY, { 1: tamperedFiles, 3: bodyRecord[3] });
  const tamperedCode = Buffer.concat([rawPreviewBytes, tamperedBodyBytes]);

  assert.throws(
    () => decodePaperPayload([tamperedCode]),
    /root_hash/,
    'a Body tampered after root_hash was computed must be caught on decode, not silently accepted'
  );

  return { name: 'tampered-paper-root-hash-detected' };
}

function testKeyOnlyCode() {
  // SPEC.md §9 "Decryption keys": a Content Extension carrying key_material
  // but no Media Preview/Payload at all — a code can be just a key, with
  // nothing of its own to identify (no contentHash) or display.
  const keyMaterial = randomBytes(32);
  const code = buildContentExtension({ keyMaterial, retainKey: false });

  const decoded = decodeArrayRecord(code, 0);
  assert.equal(decoded.typeId, TYPE.CONTENT_EXTENSION);
  assert.equal(decoded.next, code.length, 'a key-only code carries Content Extension alone, nothing after it');
  const record = decoded.record;
  assert.ok(record[33].equals(keyMaterial));
  assert.equal(record[35], false, 'retain_key must round-trip as a real boolean, not a truthy placeholder');
  assert.equal(record[1], undefined, 'Content Extension has no field 1 at all in v9 (contentHash moved to Media Preview, §3.1)');

  return { name: 'key-only-code', codes: [code.toString('hex')] };
}

function testEvenOddCriticality() {
  // SPEC.md §2.2 / QDEF-SPEC.md §3.2. Nothing in this file's own field set
  // exercises this — every TagDrop key defined today is odd — so this
  // synthesizes a "future" field of each parity to prove the mechanism
  // itself, not just TagDrop's current (all-odd) usage of it.
  const withUnknownOdd = encodeArrayRecord(TYPE.CONTENT_EXTENSION, { 3: 'hi', 101: 'a future optional field' });
  const decodedOdd = decodeArrayRecord(withUnknownOdd, 0).record;
  assertKnownKeys(decodedOdd, KNOWN_KEYS.CONTENT_EXTENSION, 'Content Extension'); // must not throw
  assert.equal(decodedOdd[3], 'hi', 'known fields must stay readable alongside an unrecognized odd key');

  const withUnknownEven = encodeArrayRecord(TYPE.CONTENT_EXTENSION, { 3: 'hi', 100: 'a future critical field' });
  const decodedEven = decodeArrayRecord(withUnknownEven, 0).record;
  assert.throws(
    () => assertKnownKeys(decodedEven, KNOWN_KEYS.CONTENT_EXTENSION, 'Content Extension'),
    /CRITICAL \(even\) key 100/,
    'an unrecognized even key must abort processing this Record'
  );

  return { name: 'even-odd-criticality' };
}

function main() {
  const results = [];
  console.log('QDEF-redesign round-trip prototype (SPEC.md v9)\n');

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
