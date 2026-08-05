/**
 * QDEF wire-shape validation suite: builds TagDrop's version-16 wire shape
 * (SPEC.md §2-§5) — array-wrapped Records with subrecords, Content Extension/
 * Media Preview/Media Payload/Content Signature, Paper-Preview/Paper-Body,
 * QDEF Split/Compress Wrapper Records (v15: no positional payload slot any
 * more — each Wrapper/standard Type's one singular value lives at map key
 * `0` instead; v16: TagDrop's own four Type IDs wire-encode NEGATED and carry
 * no namespace item of their own — a Record's own scope is decided purely by
 * its typeId's sign, and every root is now a namespace-declaring Bundle,
 * including the single-Record/key-only case), the root_hash/signed-message
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
// these specific values ever actually exercise. These are the DECLARED
// (positive) magnitudes, matching registry.rec (SPEC.md §2.1) — sequential
// (parity no longer means anything, see §2.1a). TagDrop's own four Type IDs
// (CONTENT_EXTENSION/CONTENT_SIGNATURE/PAPER_PREVIEW/PAPER_BODY) always
// wire-encode NEGATED as of v16 (`-TYPE.CONTENT_EXTENSION` etc. — negation,
// not magnitude choice, is what signals "scoped to the ambient namespace"
// now); QDEF's own standard Types (SPLIT/COMPRESS/MEDIA_PREVIEW/
// MEDIA_PAYLOAD) always wire-encode non-negative. Several magnitudes
// deliberately collide numerically (Content Extension/Split both `1`,
// Paper-Preview/Media Payload both `3`, Paper-Body/Compress Wrapper both
// `4`) — disambiguated by sign alone (TagDrop's four always negative on the
// wire), with namespace resolution then confirming which application's
// registry a negative typeId belongs to.
const TYPE = {
  SPLIT: 1,
  COMPRESS: 4,
  CONTENT_EXTENSION: 1,
  CONTENT_SIGNATURE: 2,
  PAPER_PREVIEW: 3,
  PAPER_BODY: 4,
  MEDIA_PREVIEW: 7,
  MEDIA_PAYLOAD: 3,
};

// TagDrop's namespace (SPEC.md §2.1a): SHA-256("io.github.mofosyne.tagdrop")[0:4].
// Declared in full as the root array's own leading element — the ONLY place
// TagDrop's own encoders ever emit a namespace item at all, as of v16. Every
// TagDrop-scoped Record nested underneath (Content Extension, Content
// Signature, Paper-Preview, Paper-Body) carries no namespace item of its
// own; each resolves to this ambient value purely via its own negated
// typeId (§2.1a's sign rule) — `h''` cascade markers are gone from the
// grammar entirely.
const TAGDROP_NAMESPACE = Buffer.from([0x89, 0xd4, 0x14, 0xe0]);

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

// Encodes a signed CBOR integer at any value position (not just a map key) —
// non-negative as major type 0 (uint), negative as major type 1 (RFC 8949
// §3.1: argument `-(n+1)`). Used both for an ordinary field-map key or a
// QDEF Common Field Key (§3.6, always odd/optional, always negative), and —
// as of SPEC.md v16 — for a Record's own typeId item, negative for every one
// of TagDrop's four namespace-scoped Types (§2.1a). `k`/`n` may be a Number
// or BigInt.
function encodeSignedInt(n) {
  const neg = typeof n === 'bigint' ? n < 0n : n < 0;
  if (!neg) return encodeUInt(n, 0);
  return encodeUInt(typeof n === 'bigint' ? (-n - 1n) : (-n - 1), 1);
}
const encodeKey = encodeSignedInt; // alias: map keys use the identical encoding.

// value: number(int) | {f64: number} | boolean | string | Buffer | Array<encoded-item-buffers>
function encodeValue(v) {
  if (typeof v === 'bigint') return encodeSignedInt(v);
  if (typeof v === 'number') return Number.isInteger(v) ? encodeSignedInt(v) : encodeFloat64(v);
  if (typeof v === 'boolean') return encodeBool(v);
  if (typeof v === 'string') return encodeText(v);
  if (Buffer.isBuffer(v)) return encodeBytes(v);
  if (v && v.f64 !== undefined) return encodeFloat64(v.f64);
  throw new Error(`encodeValue: unsupported type ${typeof v}`);
}

// RFC 8949 §4.2.1 core deterministic-encoding map-key order (QDEF-SPEC.md
// §3.4): shorter encoded key first; same-length keys compare bytewise. NOT
// the same as ascending integer value once negative keys are involved — a
// negative key (major type 1) always encodes to a *larger* raw byte than a
// same-magnitude non-negative key (major type 0), so e.g. `0` sorts before
// `-1` here despite `-1 < 0`.
function compareKeyBytes(a, b) {
  if (a.length !== b.length) return a.length - b.length;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return a[i] - b[i];
  return 0;
}

// fields: plain object, integer-string keys, undefined values are omitted.
function encodeRecord(fields) {
  const keys = Object.keys(fields)
    .filter((k) => fields[k] !== undefined)
    .map(Number)
    .sort((a, b) => compareKeyBytes(encodeKey(a), encodeKey(b)));
  const parts = [encodeUInt(keys.length, 5)];
  for (const k of keys) {
    parts.push(encodeKey(k));
    parts.push(encodeValue(fields[String(k)]));
  }
  return Buffer.concat(parts);
}

// A QDEF Record (QDEF-SPEC.md §3.1/§3.5, SPEC.md v16): a self-delimited CBOR
// array, [namespace?, typeId, map?, subrecord*]. `namespace`, if given, is a
// Buffer spliced in as the array's own leading element — declares the
// ambient value for whatever's nested underneath THIS Record. As of v16 it
// has NO effect on this Record's own typeId, which is instead decided
// purely by that typeId's own sign (negative = scoped, adopting the ambient
// namespace from an ancestor; non-negative = always global), so `h''` no
// longer exists in the grammar. None of TagDrop's own encoders pass
// `namespace` here any more — every TagDrop-scoped Record now carries no
// namespace item of its own (see buildContentExtension et al., which pass
// their typeId negated instead); it remains a general capability of this
// primitive since QDEF's own grammar allows any Record to carry one (e.g.
// the root Bundle, built directly by encodeRootBundle rather than through
// this function). The map is omitted entirely only when `fields` itself has
// no declared keys at all (a static, per-call-site choice) — NOT whenever
// every declared field's value happens to be undefined, since Types that
// always declare fields (Content Extension, Paper-Preview/Body) need a
// stable map for stripKeys/signature-hash formulas to work against even
// when every optional field is unset. QDEF's grammar has no separate
// positional `payload` array slot at all (v15) — a Record's "one genuinely
// singular value" (Compress Wrapper's compressed bytes, Media Payload's
// `content`, Split Wrapper's fragment data) lives at reserved map key `0`
// instead, an ordinary entry in `fields` like any other. `subrecords` are
// already-encoded array-Record byte sequences (each itself built by a
// nested encodeArrayRecord call), spliced in as their own array items.
// `typeId` may be negative (major type 1) — encodeSignedInt handles the
// sign correctly, same as encodeKey already did for negative map keys.
function encodeArrayRecord(typeId, fields, subrecords = [], namespace = undefined) {
  const items = [];
  if (namespace !== undefined) items.push(encodeBytes(namespace));
  items.push(encodeSignedInt(typeId));
  if (Object.keys(fields).length > 0) items.push(encodeRecord(fields));
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
 * Decodes one QDEF Record (a self-delimited CBOR array, `[namespace?,
 * typeId, map?, subrecord*]`, QDEF-SPEC.md §3.1/§3.5, SPEC.md v16) from the
 * head of `buf` at `offset`.
 *
 * `ambientNamespace` is whatever namespace this Record's own PARENT
 * received from ITS parent, in turn (SPEC.md §2.1a's cascading rule) — NOT
 * this Record's own resolved value. Call with no third argument (or
 * `null`) at the true root, where nothing is ambient yet.
 *
 * As of version 16, a namespace bstr present on THIS Record affects only
 * what's ambient for ITS OWN subrecords — never this Record's own scope,
 * which is instead decided purely by this Record's own typeId's sign
 * (negative = scoped, adopting `ambientNamespace`; non-negative = always
 * global, `null`, regardless of whether a namespace bstr sits on this same
 * Record). `h''` no longer exists in the grammar — any namespace bstr
 * present is used as-is, no empty/non-empty distinction needed.
 *
 * Returns `{ typeId, namespace, record, raw, subrecords, next }` —
 * `namespace` is this Record's own RESOLVED SCOPE per the sign rule above:
 * `ambientNamespace` if `typeId < 0`, else `null` (unconditionally global,
 * SPEC.md §2.1a — no ambient value rescues a non-negative typeId). `record`
 * is `{}` when the map was omitted (its own key `0`, if present, is
 * whatever used to be the separate payload-slot value — e.g. Media
 * Payload's `content`, Compress Wrapper's compressed bytes, Split
 * Wrapper's fragment data), `raw` is this Record's own exact byte range
 * (namespace item included, if present — what signature/group-id hashes
 * are computed over), `subrecords` an array of the same shape (recursively
 * — each with `raw` relative to the ORIGINAL `buf`, not sliced first),
 * `next` the offset immediately after this Record.
 */
