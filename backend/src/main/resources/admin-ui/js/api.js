// Admin API client. The key lives in sessionStorage (this file only) and travels only in the X-Admin-Key header.
const KEY = 'rp-admin-key';
const MAX_RETRIES = 4;
let onUnauthorized = () => {};

export class ApiError extends Error {
  constructor(status, title, detail) {
    super(detail || title || 'HTTP ' + status);
    this.status = status;
    this.title = title;
  }
}

function store(op, key, value) {
  try {
    if (op === 'get') return sessionStorage.getItem(key);
    if (op === 'set') sessionStorage.setItem(key, value);
    else sessionStorage.removeItem(key);
  } catch (e) { /* storage blocked: the page then works for this load only */ }
  return null;
}

let memoryKey = null;
export function getKey() { return store('get', KEY) || memoryKey; }
export function hasKey() { return !!getKey(); }
export function clearKey() { memoryKey = null; store('del', KEY); }
function setKey(key) { memoryKey = key; store('set', KEY, key); }
export function setUnauthorizedHandler(fn) { onUnauthorized = fn; }

export function banner(text) {
  const node = document.getElementById('banner');
  if (!node) return;
  node.textContent = text || '';
  node.hidden = !text;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function queryString(params) {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params || {})) if (v !== undefined && v !== null && v !== '') p.set(k, v);
  const s = p.toString();
  return s ? '?' + s : '';
}

async function problem(res) {
  try {
    const body = await res.json();
    return new ApiError(res.status, body.title, body.detail);
  } catch (e) {
    return new ApiError(res.status, 'HTTP ' + res.status);
  }
}

export async function request(path, params, opts = {}) {
  const url = '/v1/admin' + path + queryString(params);
  for (let attempt = 0; ; attempt++) {
    let res;
    try {
      res = await fetch(url, { headers: { 'X-Admin-Key': getKey() || '', Accept: 'application/json' }, cache: 'no-store' });
    } catch (e) {
      if (attempt >= MAX_RETRIES) throw new ApiError(0, 'Cannot reach the server');
      banner('Cannot reach the server, retrying…');
      await sleep(1000 * 2 ** attempt);
      continue;
    }
    if (res.status === 401) {
      banner('');
      if (!opts.quiet401) { clearKey(); onUnauthorized(); }
      throw new ApiError(401, 'Unauthorized', 'The admin key was not accepted.');
    }
    if (res.status === 429 && attempt < MAX_RETRIES) {
      const wait = Math.min(Math.max(parseInt(res.headers.get('Retry-After'), 10) || 2, 1), 60);
      banner('Too many requests, retrying in ' + wait + ' s…');
      await sleep(wait * 1000);
      continue;
    }
    if (res.status === 503 && attempt < MAX_RETRIES) {
      banner('Service unavailable, retrying…');
      await sleep(1000 * 2 ** attempt);
      continue;
    }
    banner('');
    if (!res.ok) throw await problem(res);
    return res.json();
  }
}

/** Validates a key against /me and, if accepted, remembers it for this tab. Returns the /me body. */
export async function signIn(key) {
  const previous = getKey();
  setKey(key);
  try {
    return await request('/me', null, { quiet401: true });
  } catch (e) {
    if (previous) setKey(previous); else clearKey();
    throw e;
  }
}

export const me = () => request('/me');
export const overview = (app, window) => request('/overview', { app, window });
export const dailyStats = (app, from, to) => request('/stats/daily', { app, from, to });
export const searchNotifications = (params) => request('/notifications', params);
export const notification = (id) => request('/notifications/' + encodeURIComponent(id));
export const payload = (id) => request('/notifications/' + encodeURIComponent(id) + '/payload');
export const devices = (app, userId) => request('/devices', { app, userId });
