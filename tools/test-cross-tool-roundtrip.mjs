// Cross-tool round-trip test: drives the REAL tools/generator/index.html encode functions
// into the REAL tools/reader/index.html decode functions, in the same Node process.
//
// Why this exists (see CLAUDE.md's "Two parallel wire-format implementations" /
// version-13/14/15 history entries for the full account): generator/index.html,
// examples/index.html, and reader/index.html are three separately-duplicated codec
// copies (deliberate — see CLAUDE.md's "Known duplication" note), and test-qdef-
// roundtrip.mjs is a FOURTH, independent Node reimplementation used for CI. None of
// those self-consistency checks — nor qdef-lint.cjs, nor qdef-format's own headless
// validator — ever actually call the real generator's own functions and feed the
// result into the real reader's own functions. That gap let a real, severe bug ship
// to `master` undetected: generator/index.html's buildContentExtension/
// buildContentSignature/buildPaperPreview/buildPaperBody calls were passing a stale
// 5-argument form to cborRecord after its signature shrank to 4 parameters (namespace
// moved from the 5th argument to the 4th) — the namespace argument silently landed
// nowhere, every real generator-produced code shipped with no namespace cascade marker
// at all, and the reader's own recordScanResult security check (SPEC.md §2.1a) rejected
// every one of them as "unsupported code." Every existing test passed clean throughout;
// only scanning a real generator-produced code with the real Android app caught it.
//
// This test closes that gap by loading generator/index.html and reader/index.html as
// whole HTML documents in jsdom, with runScripts: 'dangerously' so their real inline
// <script> tags actually execute against a real (virtual) DOM, and calling their real
// top-level functions (createContentSectors/createPaper/createKeyCodeSector on the
// encode side, recordScanResult/RecordAssembler on the decode side) directly — the
// same functions a real button click or a real scan would reach.
//
// Approach notes (jsdom over hand-rolled vm+DOM-stubs, confirmed while building this):
//   - Loading the WHOLE file (not just the extracted <script> text) means top-level
//     DOM-touching statements (event listener wiring, `document.getElementById(...).value
//     = ...`, etc. — both files have plenty, right at the bottom) resolve against real
//     elements instead of needing them hand-stubbed one at a time.
//   - `resources: undefined` (no eager resource loading) means jsdom never fetches the
//     external CDN <script src="...zxing-wasm..."> tag in reader/index.html, or the
//     favicon, or anything else — the CDN-hosted signing (@noble/post-quantum) and
//     QR-rendering (qrcode/zxing-wasm) libraries this test doesn't need are never
//     touched, since we call the codec functions directly rather than simulating a
//     button click that would dynamically import() them.
//   - `window.fetch` is stubbed to throw (loudly, not silently) so any accidental
//     network-touching code path fails fast instead of hanging or hitting the real
//     network.
//   - A handful of browser globals jsdom doesn't implement (or copy into the page's
//     own separate window realm) are stubbed in beforeParse: `ZXingWASM` and
//     `indexedDB` (things the reader touches at page-load time that this test doesn't
//     otherwise exercise — `indexedDB` gets a real, working implementation via
//     fake-indexeddb rather than a throwing stub, since the reader's top-level
//     history-loading code calls it unconditionally on load), `matchMedia` (an
//     install-prompt IIFE), and `crypto.subtle`/`CompressionStream`/
//     `DecompressionStream` (present on Node's own global scope per this environment's
//     Node 22, but not automatically present on jsdom's separate window object —
//     patched through from Node's own implementations).
//   - Two of the reader's top-level declarations the tests need are `class`/`let`, not
//     `function` — per ordinary JS semantics, a top-level `function` declaration in a
//     classic script becomes BOTH a global lexical binding AND a `window` property, but
//     `class`/`let`/`const` become only the former. `RecordAssembler` is bridged out via
//     `window.eval('RecordAssembler')` (indirect eval, which still resolves against the
//     shared global lexical environment) rather than `window.RecordAssembler` (undefined).
//   - Byte buffers must never cross the two jsdom windows' realm boundary as-is — each
//     window has its own separate Uint8Array constructor, and both codecs do `instanceof
//     Uint8Array` checks extensively. Passing a foreign-realm typed array in doesn't
//     throw — it silently mis-encodes/mis-decodes instead (confirmed the hard way while
//     building this test: a foreign-realm Uint8Array passed as `content` silently encoded
//     as an unrelated small integer instead of the actual bytes). crossBytes() below
//     copies into the target window's own Uint8Array before every cross-realm call.

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { indexedDB, IDBKeyRange } from 'fake-indexeddb';

const GENERATOR_PATH = new URL('./generator/index.html', import.meta.url);
const READER_PATH = new URL('./reader/index.html', import.meta.url);

