// Hash router: #/overview, #/trends, #/search, #/n/<trackingId>, #/devices/<app>/<userId>, #/circuit/<app>.
import { clearKey, hasKey, me, setUnauthorizedHandler, banner } from './api.js';
import { clear, el, message } from './dom.js';
import * as signin from './views/signin.js';
import * as overview from './views/overview.js';
import * as trends from './views/trends.js';
import * as search from './views/search.js';
import * as notification from './views/notification.js';
import * as devices from './views/devices.js';
import * as circuit from './views/circuit.js';

const root = document.getElementById('view');
const nav = document.getElementById('nav');
const signout = document.getElementById('signout');
const whoami = document.getElementById('whoami');
const VIEWS = { 'overview': overview, 'trends': trends, 'search': search, 'n': notification, 'devices': devices, 'circuit': circuit };
let session = null;
let cleanup = null;
let pendingHash = '';

function stop() {
  if (cleanup) cleanup();
  cleanup = null;
}

function parse() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, query] = raw.split('?');
  const segments = path.split('/').filter(Boolean).map((s) => decodeURIComponent(s));
  return { route: segments[0] || 'overview', args: segments.slice(1), query: new URLSearchParams(query || '') };
}

function showSignedOut() {
  stop();
  session = null;
  nav.hidden = true;
  signout.hidden = true;
  whoami.textContent = '';
  clear(root);
  banner('');
  cleanup = signin.render(root, { onSignedIn });
}

async function onSignedIn(meBody) {
  session = meBody;
  whoami.textContent = meBody.keyId;
  nav.hidden = false;
  signout.hidden = false;
  if (!location.hash || location.hash === '#/' || location.hash === '#') location.hash = pendingHash || '#/overview';
  route();
}

function route() {
  if (!session) return;
  stop();
  const { route: name, args, query } = parse();
  const view = VIEWS[name];
  for (const a of nav.querySelectorAll('a')) {
    if (a.dataset.route === name) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  }
  clear(root);
  if (!view) {
    root.append(el('section', { class: 'card' }, message('error', 'Unknown page.')));
    return;
  }
  cleanup = view.render(root, { me: session, args, query }) || null;
}

signout.addEventListener('click', () => {
  clearKey();
  location.hash = '';
  showSignedOut();
});
setUnauthorizedHandler(() => {
  pendingHash = location.hash;
  showSignedOut();
});
window.addEventListener('hashchange', route);

pendingHash = location.hash;
if (hasKey()) {
  me().then(onSignedIn).catch(() => { if (!session) showSignedOut(); });
} else {
  showSignedOut();
}
