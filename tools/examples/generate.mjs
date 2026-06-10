#!/usr/bin/env node
// Build script: pre-generates tools/examples/index.html with static QR codes
// for TagDrop test payloads. Run `npm install && npm run build` to regenerate.
//
// Output is a fully static, offline-capable HTML page — no CDN, no runtime
// crypto/compression APIs needed by the browser.

import { createHash } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import QRCode from 'qrcode';

const __dirname = dirname(fileURLToPath(import.meta.url));

// ── Base45 ────────────────────────────────────────────────────────────────
const B45 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:';
function base45Encode(u8) {
  let s = '';
  for (let i = 0; i < u8.length - 1; i += 2) {
    const n = u8[i] * 256 + u8[i + 1];
    s += B45[n % 45] + B45[Math.floor(n / 45) % 45] + B45[Math.floor(n / 2025)];
  }
  if (u8.length & 1) {
    const n = u8[u8.length - 1];
    s += B45[n % 45] + B45[Math.floor(n / 45)];
  }
  return s;
}

// ── Minimal CBOR encoder ──────────────────────────────────────────────────
function writeHead(out, major, n) {
  const m = major << 5;
  if      (n <= 23)       { out.push(m | n); }
  else if (n <= 0xFF)     { out.push(m | 24, n); }
  else if (n <= 0xFFFF)   { out.push(m | 25, n >> 8, n & 0xFF); }
  else { out.push(m | 26, (n >>> 24) & 0xFF, (n >>> 16) & 0xFF, (n >>> 8) & 0xFF, n & 0xFF); }
}

function cborValue(out, v) {
  if (v === null || v === undefined) return;
  if (typeof v === 'number') { writeHead(out, 0, v); }
  else if (v instanceof Uint8Array) { writeHead(out, 2, v.length); v.forEach(b => out.push(b)); }
  else if (typeof v === 'string') {
    const b = Buffer.from(v, 'utf8');
    writeHead(out, 3, b.length); b.forEach(x => out.push(x));
  } else if (Array.isArray(v)) {
    writeHead(out, 4, v.length); v.forEach(item => cborValue(out, item));
  } else if (v && v.__map) {
    const pairs = v.pairs.filter(([, x]) => x !== null && x !== undefined);
    writeHead(out, 5, pairs.length);
    pairs.forEach(([k, x]) => { writeHead(out, 0, k); cborValue(out, x); });
  }
}

function cborMap(pairs) {
  const nonNull = pairs.filter(([, v]) => v !== null && v !== undefined);
  const out = [];
  writeHead(out, 5, nonNull.length);
  nonNull.forEach(([k, v]) => { writeHead(out, 0, k); cborValue(out, v); });
  return new Uint8Array(out);
}

function subMap(pairs) { return { __map: true, pairs }; }

// ── SHA-256 ───────────────────────────────────────────────────────────────
function sha256first8(bytes) {
  const hash = createHash('sha256').update(bytes).digest();
  return new Uint8Array(hash.subarray(0, 8));
}

// ── DEFLATE (zlib-wrapped, matching Android's DeflaterOutputStream) ───────
function zlibCompress(bytes) {
  return new Uint8Array(deflateSync(Buffer.from(bytes)));
}

// ── TagDrop CBOR keys ─────────────────────────────────────────────────────
const K = { VERSION:1, CACHE_ID:2, HINT:3, MIME:4, CONTENT:5,
            FILENAME:11, COMPRESSION:12, SET:13, SLUG:14, FILES:15, RELATED:16,
            FILE_SLUG:20, FILE_MIME:21, FILE_ID:22, PAPER_ID:23 };

