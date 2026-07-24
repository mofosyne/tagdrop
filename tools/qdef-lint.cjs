#!/usr/bin/env node
'use strict';
// Vendored, unmodified, from mofosyne/qdef's prototype/scripts/qdef-lint.js (see that
// repo's own docs/QDEF-SPEC.md/DESIGN.md for the grammar this checks against) — an
// independent, standalone QDEF grammar-and-footgun checker written from scratch against
// the spec, sharing no code with any of TagDrop's own encoders/decoders. Used here (see
// verify-qdef-lint.mjs) as a second, independently-written check on TagDrop's own wire
// bytes — real value already demonstrated once: this file's own canonical-key-order check
// would have caught SPEC.md v12's key-ordering bug directly. `.cjs` extension because
// tools/package.json declares "type": "module" but this file is CommonJS as vendored.
//
// A standalone QDEF grammar-and-footgun checker -- bolts onto any
// encoder's raw output bytes, not just this prototype's own. Two
// deliberately separate layers:
//
//   1. Grammar checking: is this a well-formed Record (namespace?,
//      typeId?, map?, payload?, subrecord*, spec §3.1), self-delimited
//      as one CBOR array (§2's "Self-delimited root")? No Record-Type
//      semantics involved -- the same boundary the mandatory core
//      itself draws (§3.3).
//   2. Footgun checking: patterns that are grammatically legal CBOR but
//      are almost certainly not what the encoder meant -- each one
//      traced to a real, documented mistake in docs/FINDINGS.md, not
//      invented for this tool.
//
// Deliberately dependency-free and written from raw CBOR primitives
// (not the `cbor` npm package, not core.js) so the algorithm itself --
// not this specific JS encoding of it -- is what's meant to be ported
// to another language, the same way rust/qdef-core's cbor.rs is a
// from-scratch port of the same primitives, not a wrapper around one.
// See docs/ROADMAP.md.

// ---------------------------------------------------------------------
// CBOR primitives (mirrors rust/qdef-core/src/cbor.rs's read_head/
// skip_any_item exactly -- same algorithm, ported, not reinvented).
// ---------------------------------------------------------------------

class CborError extends Error {}

// skipAnyItem recursively audits everything it walks over (canonical
// encoding, duplicate map keys) as a side effect of computing an item's
// byte span. Used here wherever a span is needed purely for bounding --
// the actual grammar walk (lintRecordItems) separately re-visits and
// audits that same content for real, so passing the real `findings`
// array to both would double-report every finding underneath.
const NO_FINDINGS = { push() {} };

/**
 * Reads one CBOR item head at buf[pos]: major type, additional-info
 * field, decoded argument, and total header length. Also reports
 * whether this particular encoding of the argument is the CANONICAL
 * (shortest) form RFC 8949 §4.2.1 requires -- used by the canonical-
 * encoding footgun checks below, not by grammar recognition itself
 * (a non-canonical but well-formed item is still grammatically legal).
 */
function readHead(buf, pos) {
  if (pos >= buf.length) throw new CborError('unexpected end of buffer reading a CBOR head');
  const b0 = buf[pos];
  const major = b0 >> 5;
  const info = b0 & 0x1f;

  if (info <= 23) {
    return { major, info, arg: BigInt(info), headLen: 1, indefinite: false, canonical: true };
  }
  if (info === 24) {
    if (pos + 1 >= buf.length) throw new CborError('unexpected end of buffer reading a 1-byte argument');
    const arg = BigInt(buf[pos + 1]);
    return { major, info, arg, headLen: 2, indefinite: false, canonical: arg >= 24n };
  }
  if (info === 25) {
    if (pos + 2 >= buf.length) throw new CborError('unexpected end of buffer reading a 2-byte argument');
    const arg = BigInt((buf[pos + 1] << 8) | buf[pos + 2]);
    return { major, info, arg, headLen: 3, indefinite: false, canonical: arg > 0xffn };
  }
  if (info === 26) {
    if (pos + 4 >= buf.length) throw new CborError('unexpected end of buffer reading a 4-byte argument');
    const arg = BigInt(buf.readUInt32BE(pos + 1));
    return { major, info, arg, headLen: 5, indefinite: false, canonical: arg > 0xffffn };
  }
  if (info === 27) {
    if (pos + 8 >= buf.length) throw new CborError('unexpected end of buffer reading an 8-byte argument');
    const arg = buf.readBigUInt64BE(pos + 1);
    return { major, info, arg, headLen: 9, indefinite: false, canonical: arg > 0xffffffffn };
  }
  if (info >= 28 && info <= 30) {
    throw new CborError(`reserved additional-info value ${info} at byte ${pos}`);
  }
  // info === 31: indefinite-length marker (major 2/3/4/5) or break (major 7).
  return { major, info, arg: 0n, headLen: 1, indefinite: true, canonical: false };
}