function loadWindow(path, label, extraStubs) {
  const html = readFileSync(path, 'utf8');
  const dom = new JSDOM(html, {
    url: `https://example.invalid/${label}/index.html`,
    runScripts: 'dangerously',
    resources: undefined,
    pretendToBeVisual: true,
    beforeParse(window) {
      window.fetch = (...args) => { throw new Error(`unexpected network fetch in ${label}: ${args[0]}`); };
      if (!window.crypto.subtle) window.crypto.subtle = webcrypto.subtle;
      window.CompressionStream ??= globalThis.CompressionStream;
      window.DecompressionStream ??= globalThis.DecompressionStream;
      extraStubs?.(window);
    },
  });
  return dom.window;
}

// A fresh microtask tick after construction, so any (stubbed, non-network) async
// top-level page-load work settles before the test starts calling functions.
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

async function loadGeneratorWindow() {
  const window = loadWindow(GENERATOR_PATH, 'generator');
  await settle();
  return window;
}

async function loadReaderWindow() {
  const window = loadWindow(READER_PATH, 'reader', (w) => {
    w.ZXingWASM = {
      prepareZXingModule() {},
      readBarcodes() { throw new Error('unexpected zxing-wasm decode call — this test never scans an image'); },
    };
    w.matchMedia ??= () => ({ matches: false, addListener() {}, removeListener() {} });
    w.indexedDB = indexedDB;
    w.IDBKeyRange = IDBKeyRange;
  });
  await settle();
  return window;
}

/** Copies bytes into `targetWindow`'s own Uint8Array — required before passing them into
 *  any function running in that window (see the header comment above). */
function crossBytes(bytes, targetWindow) {
  const copy = new targetWindow.Uint8Array(bytes.length);
  copy.set(bytes);
  return copy;
}

/** RecordAssembler is `class RecordAssembler {...}` at reader/index.html's top level — a
 *  global lexical binding, not a `window` property (see header comment). */
function getRecordAssemblerClass(readerWindow) {
  return readerWindow.eval('RecordAssembler');
}

// ── Tests ──
// Each test builds real wire bytes with the real generator, hands them to the real
// reader, and asserts the decode SUCCEEDS (not null/undefined/rejected) as the primary
// check — this is the exact assertion that would have caught the historical bug
// described above (a missing namespace cascade makes recordScanResult's own security
// check reject the code outright, well before any field-level comparison would run).

async function testSingleCodeContent() {
  const gen = await loadGeneratorWindow();
  const reader = await loadReaderWindow();

  const contentText = '<p>hello from the cross-tool test</p>';
  const content = new gen.TextEncoder().encode(contentText);
  const build = await gen.createContentSectors({
    hint: 'cross-tool single code', mimeType: 'text/html', rawBytes: content, compress: false,
  });
  assert.equal(build.codes.length, 1, 'small content must fit one code');

  const bytes = crossBytes(build.codes[0].raw, reader);
  const scan = reader.recordScanResult(bytes);
  assert.equal(scan.type, 'record', 'the reader must recognize a real generator-produced code');
  assert.equal(scan.kind, 'content');

  const RecordAssembler = getRecordAssemblerClass(reader);
  const state = await new RecordAssembler().add(scan);
  assert.equal(state.kind, 'ContentReady', 'a single-code Content payload must decode straight to ContentReady');
  assert.equal(state.hint, 'cross-tool single code');
  assert.equal(state.mimeType, 'text/html');
  assert.equal(Buffer.from(state.content).toString(), contentText, 'decoded content bytes must match what was encoded');
  // The wire contentHash is a multihash (1-byte sha2-256 prefix + 8-byte digest, SPEC.md
  // §3.1a); createContentSectors' own cacheId is the bare 8-byte digest — cross-check
  // the reader recovered the same identity.
  assert.equal(state.cacheId, Buffer.from(build.cacheId).toString('hex'));

  // Also exercise the tagdrop: URI carrier (Base41 encode/decode), not just raw bytes —
  // encodeCode/parseUri are the real per-carrier entry points a QR scan or pasted URI
  // would actually use.
  const uri = gen.encodeCode(build.codes[0]).uri;
  assert.ok(uri.startsWith('tagdrop:'), 'encodeCode must produce a tagdrop: URI');
  const uriScan = await reader.parseUri(uri);
  assert.equal(uriScan.type, 'record', 'the reader must also accept the same code via its tagdrop: URI form');
  const uriState = await new RecordAssembler().add(uriScan);
  assert.equal(uriState.kind, 'ContentReady');
  assert.equal(Buffer.from(uriState.content).toString(), contentText);

  return { name: 'single-code-content' };
}