// ── Encoding ──────────────────────────────────────────────────────────────
function encodeSingle({ hint, filename, mimeType, rawBytes, compress }) {
  let content = rawBytes, compression = null;
  if (compress) { content = zlibCompress(rawBytes); compression = 1; }
  const cacheId = sha256first8(rawBytes);
  const cbor = cborMap([
    [K.VERSION,     1],
    [K.CACHE_ID,    cacheId],
    [K.HINT,        hint || null],
    [K.FILENAME,    filename || null],
    [K.MIME,        mimeType],
    [K.COMPRESSION, compression],
    [K.CONTENT,     content],
  ]);
  return { uri: 'tagdrop://v1/s/' + base45Encode(cbor), cacheId };
}

function encodePaperManifest({ label, set, slug, files, related }) {
  const cborNoHash = cborMap([
    [K.VERSION, 1],
    [K.HINT,    label || null],
    [K.SET,     set   || null],
    [K.SLUG,    slug  || null],
    [K.FILES,   files.map(f => subMap([
      [K.FILE_SLUG, f.slug], [K.FILE_MIME, f.mimeType], [K.FILE_ID, f.fileId]
    ]))],
    [K.RELATED, (related || []).map(r => subMap([
      [K.HINT, r.hint], [K.SET, r.set || null], [K.SLUG, r.slug || null], [K.PAPER_ID, r.paperId || null]
    ]))],
  ]);
  const rootHash = sha256first8(cborNoHash);
  const cbor = cborMap([
    [K.VERSION,  1],
    [K.CACHE_ID, rootHash],
    [K.HINT,     label || null],
    [K.SET,      set   || null],
    [K.SLUG,     slug  || null],
    [K.FILES,    files.map(f => subMap([
      [K.FILE_SLUG, f.slug], [K.FILE_MIME, f.mimeType], [K.FILE_ID, f.fileId]
    ]))],
    [K.RELATED,  (related || []).map(r => subMap([
      [K.HINT, r.hint], [K.SET, r.set || null], [K.SLUG, r.slug || null], [K.PAPER_ID, r.paperId || null]
    ]))],
  ]);
  return { uri: 'tagdrop://v1/p/' + base45Encode(cbor), rootHash };
}

function toHex(u8) {
  return Array.from(u8).map(b => b.toString(16).padStart(2, '0')).join('');
}

