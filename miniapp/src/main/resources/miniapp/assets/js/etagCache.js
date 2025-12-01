import { tgInitData } from './api.js';

const NS = 'etag-cache-v1';

function keyOf(method, url) {
  return `${method} ${url}`;
}

function get(method, url) {
  const raw = sessionStorage.getItem(`${NS}:${keyOf(method, url)}`);
  return raw ? JSON.parse(raw) : null;
}

function put(method, url, etag, payload) {
  sessionStorage.setItem(
    `${NS}:${keyOf(method, url)}`,
    JSON.stringify({ etag, payload, ts: Date.now() }),
  );
}

async function fetchWithFallback(url, opts, cache) {
  const res = await fetch(url, opts);
  if (res.status === 204) return { data: null, cached: false, etag: res.headers.get('ETag') };
  if (res.status === 304) {
    if (cache) return { data: cache.payload, cached: true, etag: cache.etag };
    const headers = new Headers(opts.headers || {});
    headers.delete('If-None-Match');
    const retry = await fetch(url, { ...opts, headers });
    if (!retry.ok) throw new Error(`HTTP ${retry.status}`);
    const payload = await retry.json();
    const etag = retry.headers.get('ETag');
    if (etag) put(opts.method || 'GET', url, etag, payload);
    return { data: payload, cached: false, etag };
  }

  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const payload = await res.json();
  const etag = res.headers.get('ETag');
  if (etag) put(opts.method || 'GET', url, etag, payload);
  return { data: payload, cached: false, etag };
}

export async function fetchJsonCached(url, opts = {}) {
  const method = (opts.method || 'GET').toUpperCase();
  const cache = get(method, url);
  const headers = new Headers(opts.headers || {});
  headers.set('X-Telegram-Init-Data', tgInitData());
  if (cache?.etag) headers.set('If-None-Match', cache.etag);

  const result = await fetchWithFallback(url, { ...opts, method, headers }, cache);
  if (result.etag && (!cache || cache.etag !== result.etag)) {
    put(method, url, result.etag, result.data);
  }
  return result;
}
