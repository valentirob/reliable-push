import { fallbackCircuits, overview } from '../api.js';
import { clear, el, fmtAge, fmtRate, fmtTime, labelled, link, message, option } from '../dom.js';

const REFRESH_MS = 30000;

function tile(label, value, sub, bad) {
  return el('div', { class: 'tile' + (bad ? ' bad' : '') },
    el('div', { class: 'label', text: label }),
    el('div', { class: 'value', text: value }),
    sub ? el('div', { class: 'sub', text: sub }) : null);
}

function badgeText(c) {
  if (c.state === 'OPEN') return 'OPEN since ' + fmtTime(c.since) + ' — ' + c.suppressed + ' suppressed';
  if (c.state === 'FORCED') return 'FORCED until ' + fmtTime(c.forcedUntil);
  return 'CLOSED';
}

function circuitBadge(c) {
  if (!c) return null;
  return el('div', { class: 'circuit-badge state-' + String(c.state).replace(/[^A-Z_]/g, '') },
    'Fallback circuit: ', link('#/circuit/' + encodeURIComponent(c.appId), badgeText(c)));
}

function appBlock(o, showName, first, circuit) {
  const n = o.now;
  const amber = n.oldestDueAgeSeconds !== null && n.oldestDueAgeSeconds > 60;
  return el('section', { class: 'card', 'data-app': o.appId },
    showName ? el('h2', { text: o.appId }) : null,
    circuitBadge(circuit),
    el('div', { class: 'tiles' },
      tile('Registered', String(o.registered), o.cancelled + ' cancelled, ' + o.noDeviceRegistered + ' without device'),
      tile('Delivery rate', fmtRate(o.deliveryRate), o.delivered + ' delivered'),
      tile('Read rate', fmtRate(o.readRate), o.read + ' read'),
      tile('Fallbacks triggered', String(o.fallbackTriggered), fmtRate(o.fallbackRate)),
      tile('Fallbacks failed', String(o.fallbackFailed), null, o.fallbackFailed > 0),
      tile('Fallbacks expired', String(o.fallbackExpired), null, o.fallbackExpired > 0)),
    el('div', { id: first ? 'backlog' : null, class: 'backlog' + (amber ? ' amber' : ''), role: 'group', 'aria-label': 'Backlog now' },
      el('span', {}, 'Pending ', el('b', { text: n.pending })),
      el('span', {}, 'Due, unclaimed ', el('b', { text: n.dueUnclaimed })),
      el('span', {}, 'Dispatching ', el('b', { text: n.dispatching })),
      el('span', {}, 'Oldest due ', el('b', { text: fmtAge(n.oldestDueAgeSeconds) }),
        amber ? ' (over 1 min)' : '')));
}

export function render(root, ctx) {
  const appSel = el('select', { id: 'app' }, option('', 'All apps'), ctx.me.apps.map((a) => option(a)));
  const winSel = el('select', { id: 'window' }, option('1h', '1 hour'), option('24h', '24 hours'), option('7d', '7 days'));
  winSel.value = '24h';
  const pause = el('button', { id: 'pause', type: 'button', 'aria-pressed': 'false', text: 'Pause refresh' });
  const stamp = el('span', { class: 'muted', id: 'updated' });
  const tiles = el('div', { id: 'tiles' });
  const view = el('div', {},
    el('div', { class: 'row' }, labelled('App', appSel), labelled('Window', winSel), pause, stamp),
    tiles);
  root.append(view);

  let paused = false;
  let dead = false;
  let timer = null;
  let seq = 0;

  async function load() {
    const mine = ++seq;
    try {
      const [body, circuits] = await Promise.all([
        overview(appSel.value, winSel.value),
        // the badges are extra: the overview still works if the circuit route fails, and a 503 is not retried (the
        // backoff would hold back the tiles); the next refresh tries again
        fallbackCircuits({ noRetry503: true }).catch((e) => (e.status === 401 ? Promise.reject(e) : null))]);
      if (dead || mine !== seq) return;
      clear(tiles);
      if (circuits && circuits.suspended) {
        tiles.append(el('p', { class: 'circuit-suspended', role: 'status', text: 'Fallbacks suspended by configuration' }));
      } else if (circuits && !circuits.enabled) {
        tiles.append(el('p', { class: 'muted circuit-disabled', text: 'Circuit breaker disabled' }));
      }
      const byApp = new Map(circuits ? circuits.items.map((c) => [c.appId, c]) : []);
      if (!body.items.length) tiles.append(message('muted', 'No apps visible to this key.'));
      body.items.forEach((o, i) => tiles.append(appBlock(o, body.items.length > 1 || !appSel.value, i === 0, circuits && circuits.enabled ? byApp.get(o.appId) : null)));
      stamp.textContent = 'Updated ' + new Date().toLocaleTimeString([], { hour12: false });
    } catch (e) {
      if (dead || mine !== seq || e.status === 401) return;
      clear(tiles);
      tiles.append(message('error', e.status === 404 ? 'App not found.' : 'Could not load the overview: ' + e.message));
    }
  }

  function schedule() {
    clearInterval(timer);
    timer = paused ? null : setInterval(load, REFRESH_MS);
  }

  appSel.addEventListener('change', load);
  winSel.addEventListener('change', load);
  pause.addEventListener('click', () => {
    paused = !paused;
    pause.setAttribute('aria-pressed', String(paused));
    pause.textContent = paused ? 'Resume refresh' : 'Pause refresh';
    schedule();
  });
  load();
  schedule();
  return () => { dead = true; clearInterval(timer); };
}