function decodeArrayRecord(buf, offset, ambientNamespace = null) {
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
  let remaining = count;

  // A namespace bstr present on THIS Record only ever affects what's ambient
  // for ITS OWN subrecords (SPEC.md §2.1a, v16) — never this Record's own
  // scope, computed below from typeId's sign alone.
  let childAmbientNamespace = ambientNamespace;
  if (remaining > 0 && (buf[cur] >> 5) === 2) {
    const nsItem = decodeItem(buf, cur);
    childAmbientNamespace = nsItem.value;
    cur = nsItem.next;
    remaining--;
  }
  if (remaining < 1) throw new Error('decodeArrayRecord: a Record needs a typeId after any namespace item');

  const typeIdItem = decodeItem(buf, cur);
  cur = typeIdItem.next;
  remaining--;

  let record = {};
  if (remaining > 0 && (buf[cur] >> 5) === 5) {
    const mapItem = decodeItem(buf, cur);
    record = mapItem.value;
    cur = mapItem.next;
    remaining--;
  }

  const typeId = typeof typeIdItem.value === 'bigint'
    ? (typeIdItem.value <= BigInt(Number.MAX_SAFE_INTEGER) && typeIdItem.value >= BigInt(Number.MIN_SAFE_INTEGER)
        ? Number(typeIdItem.value) : typeIdItem.value)
    : typeIdItem.value;

  // This Record's own resolved scope (v16, SPEC.md §2.1a): negative typeId
  // means "scoped, adopting the ambient namespace"; non-negative always
  // means global, regardless of any namespace bstr on this same Record.
  const namespace = (typeof typeId === 'bigint' ? typeId < 0n : typeId < 0) ? ambientNamespace : null;

  const subrecords = [];
  for (let i = 0; i < remaining; i++) {
    // Subrecords inherit CHILD ambient namespace (this Record's own namespace
    // bstr if it carried one, else whatever was already ambient) — never
    // `namespace` (this Record's own resolved scope) — per SPEC.md §2.1a's
    // cascading rule.
    const sub = decodeArrayRecord(buf, cur, childAmbientNamespace);
    subrecords.push(sub);
    cur = sub.next;
  }

  return { typeId, namespace, record, raw: buf.subarray(offset, cur), subrecords, next: cur };
}

