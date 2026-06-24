// Service worker for TagDrop Reader: caches this page and its CDN dependencies
// (zxing-wasm, marked) so scanning and viewing already-found content keeps working
// fully offline after one normal online visit. Kept in sync by hand with index.html
// — bump CACHE_NAME whenever a pinned CDN URL below changes (e.g. a zxing-wasm or
// marked version bump), so clients drop the old cache instead of serving stale files.
const CACHE_NAME = 'tagdrop-reader-v1';

const PRECACHE_URLS = [
  './',
  './index.html',
  './manifest.json',
  './icon.png',
  'https://cdn.jsdelivr.net/npm/zxing-wasm@3.1.0/dist/iife/reader/index.js',
  'https://cdn.jsdelivr.net/npm/marked@18.0.5/+esm',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => Promise.all(
      PRECACHE_URLS.map((url) =>
        cache.add(url).catch((err) => console.warn('[sw] precache failed:', url, err))
      )
    ))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((names) => Promise.all(
      names.filter((name) => name !== CACHE_NAME).map((name) => caches.delete(name))
    ))
  );
  self.clients.claim();
});

// Cache-first, falling back to network and caching whatever it returns — this is how
// the zxing-wasm .wasm binary gets cached too, even though its exact URL (decided by
// the zxing-wasm script itself) is never hardcoded above.
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  event.respondWith(
    caches.match(event.request).then((cached) => {
      if (cached) return cached;
      return fetch(event.request).then((response) => {
        const copy = response.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy));
        return response;
      });
    })
  );
});