/**
 * Skips one well-formed CBOR item of any shape starting at buf[pos],
 * returning the position immediately after it. Reports every
 * non-canonical-encoding and duplicate-map-key finding it notices along
 * the way into `findings` (it already visits every byte, so this is
 * free -- no second walk needed). Throws CborError on anything that
 * isn't even well-formed CBOR, regardless of QDEF grammar.
 */
function skipAnyItem(buf, pos, findings) {
  const head = readHead(buf, pos);
  const start = pos;
  pos += head.headLen;

  if (!head.canonical) {
    findings.push(warn('non-canonical-length', start, 'integer/length argument is not the shortest possible encoding (RFC 8949 §4.2.1 / spec §3.4)'));
  }

  switch (head.major) {
    case 0: // uint
    case 1: // negint
      return pos;
    case 2: // byte string
    case 3: { // text string
      if (head.indefinite) {
        findings.push(warn('indefinite-length', start, 'indefinite-length string (spec §3.4 requires definite-length from a conformant encoder)'));
        for (;;) {
          if (pos >= buf.length) throw new CborError('unexpected end of buffer in indefinite-length string');
          if (buf[pos] === 0xff) { pos += 1; break; }
          const chunkHead = readHead(buf, pos);
          if (chunkHead.major !== head.major || chunkHead.indefinite) {
            throw new CborError(`malformed indefinite-length string chunk at byte ${pos}`);
          }
          pos = chunkHead.headLen + pos + Number(chunkHead.arg);
        }
        return pos;
      }
      const len = Number(head.arg);
      if (pos + len > buf.length) throw new CborError('unexpected end of buffer reading string payload');
      return pos + len;
    }
    case 4: { // array
      if (head.indefinite) {
        findings.push(warn('indefinite-length', start, 'indefinite-length array (spec §3.4 requires definite-length from a conformant encoder)'));
        while (buf[pos] !== 0xff) pos = skipAnyItem(buf, pos, findings);
        return pos + 1;
      }
      const count = Number(head.arg);
      for (let i = 0; i < count; i++) pos = skipAnyItem(buf, pos, findings);
      return pos;
    }
    case 5: { // map
      const seenKeys = [];
      const checkKey = (keyBytes) => {
        const hex = Buffer.from(keyBytes).toString('hex');
        if (seenKeys.includes(hex)) {
          findings.push(warn('duplicate-map-key', start, 'map has a duplicate key -- later encoders/decoders may disagree on which value wins'));
        }
        seenKeys.push(hex);
      };
      if (head.indefinite) {
        findings.push(warn('indefinite-length', start, 'indefinite-length map (spec §3.4 requires definite-length from a conformant encoder)'));
        while (buf[pos] !== 0xff) {
          const keyStart = pos;
          pos = skipAnyItem(buf, pos, findings);
          checkKey(buf.subarray(keyStart, pos));
          pos = skipAnyItem(buf, pos, findings); // value
        }
        return pos + 1;
      }
      const pairs = Number(head.arg);
      let prevKeyBytes = null;
      for (let i = 0; i < pairs; i++) {
        const keyStart = pos;
        pos = skipAnyItem(buf, pos, findings);
        const keyBytes = buf.subarray(keyStart, pos);
        checkKey(keyBytes);
        if (prevKeyBytes && !isCanonicalKeyOrder(prevKeyBytes, keyBytes)) {
          findings.push(warn('non-canonical-key-order', keyStart, 'map keys are not in canonical (bytewise, length-first) order (RFC 8949 §4.2.1 / spec §3.4)'));
        }
        prevKeyBytes = keyBytes;
        pos = skipAnyItem(buf, pos, findings); // value
      }
      return pos;
    }
    case 6: // tag: exactly one nested item
      return skipAnyItem(buf, pos, findings);
    case 7: // simple/float/break
      if (head.indefinite) throw new CborError(`unexpected break byte at byte ${start} (not inside an indefinite-length container)`);
      return pos;
    default:
      throw new CborError(`unreachable major type ${head.major}`);
  }
}