async function testMultiCodeContentSplit() {
  const gen = await loadGeneratorWindow();
  const reader = await loadReaderWindow();

  // Deliberately large and repetitive (so DEFLATE compresses it well below the forced
  // per-code threshold regardless), forcing a genuine multi-fragment Split — mirrors
  // test-qdef-roundtrip.mjs's own testMultiCodeContentSplitCompressParity.
  const contentText = 'x'.repeat(5000);
  const content = new gen.TextEncoder().encode(contentText);
  const build = await gen.createContentSectors({
    hint: 'cross-tool split content', mimeType: 'text/plain', rawBytes: content,
    compress: true, maxSectorDataBytes: 10,
  });
  assert.ok(build.codes.length > 1, 'large content must split across multiple codes');

  const RecordAssembler = getRecordAssemblerClass(reader);
  const assembler = new RecordAssembler();
  let lastState;
  // Scanned in shuffled (reversed) order — reassembly must not depend on scan order
  // (SPEC.md §5.1: each code is independently parsed, no shared state assumed).
  const shuffled = [...build.codes].reverse();
  for (const code of shuffled) {
    const bytes = crossBytes(code.raw, reader);
    const scan = reader.recordScanResult(bytes);
    assert.equal(scan.type, 'record', 'every fragment code must independently decode as a real Record');
    lastState = await assembler.add(scan);
  }
  assert.equal(lastState.kind, 'ContentReady', 'a fully-reassembled multi-code Content payload must reach ContentReady');
  assert.equal(Buffer.from(lastState.content).toString(), contentText,
    'reassembled + decompressed content must match the original exactly');

  return { name: 'multi-code-content-split', codeCount: build.codes.length };
}

async function testSingleCodePaper() {
  const gen = await loadGeneratorWindow();
  const reader = await loadReaderWindow();

  const fileId = crossBytes(new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]), gen);
  const build = await gen.createPaper({
    label: 'Cross-tool test paper',
    files: [{ slug: 'index.html', mimeType: 'text/html', fileId, description: 'home page' }],
    related: [{ hint: 'a related drop', set: 'trailhead', slug: 'related.html' }],
    compress: false,
  });
  assert.equal(build.codes.length, 1, 'a small paper must fit one code');

  const bytes = crossBytes(build.codes[0].raw, reader);
  const scan = reader.recordScanResult(bytes);
  assert.equal(scan.type, 'record', 'the reader must recognize a real generator-produced paper code');
  assert.equal(scan.kind, 'paper');

  const RecordAssembler = getRecordAssemblerClass(reader);
  const state = await new RecordAssembler().add(scan);
  assert.equal(state.kind, 'PaperReady', 'a single-code Paper must decode straight to PaperReady');
  assert.equal(state.paper.label, 'Cross-tool test paper');
  assert.equal(state.paper.files.length, 1);
  assert.equal(state.paper.files[0].slug, 'index.html');
  assert.equal(state.paper.files[0].mimeType, 'text/html');
  assert.equal(state.paper.files[0].fileId, Buffer.from([1, 2, 3, 4, 5, 6, 7, 8]).toString('hex'));
  assert.equal(state.paper.related.length, 1);
  assert.equal(state.paper.related[0].hint, 'a related drop');
  assert.equal(state.paper.related[0].slug, 'related.html');

  return { name: 'single-code-paper' };
}

async function testKeyOnlyCode() {
  const gen = await loadGeneratorWindow();
  const reader = await loadReaderWindow();

  const keyMaterial = crossBytes(new Uint8Array(16).fill(0x42), gen);
  const code = gen.createKeyCodeSector({ keyMaterial, hint: 'cross-tool key-only code' });

  const bytes = crossBytes(code.raw, reader);
  const scan = reader.recordScanResult(bytes);
  assert.equal(scan.type, 'record', 'a real generator-produced key-only code must decode');
  assert.equal(scan.kind, 'content');

  const RecordAssembler = getRecordAssemblerClass(reader);
  const state = await new RecordAssembler().add(scan);
  assert.equal(state.kind, 'ContentReady', 'a key-only code decodes straight to ContentReady, carrying no content');
  assert.equal(state.hint, 'cross-tool key-only code');
  assert.equal(Buffer.from(state.keyMaterial).toString('hex'), '42'.repeat(16));
  assert.equal(state.content.length, 0);

  return { name: 'key-only-code' };
}

async function main() {
  console.log('Cross-tool round-trip test: real generator/index.html -> real reader/index.html\n');
  const results = [];
  for (const test of [testSingleCodeContent, testMultiCodeContentSplit, testSingleCodePaper, testKeyOnlyCode]) {
    try {
      const result = await test();
      console.log(`  PASS  ${result.name}` + (result.codeCount ? ` (${result.codeCount} codes)` : ''));
      results.push(result);
    } catch (err) {
      console.error(`  FAIL  ${test.name}: ${err.stack || err.message}`);
      process.exitCode = 1;
    }
  }
  if (!process.exitCode) {
    console.log(`\n${results.length}/${results.length} passed.`);
  } else {
    console.log(`\n${results.length}/4 passed — see FAIL lines above.`);
  }
}

main();
