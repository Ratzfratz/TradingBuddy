const CACHE = 'warrantcalc-v140';
const SCOPE_URL = new URL(self.registration.scope);
const scopePath = SCOPE_URL.pathname.replace(/\/$/, '');
const appPath = path => `${scopePath}${path}`;
const appUrl = path => new URL(appPath(path), self.location.origin).toString();

const ASSETS = [
  appUrl('/index.html'),
  appUrl('/appwrite-sdk.js'),
  appUrl('/manifest.json'),
  appUrl('/favicon.ico'),
  appUrl('/icons/icon-16.png'),
  appUrl('/icons/icon-32.png'),
  appUrl('/icons/apple-touch-icon.png'),
  appUrl('/icons/icon-192.png'),
  appUrl('/icons/icon-512.png')
];

const INDEX_URL = appUrl('/index.html');
const isAppIndex = url => url.origin === self.location.origin && (
  url.pathname === appPath('/') ||
  url.pathname === appPath('/index.html')
);

self.addEventListener('install', event => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then(cache => cache.addAll(ASSETS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches
      .keys()
      .then(keys =>
        Promise.all(
          keys
            .filter(key => key !== CACHE)
            .map(key => caches.delete(key))
        )
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;

  const url = new URL(event.request.url);
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return;
  if (url.origin !== self.location.origin) return;
  if (event.request.mode === 'navigate' || isAppIndex(url)) {
    event.respondWith(
      fetch(event.request)
        .then(response => {
          if (response.status === 200) {
            const copy = response.clone();
            caches.open(CACHE).then(cache => cache.put(INDEX_URL, copy));
          }
          return response;
        })
        .catch(() => caches.match(event.request).then(response => response || caches.match(INDEX_URL)))
    );
    return;
  }

  event.respondWith(
    caches.match(event.request).then(cachedResponse => {
      if (cachedResponse) return cachedResponse;

      return fetch(event.request)
        .then(response => {
          if (response.status === 200) {
            const copy = response.clone();
            caches.open(CACHE).then(cache => cache.put(event.request, copy));
          }
          return response;
        })
        .catch(() => caches.match(INDEX_URL));
    })
  );
});