/** RFC 8949 §4.2.1 canonical map key order: shorter encoding first, then bytewise. */
function isCanonicalKeyOrder(a, b) {
  if (a.length !== b.length) return a.length < b.length;
  return Buffer.compare(a, b) <= 0;
}

function err(code, pos, message) {
  return { severity: 'error', code, pos, message };
}
function warn(code, pos, message) {
  return { severity: 'warning', code, pos, message };
}
function info(code, pos, message) {
  return { severity: 'info', code, pos, message };
}

// ---------------------------------------------------------------------
// Grammar walk (mirrors core.js's parseRecordFromItems / rust/qdef-
// core's parse_record_items -- same positional algorithm, instrumented
// to report findings instead of building a decoded record).
// ---------------------------------------------------------------------

/**
 * Walks one Record's own item list, buf[pos..end) (already known to be
 * exactly one array's worth of bytes, self-delimited or subrecord-
 * bounded -- see lintRootBytes). Appends findings; does not throw for
 * QDEF-grammar-level issues (only for genuinely malformed CBOR, since
 * a malformed inner Record must never stop discovery of its siblings,
 * spec §3.1's "Implementer caution for subrecords").
 */
function lintRecordItems(buf, pos, end, findings) {
  // namespace?
  if (pos < end) {
    const head = readHead(buf, pos);
    if (head.major === 2 && !head.indefinite) {
      pos = skipAnyItem(buf, pos, findings);
    }
  }

  // typeId?
  if (pos < end) {
    const head = readHead(buf, pos);
    if (head.major === 0) {
      pos = skipAnyItem(buf, pos, findings);
    } else if (head.major === 6) {
      // FOOTGUN: a tag sits exactly where typeId would be recognized.
      // Tag 2/3 (bignum) is the real, documented mistake (FINDINGS.md
      // #14: a Type ID above Number.MAX_SAFE_INTEGER silently promoted
      // to a bignum tag by a naive encoder, instead of a native uint) --
      // flagged specifically; any other tag here is just an ordinary
      // (if unusual) typeId-defaults-to-Bundle case, not a known footgun.
      if (head.arg === 2n || head.arg === 3n) {
        findings.push(warn('bignum-typeid', pos, 'a CBOR bignum tag (2 or 3) sits where typeId would be recognized -- QDEF Type IDs MUST be a native uint (major type 0) even above 2^53; this position will default to Bundle (typeId 0) instead of routing as intended (see FINDINGS.md #14)'));
      }
    }
  }

  // Deliberately NOT flagged: "namespace present, typeId absent." Tested
  // against real encoder output and dropped -- it fires on the single
  // most standard documented root shape in the spec (a namespaced Bundle
  // with a hint and content, §3.5's own worked example), since that
  // shape is byte-identical to the real footgun (a namespace-shaped
  // payload with a forgotten typeId, §3.1). The spec is explicit that
  // this ambiguity can only be resolved by the encoder, not decoded back
  // out of the bytes after the fact -- so a linter operating on bytes
  // alone has no reliable signal here, and flagging the common case
  // makes the tool actively unhelpful. See docs/DESIGN.md.

  // map?
  let hasMap = false;
  if (pos < end) {
    const head = readHead(buf, pos);
    if (head.major === 5) {
      pos = skipAnyItem(buf, pos, findings);
      hasMap = true;
    }
  }
  void hasMap;

  // payload?: anything non-array right here.
  if (pos < end) {
    const head = readHead(buf, pos);
    if (head.major !== 4) {
      pos = skipAnyItem(buf, pos, findings);
    }
  }

  // subrecord*: every remaining item must be an array to be reachable
  // by the real decoder -- a non-array item here is not an error (the
  // real decoder silently skips it, spec's forward-compat tolerance),
  // but it's dead weight on the wire, worth flagging at low severity.
  while (pos < end) {
    const arrayStart = pos;
    const head = readHead(buf, pos);
    if (head.major === 4 && !head.indefinite) {
      if (!head.canonical) {
        findings.push(warn('non-canonical-length', arrayStart, 'integer/length argument is not the shortest possible encoding (RFC 8949 §4.2.1 / spec §3.4)'));
      }
      const subContentStart = pos + head.headLen;
      const subEnd = skipAnyItem(buf, pos, NO_FINDINGS); // bounding only -- see NO_FINDINGS
      lintRecordItems(buf, subContentStart, subEnd, findings);
      pos = subEnd;
    } else {
      const deadStart = pos;
      pos = skipAnyItem(buf, pos, findings);
      findings.push(info('unreachable-subrecord-slot', deadStart, 'a non-array item sits in subrecord position -- the real decoder silently skips it (never an error), so these bytes are permanently unreachable'));
    }
  }
}

