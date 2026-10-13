const CACHE_NAME = 'movie-watchlist-shell-v3';
const SHELL_FILES = [
  '/offline.html',
  '/css/app.css',
  '/images/app-icon.svg',
  '/manifest.webmanifest'
];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE_NAME).then(cache => cache.addAll(SHELL_FILES)));
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys => Promise.all(
      keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key))
    ))
  );
  self.clients.claim();
});

self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;

  if (request.mode === 'navigate') {
    // Local app navigation must always reach the running Spring server. Returning the
    // generic offline screen here left local preview tabs stuck after server restarts.
    if (['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) {
      event.respondWith(fetch(request));
      return;
    }
    event.respondWith(fetch(request).catch(async () => {
      const offline = await caches.match('/offline.html');
      return offline || Response.error();
    }));
    return;
  }

  if (SHELL_FILES.includes(url.pathname) || url.pathname.startsWith('/js/')) {
    event.respondWith(fetch(request).then(response => {
      if (response.ok) caches.open(CACHE_NAME).then(cache => cache.put(request, response.clone()));
      return response;
    }).catch(async () => (await caches.match(request)) || Response.error()));
  }
});
