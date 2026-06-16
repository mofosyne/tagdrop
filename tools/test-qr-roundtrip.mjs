/**
 * QR round-trip test: encode a URI → render as QR image → decode with jsQR.
 *
 * Catches failures the URI-level codec tests can't see:
 *   - URI too long for any QR version (qrcode throws)
 *   - Rendered image too dense for jsQR to decode at the given pixel size
 *
 * Run with:
 *   cd tools
 *   npm install     # installs qrcode, jsqr, pngjs (see package.json)
 *   node test-qr-roundtrip.mjs
 */
import QRCode from 'qrcode';
import jsQR   from 'jsqr';
import { PNG } from 'pngjs';

// ── Helpers ────────────────────────────────────────────────────────────────
async function uriToQrPng(uri, width) {
  return QRCode.toBuffer(uri, { errorCorrectionLevel: 'L', margin: 2, width, type: 'png' });
}

function pngToImageData(buf) {
  const png = PNG.sync.read(buf);
  return { data: Uint8ClampedArray.from(png.data), width: png.width, height: png.height };
}

async function roundTrip(label, uri, width) {
  process.stdout.write(`  ${label} (URI ${uri.length} chars, ${width}px) … `);
  let pngBuf;
  try {
    pngBuf = await uriToQrPng(uri, width);
  } catch (e) {
    console.log(`FAIL  URI too long for any QR version: ${e.message}`);
    return false;
  }
  const { data, width: w, height: h } = pngToImageData(pngBuf);
  const code = jsQR(data, w, h, { inversionAttempts: 'dontInvert' });
  if (!code) {
    console.log(`FAIL  rendered at ${w}px but jsQR could not decode it — too dense at this size`);
    return false;
  }
  if (code.data !== uri) {
    console.log(`FAIL  decoded text doesn't match: "${code.data.slice(0, 60)}…"`);
    return false;
  }
  console.log('ok');
  return true;
}

// Build a realistic-length TagDrop URI by cycling through the Base45 charset
// (what the actual payload looks like after encoding).
const B45 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:';
const payload = n => Array.from({ length: n }, (_, i) => B45[i % 45]).join('');

// Test sizes represent real-world scenarios:
//   ~200 chars — small text drop
//   ~500 chars — typical HTML page (compressed)
//   ~800 chars — larger HTML / uncompressed small file
//   ~1200 chars — larger file or encrypted+compressed content
//   ~1800 chars — large file approaching practical limits
const CASES = [200, 500, 800, 1200, 1800].map(n => ({
  label: `~${n}-char payload`,
  uri:   'tagdrop:' + payload(n),
}));

// Pixel widths to check: 400px is our on-screen preview, 1024px is Download PNG.
const WIDTHS = [400, 1024];

let pass = 0, fail = 0;
console.log('QR round-trip test\n');

for (const { label, uri } of CASES) {
  console.log(`${label}:`);
  for (const w of WIDTHS) {
    const ok = await roundTrip(w === 400 ? 'preview' : 'download PNG', uri, w);
    ok ? pass++ : fail++;
  }
}

console.log(`\n${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