/**
 * Lints a full QDEF container or NDEF/own-URI body: the root Record,
 * self-delimited as one CBOR array (spec §2's "Self-delimited root"),
 * optionally preceded by the 4-byte magic. Bytes after the root array
 * are provably outside the container, per that same design -- reported
 * as an informational finding, never an error.
 */
function lintRootBytes(buf, { hasMagic = true } = {}) {
  const findings = [];
  let pos = 0;

  if (hasMagic) {
    const MAGIC = Buffer.from([0x51, 0x44, 0x45, 0x46]);
    if (buf.length < 4 || !buf.subarray(0, 4).equals(MAGIC)) {
      findings.push(err('bad-magic', 0, `expected magic bytes 51 44 45 46 ("QDEF"), got ${buf.subarray(0, 4).toString('hex')}`));
      return findings;
    }
    pos = 4;
  }

  let head;
  try {
    head = readHead(buf, pos);
  } catch (e) {
    findings.push(err('malformed-cbor', pos, e.message));
    return findings;
  }
  if (head.major !== 4 || head.indefinite) {
    findings.push(err('root-not-array', pos, 'the root is not a definite-length CBOR array -- every Record, root included, must be exactly one self-delimited array (spec §2/§3.1)'));
    return findings;
  }
  if (!head.canonical) {
    findings.push(warn('non-canonical-length', pos, 'integer/length argument is not the shortest possible encoding (RFC 8949 §4.2.1 / spec §3.4)'));
  }

  let rootEnd;
  try {
    rootEnd = skipAnyItem(buf, pos, NO_FINDINGS); // bounding only -- see NO_FINDINGS
  } catch (e) {
    findings.push(err('malformed-cbor', pos, e.message));
    return findings;
  }

  try {
    lintRecordItems(buf, pos + head.headLen, rootEnd, findings);
  } catch (e) {
    findings.push(err('malformed-cbor', pos, e.message));
  }

  if (rootEnd < buf.length) {
    findings.push(info('trailing-bytes', rootEnd, `${buf.length - rootEnd} byte(s) after the root array -- allowed and ignored by a conformant decoder (spec §2), not part of this container`));
  }

  return findings;
}

module.exports = { lintRootBytes, readHead, skipAnyItem, CborError };

// ---------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------

if (require.main === module) {
  const args = process.argv.slice(2);
  const noMagic = args.includes('--no-magic');
  const filtered = args.filter((a) => a !== '--no-magic');

  if (filtered.length !== 1) {
    console.error('Usage: node qdef-lint.cjs [--no-magic] <file | hex-string>');
    console.error();
    console.error('  --no-magic   input is a bare Record body (NDEF/own-URI path), no 4-byte magic prefix');
    process.exit(2);
  }

  const arg = filtered[0];
  const fs = require('fs');
  let buf;
  if (/^[0-9a-fA-F]+$/.test(arg) && arg.length % 2 === 0) {
    buf = Buffer.from(arg, 'hex');
  } else {
    buf = fs.readFileSync(arg);
  }

  const findings = lintRootBytes(buf, { hasMagic: !noMagic });
  const bySeverity = { error: 0, warning: 0, info: 0 };
  for (const f of findings) {
    bySeverity[f.severity]++;
    console.log(`${f.severity.toUpperCase().padEnd(7)} @${f.pos}\t[${f.code}] ${f.message}`);
  }
  console.log();
  console.log(`${bySeverity.error} error(s), ${bySeverity.warning} warning(s), ${bySeverity.info} info`);
  process.exit(bySeverity.error > 0 ? 1 : 0);
}
