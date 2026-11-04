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
const bus = new EventTarget();
function connectStream() {
  const es = new EventSource('/console/api/stream');
  es.onmessage = (m) => {
    try { bus.dispatchEvent(new CustomEvent('event', { detail: JSON.parse(m.data) })); } catch (e) { /* ignore */ }
  };
  es.onerror = () => bus.dispatchEvent(new CustomEvent('stream-state', { detail: es.readyState }));
  es.onopen = () => bus.dispatchEvent(new CustomEvent('stream-state', { detail: 1 }));
}

async function main() {
  const nav = document.getElementById('nav');
  const main = document.getElementById('main');
  let apps = [];
  try { apps = await api('/apps'); } catch (e) { main.append(el('p', { class: 'error', role: 'alert', text: e.message })); return; }
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