// Encodes 1+ already-encoded top-level Record byte-arrays as the QDEF
// self-delimited root (QDEF-SPEC.md §2/§3.1/§3.5, SPEC.md v16): always a
// namespace-declaring Bundle now — a Record can no longer simultaneously
// introduce a namespace and be scoped by it (§2.1a), so even a single
// top-level Record (the key-only code case, §9) is wrapped, exactly like
// the common two-Record case. One definite-length CBOR array: TagDrop's
// namespace declared in full (TAGDROP_NAMESPACE) as the array's own leading
// element, followed by each already-encoded Record spliced in raw as a
// subrecord — every TagDrop-scoped child then resolves to it via its own
// negated typeId (§2.1a's sign rule) rather than repeating the full value
// or spending a byte on a cascade marker.
function encodeRootBundle(records) {
  return encodeArray([encodeBytes(TAGDROP_NAMESPACE), ...records]);
}

// Decodes the QDEF self-delimited root (QDEF-SPEC.md §2/§3.1/§3.5, SPEC.md
// v16): exactly one definite-length CBOR array, always a namespace-declaring
// Bundle now — its own leading element is a namespace byte string (SPEC.md
// §2.1a), and every remaining item is a nested Record, independently
// decoded with that declared value as their shared ambient namespace.
// There is no more "single Record IS the root, no Bundle indirection" case
// (v13-15) — a Record can no longer simultaneously introduce a namespace
// and be scoped by it (§2.1a), so even a key-only code's lone Content
// Extension is wrapped, uniformly. Returns null if the root isn't a
// well-formed, definite-length CBOR array whose first item is a byte
// string, or if it carries no Records at all.
function decodeRootBundle(buf) {
  const head = buf[0];
  if ((head >> 5) !== 4) return null;
  const info = head & 0x1f;
  let argOffset = 1, count;
  if (info < 24) { count = info; }
  else if (info === 24) { count = buf[argOffset]; argOffset += 1; }
  else if (info === 25) { count = buf.readUInt16BE(argOffset); argOffset += 2; }
  else return null;
  if (count < 2) return null; // namespace + at least one Record

  if ((buf[argOffset] >> 5) !== 2) return null;
  const nsItem = decodeItem(buf, argOffset);
  const ambientNamespace = nsItem.value;
  const out = [];
  let cur = nsItem.next;
  for (let i = 1; i < count; i++) {
    const rec = decodeArrayRecord(buf, cur, ambientNamespace);
    out.push(rec);
    cur = rec.next;
  }
  return out;
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

// ── Compress Wrapper (QDEF-SPEC.md §4.1 Type 8) — DEFLATEs `bytes` into map
// key 0 (SPEC.md v15 — was the array's positional payload slot, `[8,
// deflated_bytes]`, through v14; QDEF dropped that slot entirely, so the one
// value now lives at `{0: deflated_bytes}` instead): `[8, {0: deflated_bytes}]`. ──
function compressWrap(bytes) {
  return encodeArrayRecord(TYPE.COMPRESS, { 0: deflateRawSync(bytes) });
}

// ── Split Wrapper (QDEF-SPEC.md §4.1 Type 2) — fragment / reassemble / XOR parity ──
// `subrecords` (SPEC.md §3.1a) is attached to every fragment Record unwrapped
// and repeated — used to carry Content's Media Preview alongside a
// Split-wrapped Media Payload; Paper has none, so it's `[]` by default.
// Fragment data lives at map key `0` (SPEC.md v15 — was the array's
// positional payload slot through v14); `group_id`/`index`/`count` shift up
// two keys each to make room (`0`/`2`/`4` → `2`/`4`/`6`); `total_bytes`/
// `parity_scheme` are unaffected (`7`/`9` both versions).
function splitFragments(bytes, groupId, fragmentCount, withParity, subrecords = []) {
  const totalBytes = bytes.length;
  const chunkLen = Math.ceil(totalBytes / fragmentCount);
  const fragments = [];
  for (let i = 0; i < fragmentCount; i++) {
    const slice = bytes.subarray(i * chunkLen, Math.min((i + 1) * chunkLen, totalBytes));
    fragments.push(
      encodeArrayRecord(TYPE.SPLIT, {
        0: slice,
        2: groupId,
        4: i,
        6: fragmentCount,
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
        0: parity,
        2: groupId,
        4: fragmentCount,
        6: fragmentCount,
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
    assert.ok(rec[2].equals(expectedGroupId), 'group_id mismatch across fragments');
    count = rec[6];
    totalBytes = rec[7];
    byIndex.set(rec[4], rec[0]);
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
// `ambientNamespace` is threaded through so a TagDrop-scoped Record found at
// this position (e.g. Paper-Body) resolves correctly via its own negated
// typeId when re-decoded from reassembled bytes independently of the
// original scan tree — the caller passes TAGDROP_NAMESPACE once the root's
// own namespace has already been validated (SPEC.md §2.1a).
//
// Compress Wrapper's DECLARED magnitude (4) is the same integer as
// Paper-Body's, but as of SPEC.md v16 (§2.1a) Paper-Body's actual WIRE value
// is negated (-4) — the `decoded.typeId === TYPE.COMPRESS` check below
// (comparing against the positive, global constant) can therefore never
// match genuine Paper-Body bytes at all, a correctness upgrade over v14-15's
// design (where both wire-encoded as bare `4`, disambiguated only by
// namespace resolution). The `decoded.namespace === null` check is now
// structurally redundant for the same reason (TYPE.COMPRESS is always
// non-negative, so decodeArrayRecord always resolves its namespace to
// `null` regardless of ambient) — kept for defensive symmetry with real
// callers' own namespace checks on the Record this eventually returns.
function resolveNonSplitWrapperStack(bytes, ambientNamespace = null) {
  let cur = bytes;
  for (;;) {
    const decoded = decodeArrayRecord(cur, 0, ambientNamespace);
    if (decoded.typeId === TYPE.COMPRESS && decoded.namespace === null) {
      // Compress Wrapper's one value lives at map key 0 as of SPEC.md v15
      // (was the array's positional payload slot through v14).
      cur = inflateRawSync(decoded.record[0]);
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

// ── Content Extension / Media Preview / Media Payload / Content Signature (SPEC.md v16 §3.1/§3.1a/§2.1a) ──

// Content Extension (Type 1) — TagDrop-specific fields only; file
// identification (contentHash/mediaType/filename/label) lives in Media
// Preview, large signing fields in Content Signature. Namespace (SPEC.md
// §2.1a, version 16): carries no namespace item of its own, ever — its
// scope is resolved purely by its own negated typeId
// (`-TYPE.CONTENT_EXTENSION`), adopting whatever namespace is ambient from
// its Bundle-root parent. This applies uniformly now, including the
// key-only code (§9) case — a lone Content Extension is no longer written
// as the root directly; it's always a Bundle's sole subrecord (see
// encodeRootBundle).
function buildContentExtension(f) {
  return encodeArrayRecord(-TYPE.CONTENT_EXTENSION, {
    3: f.hint,
    33: f.keyMaterial,
    35: f.retainKey,
    37: f.encryption,
    45: f.signatureAlgorithm,
    47: f.signerId,
    49: f.signerLabel,
  }, []);
}

// SPEC.md §2.2 / QDEF-SPEC.md §3.2: even/odd key criticality. TagDrop's own
// fields are all odd today (§2.2) — nothing here is exercised by ordinary
// encode/decode of a current-version payload — so this needs its own
// explicit test rather than falling out of the existing round-trips.
const KNOWN_KEYS = {
  CONTENT_EXTENSION: new Set([3, 33, 35, 37, 45, 47, 49]),
  MEDIA_PREVIEW: new Set([2, 3, 5]), // mediaType/contentHash/filename shifted up two keys (v15)
  MEDIA_PAYLOAD: new Set([0, 1]), // content at key 0, mediaType at key 1 (v15)
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

// Media Preview (QDEF standard Type 7, SPEC.md v15 §3.1a) — file
// identification. Global Type — no namespace item, ever. Every field shifts
// up two keys as of v15 (`mediaType`/`contentHash`/`filename`: `0`/`1`/`3`
// → `2`/`3`/`5`) since key `0` is now reserved QDEF-wide for a Record's
// payload-equivalent value (§3.6) — Media Preview never has one of its own
// (content travels as Media Payload's own subrecord), so key `0` is simply
// unused here. Before v15, `contentHash`/`filename` were back to
// Type-specific keys 1/3 as of v14 (were QDEF Common Field Keys -11/-15 in
// versions 11-13; QDEF's own registry shrank back down to just ID/UUID).
// `contentHash` is multihash-style: a 1-byte function code (0x12 =
// sha2-256) prepended to the 8-byte digest. `subrecords` carries Media
// Payload when it fits nested here (single-code case) or is omitted
// (multi-code case, where Media Preview itself becomes Split's subrecord
// instead — SPEC.md §5.1).
function buildMediaPreview(f, subrecords = []) {
  return encodeArrayRecord(TYPE.MEDIA_PREVIEW, {
    2: f.mediaType,
    3: f.contentHash ? Buffer.concat([Buffer.from([0x12]), f.contentHash]) : undefined,
    5: f.filename,
  }, subrecords);
}

// Media Payload (QDEF standard Type 3, SPEC.md v15 §3.1a) — `content` moved
// from the array's positional payload slot into map key `0` as of v15 (QDEF
// dropped that slot entirely); `mediaType`, which used to sit at key `0`
// itself (v11-v14), moves to key `1` to make room. Global Type — no
// namespace item, ever, but transparently passes whatever ambient
// namespace it received through to Content Signature nested inside it
// (SPEC.md §2.1a's cascading rule). `subrecords` carries Content Signature
// (Type 2) when the payload is signed, so `signature`/`signer_pubkey`
// travel wherever Media Payload's own bytes travel (nested once, not
// repeated per code — SPEC.md §3.1).
function buildMediaPayload(f, subrecords = []) {
  return encodeArrayRecord(TYPE.MEDIA_PAYLOAD, { 0: f.content, 1: f.mediaType }, subrecords);
}

// Content Signature (Type 2, TagDrop-scoped, SPEC.md §3.1a) —
// signature/signer_pubkey for a signed Content payload, nested as Media
// Payload's own subrecord. Absent entirely (no Record at all) when
// unsigned. Carries no namespace item of its own (SPEC.md §2.1a, version
// 16) — resolves via its own negated typeId to whatever namespace is
// ambient, cascaded down through Media Payload/Media Preview from the
// Bundle root three levels up.
function buildContentSignature(f) {
  return encodeArrayRecord(-TYPE.CONTENT_SIGNATURE, { 3: f.signature, 5: f.signerPubkey }, []);
}

// ── Paper-Preview / Paper-Body (SPEC.md §3.3-§3.4) ── Both carry no
// namespace item of their own (SPEC.md §2.1a, version 16) — each resolves
// via its own negated typeId to whatever the Bundle root declared as
// ambient.
function buildPaperPreview(f) {
  return encodeArrayRecord(-TYPE.PAPER_PREVIEW, {
    1: f.rootHash,
    3: f.hint,
    5: f.set,
    7: f.slug,
    31: f.signatureAlgorithm,
    33: f.signerId,
    35: f.signerLabel,
  }, []);
}

function buildPaperBody(f) {
  return encodeArrayRecord(-TYPE.PAPER_BODY, {
    1: f.files,
    3: f.related,
    5: f.signature,
    7: f.signerPubkey,
  }, []);
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
      return [encodeRootBundle([signedExtension, wireMediaPreview])];
    }
    // Single code: Media Payload (or its Compress Wrapper) nests as Media
    // Preview's own subrecord (§3.1a) — not a separate sibling Record.
    const wireMediaPreview = buildMediaPreview({ mediaType: mimeType, contentHash: cacheId }, [mediaPayloadForWire]);
    return [encodeRootBundle([extension, wireMediaPreview])];
  }

  // multi-code: Split-wrap mediaPayloadForWire; Media Preview becomes Split's
  // own repeated subrecord instead (§3.1a); Content Extension stays a
  // separate top-level Record, repeated per code exactly as in the
  // single-code case above.
  assert.ok(!sign, 'multi-code signing not exercised in this prototype (single-code path already covers the placeholder/strip discipline; multi-code adds only Split framing on top, orthogonal to signing)');
  const fragmentCount = Math.ceil(mediaPayloadForWire.length / maxBodyBytes);
  const groupId = sha256(mediaPayloadForWire).subarray(0, 8);
  const fragments = splitFragments(mediaPayloadForWire, groupId, fragmentCount, true, [mediaPreviewBare]);
  return fragments.map((frag) => encodeRootBundle([extension, frag]));
}

/**
 * Asserts that every Record in a decoded root Bundle has the correct namespace
 * for its role — TagDrop-scoped types (Content Extension/Signature, Paper
 * Preview/Body) MUST resolve to TAGDROP_NAMESPACE via their own negated typeId
 * (SPEC.md §2.1a, version 16 — sign alone decides scope now, not a `h''`
 * cascade marker); QDEF global types (Split, Media Payload, Compress, Media
 * Preview) MUST have `null` namespace. This mirrors the Android app's
 * recordScanResult check (SPEC.md §2.1a) and catches the class of bug where
 * the encoder's namespace cascade was silently broken (e.g. an extra stray
 * argument shifting a build*() function's parameters out of position, or a
 * declared-magnitude typeId shipped instead of its negated wire form).
 */
function assertNamespaces(records) {
  for (let i = 0; i < records.length; i++) {
    const r = records[i];
    // Walk subrecords too (Split wraps Media Preview, Media Preview wraps
    // Media Payload, Media Payload wraps Content Signature).
    for (const sr of (r.subrecords || [])) {
      assertNamespace(sr, `records[${i}].subrecords`);
    }
    assertNamespace(r, `records[${i}]`);
  }
}
function assertNamespace(r, path) {
  // TagDrop-scoped Type IDs overlap with QDEF global ones in DECLARED
  // magnitude (1, 3, 4 each appear in both spaces) but never in actual wire
  // value (TagDrop's four always negative, QDEF's globals always
  // non-negative, SPEC.md v16) — namespace resolution confirms which
  // application's registry a negative typeId belongs to.
  if (r.namespace && r.namespace.equals(TAGDROP_NAMESPACE)) {
    // TagDrop-scoped: ok — the Record's own negated typeId resolved to
    // TagDrop's namespace.
    return;
  }
  if (r.namespace === null) {
    // QDEF global/standard Type (Split, Media Payload, Compress, Media
    // Preview): namespace must be null — these Types' typeId is always
    // non-negative, so decodeArrayRecord never resolves a scope for them,
    // even when nested inside a TagDrop-namespaced tree.
    return;
  }
  throw new Error(
    `${path} typeId=${r.typeId} has unexpected namespace ` +
    (r.namespace ? r.namespace.toString('hex') : 'null') +
    ' — expected TAGDROP_NAMESPACE or null'
  );
}

function decodeContentPayload(codes) {
  let extension;
  let extensionRawForComparison;
  const fragmentRecords = [];
  let mediaPreviewRecord;
  let plainMediaPayloadWireRaw = null;

  for (const code of codes) {
    const records = decodeRootBundle(code);
    assert.ok(records && records.length === 2, 'a Content code must carry exactly a Content Extension and a second Record');
    const [first, second] = records;
    // As of SPEC.md v16 (§2.1a), Content Extension's wire typeId is negated
    // (-TYPE.CONTENT_EXTENSION = -1) — the same magnitude as QDEF's global
    // Split Wrapper (1), but a different CBOR value by sign alone.
    assert.equal(first.typeId, -TYPE.CONTENT_EXTENSION, 'first Record must be Content Extension');
    // SPEC.md §2.1a: the real security-relevant check this whole mechanism
    // exists for — TagDrop's small Type IDs, negated, are the same integers
    // QDEF's own global standard Types use (once you drop the sign), so a
    // Record only counts as TagDrop's Content Extension once its resolved
    // namespace actually matches.
    assert.ok(
      first.namespace && first.namespace.equals(TAGDROP_NAMESPACE),
      'Content Extension must resolve to TagDrop\'s namespace (SPEC.md §2.1a) — got: ' +
        (first.namespace ? first.namespace.toString('hex') : 'none')
    );
    assertKnownKeys(first.record, KNOWN_KEYS.CONTENT_EXTENSION, 'Content Extension');

    // SPEC.md §5.1: Content Extension MUST be byte-identical on every code
    // in a group — verify by comparing raw wire bytes directly.
    if (extensionRawForComparison) {
      assert.ok(first.raw.equals(extensionRawForComparison), 'Content Extension must be byte-identical on every code in the group');
    }
    extensionRawForComparison = first.raw;
    extension = first.record;

    if (second.typeId === TYPE.MEDIA_PREVIEW) {
      // Single-code case: Media Payload is Media Preview's own (sole) subrecord.
      assert.equal(second.namespace, null, 'Media Preview is QDEF Type 7 (global) — must have null namespace');
      assertKnownKeys(second.record, KNOWN_KEYS.MEDIA_PREVIEW, 'Media Preview');
      assert.equal(second.subrecords.length, 1, 'single-code Media Preview must nest exactly one subrecord');
      mediaPreviewRecord = second.record;
      plainMediaPayloadWireRaw = second.subrecords[0].raw;
      // The subrecord (Media Payload or Compress Wrapper) is also a global
      // Type — namespace must be null here too.
      assert.equal(second.subrecords[0].namespace, null, 'Media Preview\'s subrecord (Type 3 or 4) must have null namespace');
    } else if (second.typeId === TYPE.SPLIT) {
      // Multi-code case: Media Preview is Split's own subrecord instead.
      assert.equal(second.namespace, null, 'Split is QDEF Type 1 (global) — must have null namespace');
      fragmentRecords.push(second.record);
      const mp = second.subrecords.find((s) => s.typeId === TYPE.MEDIA_PREVIEW);
      assert.ok(mp, 'multi-code Split fragment must carry Media Preview as its own subrecord');
      assert.equal(mp.namespace, null, 'Media Preview nested in Split must have null namespace');
      assertKnownKeys(mp.record, KNOWN_KEYS.MEDIA_PREVIEW, 'Media Preview');
      mediaPreviewRecord = mp.record;
    } else {
      throw new Error(`decodeContentPayload: unexpected second Record type ${second.typeId}`);
    }
  }

  let mediaPayloadWireBytes;
  if (fragmentRecords.length > 0) {
    const groupId = fragmentRecords[0][2]; // group_id lives at key 2 as of SPEC.md v15
    mediaPayloadWireBytes = reassembleSplit(fragmentRecords, groupId);
  } else {
    mediaPayloadWireBytes = plainMediaPayloadWireRaw;
  }

  // TAGDROP_NAMESPACE, not the original scan tree's own ambient value: the
  // reassembled bytes are re-decoded fresh, outside the tree decodeRootBundle
  // walked, but by construction (only the root ever declares a real
  // namespace value; nothing in TagDrop's own wire format overrides ambient
  // mid-tree) the ambient reaching this position is always exactly what the
  // already-validated `first.namespace` check above confirmed.
  const unwrapped = resolveNonSplitWrapperStack(mediaPayloadWireBytes, TAGDROP_NAMESPACE);
  assert.equal(unwrapped.typeId, TYPE.MEDIA_PAYLOAD, 'reassembled bytes must decode as Media Payload');
  assertKnownKeys(unwrapped.record, KNOWN_KEYS.MEDIA_PAYLOAD, 'Media Payload');

  return {
    extension, mediaPreview: mediaPreviewRecord, mediaPayload: unwrapped.record,
    mediaPayloadContent: unwrapped.record[0], // content lives at map key 0 (SPEC.md v15)
  };
}

// ── Test vectors ──

function testSingleCodeContent() {
  const codes = buildContentPayload({ hint: 'under the bridge', mimeType: 'text/html', content: Buffer.from('<p>hello</p>') });
  assert.equal(codes.length, 1, 'small content must fit one code');
  const { extension, mediaPreview, mediaPayloadContent } = decodeContentPayload(codes);
  assert.equal(extension[3], 'under the bridge');
  assert.equal(mediaPreview[2], 'text/html');
  assert.ok(mediaPayloadContent.equals(Buffer.from('<p>hello</p>')));
  return { name: 'single-code-content', codes: codes.map((c) => c.toString('hex')) };
}

function testSingleCodeSignedContent() {
  const codes = buildContentPayload({ hint: 'signed note', mimeType: 'text/plain', content: Buffer.from('hello, signed world'), sign: true });
  assert.equal(codes.length, 1);
  const { extension, mediaPayloadContent } = decodeContentPayload(codes);
  assert.equal(extension[45], 1, 'signature_algorithm must be present');
  assert.equal(extension[47].length, 8, 'signer_id must be 8 bytes');

  // Content Signature travels as Media Payload's own subrecord (SPEC.md
  // §3.1a) — re-locate it from the raw wire bytes directly, since
  // decodeContentPayload's returned `mediaPayload` is just the field map
  // (subrecords aren't map values). Its wire typeId is negated
  // (-TYPE.CONTENT_SIGNATURE, SPEC.md v16 §2.1a).
  const [, mediaPreviewRec] = decodeRootBundle(codes[0]);
  const contentSignatureRec = mediaPreviewRec.subrecords[0].subrecords.find((s) => s.typeId === -TYPE.CONTENT_SIGNATURE);
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
    return [encodeRootBundle([preview, bodyForWire])];
  }

  const fragmentCount = Math.ceil(bodyForWire.length / maxBodyBytes);
  const groupId = sha256(bodyForWire).subarray(0, 8);
  const fragments = splitFragments(bodyForWire, groupId, fragmentCount, true);
  return fragments.map((frag) => encodeRootBundle([preview, frag]));
}

function decodePaperPayload(codes) {
  let preview;
  let previewRawForComparison;
  const fragmentRecords = [];
  let plainBodyRaw = null;

  for (const code of codes) {
    const records = decodeRootBundle(code);
    assert.ok(records && records.length === 2, 'a Paper code must carry exactly a Paper-Preview and a second Record');
    const [first, second] = records;
    // Paper-Preview's wire typeId is negated (-TYPE.PAPER_PREVIEW, SPEC.md
    // v16 §2.1a) — the same magnitude as QDEF's global Media Payload (3),
    // but a different CBOR value by sign alone.
    assert.equal(first.typeId, -TYPE.PAPER_PREVIEW, 'first Record must be Paper-Preview');
    assertKnownKeys(first.record, KNOWN_KEYS.PAPER_PREVIEW, 'Paper-Preview');

    if (previewRawForComparison) {
      assert.ok(first.raw.equals(previewRawForComparison), 'Preview must be byte-identical on every code in the group');
    }
    previewRawForComparison = first.raw;
    preview = first.record;

    if (second.typeId === TYPE.SPLIT) fragmentRecords.push(second.record);
    else plainBodyRaw = second.raw;
  }

  let bodyWireBytes;
  if (fragmentRecords.length > 0) {
    const groupId = fragmentRecords[0][2]; // group_id lives at key 2 as of SPEC.md v15
    bodyWireBytes = reassembleSplit(fragmentRecords, groupId);
  } else {
    bodyWireBytes = plainBodyRaw;
  }
  // TAGDROP_NAMESPACE ambient, same reasoning as decodeContentPayload's own
  // resolveNonSplitWrapperStack call above. Paper-Body's DECLARED magnitude
  // (4) is the same integer as Compress Wrapper's, but as of SPEC.md v16
  // (§2.1a) Paper-Body's actual WIRE value is negated (-4) — genuinely
  // distinct CBOR values by sign alone now, not just by namespace
  // convention (see resolveNonSplitWrapperStack's own updated comment).
  const unwrapped = resolveNonSplitWrapperStack(bodyWireBytes, TAGDROP_NAMESPACE);
  assert.equal(unwrapped.typeId, -TYPE.PAPER_BODY, 'reassembled bytes must decode as Paper-Body');
  assert.ok(
    unwrapped.namespace && unwrapped.namespace.equals(TAGDROP_NAMESPACE),
    'Paper-Body must resolve to TagDrop\'s namespace (SPEC.md v16 §2.1a) — its negated wire typeId no longer even collides with global Compress Wrapper by CBOR value, only by declared magnitude'
  );
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

  // Preview repeated on every code (SPEC.md §5.1) — verify identically, not just "present".
  // decodeRootBundle (not decodeArrayRecord directly on the whole code — each multi-code
  // Paper's own root is now a namespace-prefixed Bundle, SPEC.md §2.1a/§3.5, not one bare
  // Record) is what actually recovers each code's Preview Record.
  const previews = codes.map((c) => decodeRootBundle(c)[0].record);
  const firstPreviewBytes = buildPaperPreview({ rootHash: previews[0][1], hint: previews[0][3], set: previews[0][5], slug: previews[0][7] });
  for (const p of previews.slice(1)) {
    const pBytes = buildPaperPreview({ rootHash: p[1], hint: p[3], set: p[5], slug: p[7] });
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
  // The override blob occupies Media Payload's `content` field, map key 0
  // (SPEC.md v15) — the same slot a Content payload's cache normally
  // occupies (SPEC §9).
  const mediaPreview = buildMediaPreview({ mediaType: 'text/plain', contentHash: cacheId }, [
    buildMediaPayload({ mediaType: 'text/plain', content: blob }),
  ]);
  const codes = [encodeRootBundle([extension, mediaPreview])];

  // Cover story is what's visible without the key.
  const [first, second] = decodeRootBundle(codes[0]);
  assert.equal(first.record[3], coverHint, 'cover hint must be visible with no key present');

  // Trial decryption (§9 "Discovery, not declaration") — try the candidate
  // blob against a known key_material; a wrong key must fail the GCM
  // authentication tag, not silently produce garbage plaintext.
  const candidateBlob = second.subrecords[0].record[0]; // content lives at map key 0 (SPEC.md v15)
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
  const [first, second] = decodeRootBundle(code);
  const rawExtensionBytes = first.raw;
  assert.equal(second.typeId, TYPE.SPLIT);
  const fragmentRecord = second.record;
  const mediaPreviewSubRaw = second.subrecords.find((s) => s.typeId === TYPE.MEDIA_PREVIEW).raw;

  // Flip one bit in the fragment's data (key 0, SPEC.md v15), leaving its
  // declared group_id (key 2) field untouched — the tamper must be caught
  // by recomputing the hash from the actual reassembled bytes, not by
  // comparing declared fields. Media Preview's subrecord (carried on every
  // Split fragment, §3.1a) must be preserved so the tampered fragment still
  // decodes.
  const tamperedFragmentBytes = Buffer.from(fragmentRecord[0]);
  tamperedFragmentBytes[0] ^= 0xff;
  const tamperedFragmentRecordBytes = encodeArrayRecord(TYPE.SPLIT, {
    0: tamperedFragmentBytes, 2: fragmentRecord[2], 4: fragmentRecord[4],
    6: fragmentRecord[6], 7: fragmentRecord[7], 9: fragmentRecord[9],
  }, [mediaPreviewSubRaw]);
  const tamperedCode = encodeRootBundle([rawExtensionBytes, tamperedFragmentRecordBytes]);
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

  const [first, second] = decodeRootBundle(codes[0]);
  const rawPreviewBytes = first.raw;
  const bodyRecord = second.record;

  const tamperedFiles = encodeArray([encodeRecord({ 1: 'index-RENAMED', 2: 'text/html', 3: randomBytes(8) })]);
  const tamperedBodyBytes = buildPaperBody({ files: tamperedFiles, related: bodyRecord[3] });
  const tamperedCode = encodeRootBundle([rawPreviewBytes, tamperedBodyBytes]);

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
  // As of SPEC.md v16 (§2.1a), a lone Content Extension is no longer written
  // as the root directly — a Record can't simultaneously introduce a
  // namespace and be scoped by it — so this now goes through
  // encodeRootBundle just like the common two-Record case, uniformly
  // wrapping it as the Bundle's sole subrecord (one more byte than versions
  // 13-15's "no Bundle indirection" shape, §2.1a's byte-cost accounting).
  const record = buildContentExtension({ keyMaterial, retainKey: false });
  const code = encodeRootBundle([record]);

  // The raw array header must be a 2-item array (namespace bstr + one
  // subrecord) — confirms real Bundle-wrapping happened, not a leftover
  // "no wrapping" shape.
  assert.equal(code[0] >> 5, 4, 'a key-only code\'s root must be a CBOR array');
  assert.equal(code[0] & 0x1f, 2, 'a key-only code\'s root Bundle must have exactly 2 items: namespace + Content Extension');

  const records = decodeRootBundle(code);
  assert.ok(records && records.length === 1, 'a key-only code must decode as a Bundle wrapping exactly one Record');
  const decoded = records[0];
  // Content Extension's wire typeId is negated (-TYPE.CONTENT_EXTENSION,
  // SPEC.md v16 §2.1a) — the same magnitude as QDEF's global Split Wrapper
  // (1), but a different CBOR value by sign alone.
  assert.equal(decoded.typeId, -TYPE.CONTENT_EXTENSION, 'Content Extension\'s wire typeId must be negated');
  assert.ok(decoded.namespace && decoded.namespace.equals(TAGDROP_NAMESPACE), 'a key-only code\'s Content Extension must resolve to TagDrop\'s namespace via the Bundle root, not carry its own namespace item');
  const record2 = decoded.record;
  assert.ok(record2[33].equals(keyMaterial));
  assert.equal(record2[35], false, 'retain_key must round-trip as a real boolean, not a truthy placeholder');
  assert.equal(record2[1], undefined, 'Content Extension has no field 1 at all as of v9 (contentHash moved to Media Preview, §3.1)');

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

function testWrongNamespaceRejected() {
  // SPEC.md §2.1a: the namespace resolution mechanism itself, not just its
  // absence. TagDrop's small Type IDs, negated (-1/-2/-3/-4), are the same
  // integers (once you drop the sign) QDEF's own global standard Types use
  // (Split/Media Payload/Compress) — a Record with the "right" Type ID but a
  // namespace that resolves to something other than TagDrop's own MUST NOT
  // be accepted as TagDrop content, even though every field/shape otherwise
  // looks legitimate.
  const codes = buildContentPayload({ hint: 'ok', mimeType: 'text/plain', content: Buffer.from('hi') });
  const [first, second] = decodeRootBundle(codes[0]);
  const wrongNamespace = Buffer.from([0xde, 0xad, 0xbe, 0xef]);
  const wrongNamespaceCode = encodeArray([encodeBytes(wrongNamespace), first.raw, second.raw]);
  assert.throws(
    () => decodeContentPayload([wrongNamespaceCode]),
    /namespace/i,
    'a root declaring the wrong namespace must not be accepted as TagDrop content'
  );

  // Same check with the namespace declaration dropped entirely, rather than
  // substituted. Under SPEC.md v14-15 this fell through to the same
  // namespace-mismatch check above (an absent namespace slot still meant
  // global/standard Type-ID space, no ambient value rescuing it). As of v16,
  // the rejection point moves EARLIER and gets structural rather than
  // semantic: every real root is now a namespace-declaring Bundle by
  // construction (§2.1a — "a Record can no longer simultaneously introduce
  // a namespace and be scoped by it"), so a root whose first item isn't a
  // namespace byte string at all doesn't even parse as a well-formed Bundle
  // — decodeRootBundle itself returns null, caught here by
  // decodeContentPayload's own "must carry exactly..." shape check rather
  // than reaching the namespace-equality assertion.
  const noNamespaceCode = encodeArray([first.raw, second.raw]);
  assert.throws(
    () => decodeContentPayload([noNamespaceCode]),
    /Content Extension and a second Record/,
    'a root with no namespace declared at all must be rejected as a malformed Bundle, not merely as an unresolved namespace'
  );

  return { name: 'wrong-namespace-rejected' };
}

function main() {
  const results = [];
  console.log('QDEF-redesign round-trip prototype (SPEC.md v16)\n');

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
    testWrongNamespaceRejected,
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