function escHtml(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

// ── QR rendering (inline SVG, no canvas/CDN needed) ───────────────────────
async function renderQrSvg(uri) {
  return QRCode.toString(uri, { type: 'svg', errorCorrectionLevel: 'L', margin: 2, width: 200 });
}

// ── Example definitions ───────────────────────────────────────────────────
const STANDALONE_EXAMPLES = [
  {
    label: 'Plain text',
    mimeType: 'text/plain',
    compress: false,
    hint: null,
    filename: 'hello.txt',
    content:
`Hello from TagDrop!

This is a plain-text payload — no formatting, no compression.
Scan this QR with the TagDrop app or the web reader to see this message.`,
  },
  {
    label: 'HTML page',
    mimeType: 'text/html',
    compress: true,
    hint: null,
    filename: 'welcome.html',
    content:
`<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TagDrop Example</title>
<style>
body{font-family:system-ui,sans-serif;max-width:560px;margin:40px auto;padding:0 20px;color:#222;line-height:1.6}
h1{color:#1a1a2e}code{background:#f0f0f0;padding:2px 6px;border-radius:3px}
</style>
</head>
<body>
<h1>Welcome to TagDrop</h1>
<p>This HTML page was stored inside a QR code and rendered entirely offline.</p>
<p>No internet, no server — just a <code>tagdrop://</code> URI decoded from the QR.</p>
<hr>
<p><small>Payload type: <code>text/html</code> · Encoding: DEFLATE compressed</small></p>
</body>
</html>`,
  },
  {
    label: 'SVG graphic',
    mimeType: 'image/svg+xml',
    compress: false,
    hint: null,
    filename: 'logo.svg',
    content:
`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="200" height="200">
  <circle cx="50" cy="50" r="48" fill="#1a1a2e"/>
  <text x="50" y="34" text-anchor="middle" fill="#aaa" font-family="system-ui,sans-serif" font-size="10">TAG</text>
  <text x="50" y="56" text-anchor="middle" fill="#4fc3f7" font-family="system-ui,sans-serif" font-size="22" font-weight="bold">DROP</text>
  <rect x="28" y="64" width="44" height="4" rx="2" fill="#fff" opacity="0.35"/>
  <rect x="34" y="72" width="32" height="3" rx="2" fill="#fff" opacity="0.22"/>
  <rect x="40" y="79" width="20" height="3" rx="2" fill="#fff" opacity="0.13"/>
</svg>`,
  },
  {
    label: 'JSON data',
    mimeType: 'application/json',
    compress: false,
    hint: null,
    filename: 'info.json',
    content: JSON.stringify({
      "tagdrop": "example",
      "version": 1,
      "message": "Scan me with TagDrop!",
      "tags": ["demo", "test", "qr"]
    }, null, 2),
  },
];

const PAPER_EXAMPLE = {
  label: 'TagDrop Test Paper',
  set:   'tagdrop-tests',
  slug:  'test-paper',
  files: [
    {
      slug:     'readme',
      mimeType: 'text/html',
      compress: true,
      content:
`<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Test Paper</title>
<style>
body{font-family:system-ui,sans-serif;max-width:540px;margin:30px auto;padding:0 20px;color:#222;line-height:1.5}
h1{color:#1a1a2e}pre{background:#f5f5f5;padding:12px;border-radius:6px;overflow:auto}
</style>
</head>
<body>
<h1>TagDrop Test Paper</h1>
<p>This paper belongs to the <strong>tagdrop-tests</strong> set.
It has three files — each stored in its own QR code on the same physical paper.</p>
<h2>Files on this paper</h2>
<ul>
  <li><strong>readme</strong> (this page) — text/html</li>
  <li><strong>note</strong> — text/plain</li>
  <li><strong>badge</strong> — image/svg+xml</li>
</ul>
<p>Once you have scanned the manifest and all file QRs into the app, the other
files can be referenced via <code>tagdrop://</code> navigation links.</p>
</body>
</html>`,
    },
    {
      slug:     'note',
      mimeType: 'text/plain',
      compress: false,
      content:
`TagDrop Test Paper — notes
===========================

This plain-text file is the "note" slug on the test-paper paper.
It lives alongside readme (HTML) and badge (SVG) under the same manifest.

Set:   tagdrop-tests
Paper: test-paper
Slug:  note
`,
    },
    {
      slug:     'badge',
      mimeType: 'image/svg+xml',
      compress: false,
      content:
`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 50" width="320" height="100">
  <rect width="160" height="50" rx="8" fill="#1a1a2e"/>
  <rect x="0"  y="0" width="60" height="50" rx="8" fill="#4fc3f7"/>
  <rect x="54" y="0" width="6"  height="50" fill="#4fc3f7"/>
  <text x="30" y="32" text-anchor="middle" fill="#fff" font-family="system-ui,sans-serif" font-size="16" font-weight="bold">TAG</text>
  <text x="110" y="32" text-anchor="middle" fill="#fff" font-family="system-ui,sans-serif" font-size="16" font-weight="bold">DROP</text>
</svg>`,
    },
  ],
};

// ── Card rendering ────────────────────────────────────────────────────────
async function renderCard({ label, mime, extraBadge, id, uri, isManifest }) {
  const svg = await renderQrSvg(uri);
  const mimeBadge = `<span class="qr-badge">${escHtml(mime)}</span>`;
  const extra = extraBadge ? ` <span class="qr-badge compress">${escHtml(extraBadge)}</span>` : '';
  return `
    <div class="qr-card${isManifest ? ' manifest' : ''}">
      ${svg}
      <div class="qr-label">${escHtml(label)}</div>
      <div>${mimeBadge}${extra}</div>
      <div class="qr-id">${id}</div>
      <div class="qr-uri"><input type="text" readonly value="${escHtml(uri)}"></div>
      <button class="copy-btn" data-uri="${escHtml(uri)}">Copy URI</button>
    </div>`;
}

// ── Main ──────────────────────────────────────────────────────────────────
async function main() {
  // Standalone examples
  let standaloneHtml = '';
  for (const ex of STANDALONE_EXAMPLES) {
    const rawBytes = Buffer.from(ex.content, 'utf8');
    const encoded = encodeSingle({
      hint: ex.hint, filename: ex.filename,
      mimeType: ex.mimeType, rawBytes, compress: ex.compress,
    });
    standaloneHtml += await renderCard({
      label: ex.label,
      mime: ex.mimeType,
      extraBadge: ex.compress ? 'deflate' : null,
      id: toHex(encoded.cacheId),
      uri: encoded.uri,
      isManifest: false,
    });
  }

  // Paper set
  const p = PAPER_EXAMPLE;
  const encodedFiles = p.files.map(f => {
    const rawBytes = Buffer.from(f.content, 'utf8');
    const enc = encodeSingle({ hint: null, filename: f.slug, mimeType: f.mimeType, rawBytes, compress: f.compress });
    return { slug: f.slug, mimeType: f.mimeType, uri: enc.uri, fileId: enc.cacheId, compress: f.compress };
  });
  const manifestFiles = encodedFiles.map(f => ({ slug: f.slug, mimeType: f.mimeType, fileId: f.fileId }));
  const manifestEnc = encodePaperManifest({ label: p.label, set: p.set, slug: p.slug, files: manifestFiles, related: [] });

  const rootHashHex = toHex(manifestEnc.rootHash);
  const rootHashB45 = base45Encode(manifestEnc.rootHash);

  let paperHtml = await renderCard({
    label: p.label,
    mime: 'paper manifest',
    extraBadge: null,
    id: rootHashHex,
    uri: manifestEnc.uri,
    isManifest: true,
  });
  for (const f of encodedFiles) {
    paperHtml += await renderCard({
      label: f.slug,
      mime: f.mimeType,
      extraBadge: f.compress ? 'deflate' : null,
      id: toHex(f.fileId),
      uri: f.uri,
      isManifest: false,
    });
  }

  const paperInfoHtml = `
    <div class="paper-info">
      <strong>${escHtml(p.label)}</strong>
      — set: <code>${escHtml(p.set)}</code>, slug: <code>${escHtml(p.slug)}</code><br>
      Root hash: <code>${rootHashHex}</code><br>
      Navigation link base: <code>tagdrop://${escHtml(rootHashB45)}/${escHtml(p.slug)}</code>
      <div class="step">① Scan <strong>manifest</strong> first — it registers the paper and its file list.</div>
      <div class="step">② Scan each <strong>file</strong> QR in any order to cache its content.</div>
    </div>`;

  const html = renderPage({ standaloneHtml, paperInfoHtml, paperHtml });
  writeFileSync(join(__dirname, 'index.html'), html);
  console.log('Wrote tools/examples/index.html');
}

function renderPage({ standaloneHtml, paperInfoHtml, paperHtml }) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TagDrop — Example Payloads</title>
<!--
  GENERATED FILE — do not edit by hand.
  Run \`npm install && npm run build\` in tools/examples/ to regenerate
  (edits go in tools/examples/generate.mjs).

  Pre-generated example TagDrop QR codes for testing the Android app and web
  reader. Open this file directly in a browser — fully static, no internet
  or JS APIs (crypto/compression) required.
-->
<style>
  *, *::before, *::after { box-sizing: border-box; }
  body   { font-family: system-ui, sans-serif; margin: 0; background: #f5f5f5; color: #222; }
  header { background: #1a1a2e; color: #fff; padding: 16px 24px; }
  header h1    { margin: 0; font-size: 1.4rem; }
  header small { color: #aaa; font-size: 0.82rem; }
  header nav   { margin-top: 8px; font-size: 0.82rem; }
  header nav a { color: #7eb8f7; }

  .container { max-width: 960px; margin: 0 auto; padding: 24px; }

  section { margin-bottom: 40px; }
  h2      { font-size: 1.1rem; margin: 0 0 4px; color: #1a1a2e; border-bottom: 2px solid #1a1a2e; padding-bottom: 6px; }
  .section-desc { font-size: 0.85rem; color: #555; margin: 8px 0 16px; }
  .section-desc a { color: #1a1a2e; }

  /* QR grid */
  .qr-grid { display: flex; flex-wrap: wrap; gap: 16px; }

  .qr-card {
    background: #fff; border-radius: 8px; padding: 16px;
    box-shadow: 0 1px 3px rgba(0,0,0,.1);
    width: 220px; display: flex; flex-direction: column; align-items: center;
  }
  .qr-card svg { display: block; margin-bottom: 10px; max-width: 100%; height: auto; }
  .qr-card .qr-label { font-weight: 600; font-size: 0.9rem; text-align: center; word-break: break-all; }
  .qr-card .qr-badge {
    display: inline-block; margin: 4px 0; padding: 2px 8px;
    border-radius: 12px; font-size: 0.7rem; background: #e8eaf6; color: #3949ab;
  }
  .qr-card .qr-badge.compress { background: #e8f5e9; color: #2e7d32; }
  .qr-card .qr-id   { font-family: monospace; font-size: 0.65rem; color: #aaa; margin-top: 4px; word-break: break-all; }
  .qr-card .qr-uri  { margin-top: 8px; width: 100%; }
  .qr-card .qr-uri input {
    width: 100%; font-family: monospace; font-size: 0.6rem; color: #888;
    border: 1px solid #e0e0e0; border-radius: 4px; padding: 4px 6px;
    background: #fafafa; cursor: text;
  }
  .qr-card .copy-btn {
    margin-top: 4px; width: 100%; padding: 4px; font-size: 0.75rem;
    border: 1px solid #ccc; border-radius: 4px; background: #fff; cursor: pointer;
  }
  .qr-card .copy-btn:hover { background: #f0f0f0; }

  /* Manifest card highlight */
  .qr-card.manifest { border: 2px solid #1a1a2e; }
  .qr-card.manifest .qr-label::before { content: "📋 "; }

  /* paper info box */
  .paper-info {
    background: #fff; border-radius: 8px; padding: 14px 16px; margin-bottom: 16px;
    box-shadow: 0 1px 3px rgba(0,0,0,.1); font-size: 0.85rem;
  }
  .paper-info strong { color: #1a1a2e; }
  .paper-info code { background: #f0f0f0; padding: 2px 5px; border-radius: 3px; font-size: 0.8rem; }
  .paper-info .step { margin-top: 6px; }
</style>
</head>
<body>

<header>
  <h1>TagDrop — Example Payloads</h1>
  <small>Pre-generated QR codes for testing the app and web reader</small>
  <nav>
    <a href="../generator/index.html">Generator</a> ·
    <a href="../reader/index.html">Web Reader</a>
  </nav>
</header>

<div class="container">

  <section id="sec-standalone">
    <h2>Standalone QR Codes</h2>
    <p class="section-desc">
      Each QR code is a self-contained payload — no other codes needed.
      Scan any one with the <a href="../reader/index.html">web reader</a> or the Android app.
    </p>
    <div class="qr-grid">${standaloneHtml}
    </div>
  </section>

  <section id="sec-paper">
    <h2>Paper Set (Multi-file)</h2>
    <p class="section-desc">
      A paper groups multiple files under one manifest.
      <strong>Scan the manifest QR first</strong>, then the file QRs in any order.
    </p>
    ${paperInfoHtml}
    <div class="qr-grid">${paperHtml}
    </div>
  </section>

</div>

<script>
'use strict';
document.querySelectorAll('.copy-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    navigator.clipboard.writeText(btn.dataset.uri).then(() => {
      const orig = btn.textContent;
      btn.textContent = 'Copied!';
      setTimeout(() => { btn.textContent = orig; }, 1500);
    });
  });
});
</script>
</body>
</html>
`;
}

main();
