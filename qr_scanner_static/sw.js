const CACHE = 'qr-scanner-v1';
const ASSETS = [
  '/',
  '/index.html',
  '/scanner.js',
  '/manifest.webmanifest'
];

self.addEventListener('install', (e)=>{
  e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)));
  self.skipWaiting();
});

self.addEventListener('activate', (e)=>{
  e.waitUntil(
    caches.keys().then(keys => Promise.all(keys.map(k => k !== CACHE ? caches.delete(k) : null)))
  );
  self.clients.claim();
});

self.addEventListener('fetch', (e)=>{
  const url = new URL(e.request.url);
  // App shell cache-first
  if (ASSETS.includes(url.pathname) || (url.pathname === '/' && e.request.mode === 'navigate')) {
    e.respondWith(caches.match(e.request).then(r => r || fetch(e.request)));
    return;
  }
  // Default: network-first then fallback
  e.respondWith(fetch(e.request).catch(()=>caches.match(e.request)));
});
