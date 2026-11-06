import { api, el } from './api.js';
import * as devices from './views/devices.js';
import * as send from './views/send.js';
import * as tracking from './views/tracking.js';
import * as inbox from './views/inbox.js';
import * as scenarios from './views/scenarios.js';
import * as logs from './views/logs.js';

const SCREENS = [
  ['devices', 'Devices', devices], ['send', 'Send push', send], ['tracking', 'Live tracking', tracking],
  ['inbox', 'Fallback inbox', inbox], ['scenarios', 'Scenarios', scenarios], ['logs', 'Logs', logs],
];

// SSE events fan out through a bus; views subscribe on mount and unsubscribe on unmount.
// EventSource gives up (CLOSED) on a non-200 answer such as the 503 of the 21st tab: reconnect ourselves with
// exponential backoff + jitter, and ask the views to resync after every (re)connect (events may have been missed).
const bus = new EventTarget();
const live = el('span', { class: 'muted', role: 'status', text: 'Live updates: connecting…' });
let attempt = 0;
function setLive(ok) {
  live.textContent = ok ? 'Live updates: connected' : 'Live updates disconnected — retrying';
  live.className = ok ? 'ok' : 'error';
}
function connectStream() {
  const es = new EventSource('/console/api/stream');
  es.onopen = () => {
    attempt = 0;
    setLive(true);
    bus.dispatchEvent(new CustomEvent('resync'));
  };
  es.onmessage = (m) => {
    try { bus.dispatchEvent(new CustomEvent('event', { detail: JSON.parse(m.data) })); } catch (e) { /* ignore */ }
  };
  es.onerror = () => {
    setLive(false);
    if (es.readyState === EventSource.CLOSED) {
      es.close();
      const delay = Math.min(30000, 1000 * 2 ** attempt++) * (0.5 + Math.random() / 2);
      setTimeout(connectStream, delay);
    }
  };
}

async function main() {
  const nav = document.getElementById('nav');
  const main = document.getElementById('main');
  let apps = [];
  try { apps = await api('/apps'); } catch (e) { main.append(el('p', { class: 'error', role: 'alert', text: e.message })); return; }
  document.querySelector('header').append(live);
  const ctx = { apps, bus };
  let current = null;
  const buttons = new Map();

  function show(id) {
    if (current && current.unmount) current.unmount();
    for (const [sid, b] of buttons) { if (sid === id) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current'); }
    const screen = SCREENS.find((s) => s[0] === id) || SCREENS[0];
    main.replaceChildren();
    const section = el('section', { 'aria-labelledby': 'screen-title' }, el('h2', { id: 'screen-title', text: screen[1] }));
    main.append(section);
    current = screen[2].mount(section, ctx) || {};
    if (location.hash !== '#' + screen[0]) history.replaceState(null, '', '#' + screen[0]);
  }
  for (const [id, label] of SCREENS) {
    const b = el('button', { type: 'button', onclick: () => show(id), text: label });
    buttons.set(id, b);
    nav.append(b);
  }
  connectStream();
  show(location.hash.slice(1));
}
main();
