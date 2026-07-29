// Verifies that the example page's generated codes would pass qdef-lint.cjs with
// the correct QDEF magic header present — the magic is required for byte-mode QR
// codes (QDEF-SPEC.md §2) and must be tested, not just assumed.
//
// Loads the logical codes from qdef-fixtures.json (the same ones test:qdef and
// lint:qdef already validate without magic), wraps each with the QDEF magic header
// via qdefFrame() — mirroring what tools/examples/index.html does for binary QR
// cards (addBinaryQrCard's qdefFrame(rawBytes) at line 2645) — and re-lints with
// hasMagic: true to confirm the magic is present and the resulting framed container
// is well-formed.  This is the same independent grammar/footgun checker
// (qdef-lint.cjs, vendored from qdef-format.github.io's prototype) that lint:qdef
// already runs on the bare codes, but exercised here through the carrier framing
// path the examples page actually ships.
//
// Usage:
//   npm run lint:examples     # after test:qdef has regenerated qdef-fixtures.json

import { readFileSync } from 'node:fs';
import { lintRootBytes } from './qdef-lint.cjs';

const QDEF_MAGIC = new Uint8Array([0x51, 0x44, 0x45, 0x46]); // "QDEF"

function qdefFrame(recordBytes) {
  return Buffer.concat([Buffer.from(QDEF_MAGIC), Buffer.isBuffer(recordBytes) ? recordBytes : Buffer.from(recordBytes)]);
}

const fixtures = JSON.parse(readFileSync(new URL('./qdef-fixtures.json', import.meta.url)));

let codeCount = 0;
let failed = false;

for (const fixture of fixtures) {
  if (!fixture.codes) continue; // tamper-only fixtures have no codes to lint
  for (const hex of fixture.codes) {
    codeCount++;
    const raw = Buffer.from(hex, 'hex');
    const framed = qdefFrame(raw);

    // Check 1: magic header must be present and lint the framed container as
    // a byte-mode QR code would arrive at the reader.
    const findings = lintRootBytes(framed, { hasMagic: true });
    const errors = findings.filter((f) => f.severity === 'error');
    const warnings = findings.filter((f) => f.severity === 'warning');

    // Check 2: also verify the bare code passes with hasMagic:false (the URI
    // / NDEF carrier path), since the same logical bytes travel both ways.
    const bareFindings = lintRootBytes(raw, { hasMagic: false });
    const bareErrors = bareFindings.filter((f) => f.severity === 'error');

    if (errors.length > 0 || warnings.length > 0 || bareErrors.length > 0) {
      failed = true;
      const all = [...findings, ...bareFindings].filter(
        (f, i, a) => a.findIndex((x) => x.code === f.code && x.pos === f.pos) === i
      );
      console.log(`--- ${fixture.name} (${raw.length} raw + 4 magic) ---`);
      for (const f of all) {
        console.log(`  ${f.severity.toUpperCase().padEnd(7)} [${f.code}] @${f.pos}: ${f.message}`);
      }
    }
  }
}

if (failed) {
  console.log();
  console.log('qdef-lint found error(s)/warning(s) on framed examples — see above.');
  console.log('Check that qdefFrame() prepends the 4-byte QDEF magic and that');
  console.log('the wrapped container is correctly structured.');
  process.exit(1);
}

console.log(
  `qdef-lint: ${codeCount} code(s) across ${fixtures.length} fixtures — ` +
  `clean with magic framing (0 errors, 0 warnings).`
);
