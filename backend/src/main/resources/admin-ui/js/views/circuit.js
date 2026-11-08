import { circuitEvents, fallbackCircuits, resumeFallbacks } from '../api.js';
import { clear, el, fmtTime, link, message } from '../dom.js';

const REASONS = {
  tracking_missing: 'delivery tracking is missing',
  delivered_resumed: 'deliveries resumed',
  forced: 'forced by an operator',
  force_expired: 'forced period ended',
};

function eventRow(e) {
  return el('tr', {},
    el('td', { text: fmtTime(e.at) }),
    el('td', { text: e.transition }),
    el('td', { text: REASONS[e.reason] ? e.reason + ' (' + REASONS[e.reason] + ')' : e.reason }),
    el('td', { class: 'num', text: e.due === null ? '–' : e.due }),
    el('td', { class: 'num', text: e.ratio === null ? '–' : e.ratio }),
    el('td', { class: 'num', text: e.suppressed === null ? '–' : e.suppressed }),
    el('td', { text: e.adminKeyId || '–' }));
}

function stateText(c) {
  if (c.state === 'OPEN') return 'OPEN since ' + fmtTime(c.since) + ' — ' + c.suppressed + ' suppressed';
  if (c.state === 'FORCED') return 'FORCED until ' + fmtTime(c.forcedUntil) + (c.forcedBy ? ' by ' + c.forcedBy : '');
  return 'CLOSED';
}

export function render(root, ctx) {
  const app = ctx.args[0];
  const out = el('div', {});
  root.append(el('p', {}, link('#/overview', '← Overview')), out);
  let dead = false;
  if (!app) {
    out.append(message('error', 'Missing app.'));
    return null;
  }
  const canResume = Array.isArray(ctx.me.permissions) && ctx.me.permissions.includes('fallback-control');
  const state = el('p', { id: 'circuit-state', 'aria-live': 'polite' });
  const status = el('div', {});
  const actions = el('div', {});
  const tbody = el('tbody', {});
  const more = el('button', { id: 'circuit-more', type: 'button', text: 'Next page', hidden: true });
  const empty = el('p', { class: 'muted', text: 'No circuit events yet.', hidden: true });
  let cursor = null;

  out.append(el('section', { class: 'card' },
    el('h2', {}, 'Fallback circuit of ', el('code', { text: app })),
    state, actions, status,
    el('h3', { text: 'History' }),
    el('div', { class: 'table-wrap' }, el('table', { id: 'circuit-events' },
      el('thead', {}, el('tr', {}, [
        ['When'], ['Transition'], ['Reason'], ['Due', 'num'], ['Ratio', 'num'], ['Suppressed', 'num'], ['Admin key']]
        .map(([h, c]) => el('th', { class: c, text: h })))),
      tbody)),
    empty, more));

  function showCircuit(c) {
    state.textContent = c ? 'State: ' + stateText(c) : 'State: CLOSED';
    clear(actions);
    // OPEN only: the API would also accept CLOSED and FORCED, but there is nothing to resume then
    if (canResume && c && c.state === 'OPEN') {
      actions.append(el('button', { id: 'resume', type: 'button', class: 'primary', text: 'Resume fallbacks', on: { click: () => resume(c) } }));
    }
  }

  async function resume(c) {
    if (!window.confirm('Resume fallbacks for ' + app + '? Fallbacks are sent again from now on (' + c.suppressed + ' were suppressed while the circuit was open).')) return;
    const btn = actions.querySelector('button');
    if (btn) btn.disabled = true;
    status.replaceChildren();
    try {
      const next = await resumeFallbacks(app);
      if (dead) return;
      showCircuit(next);
      loadEvents(true);
    } catch (e) {
      if (dead || e.status === 401) return;
      if (btn) btn.disabled = false;
      // 404 (no longer in scope): the reload says "App not found." (once) and removes the button
      if (e.status === 404) {
        status.replaceChildren();
        loadCircuit();
        return;
      }
      status.append(message('error', 'Could not resume: ' + e.message));
      // 409 (suspended or disabled meanwhile): the state shown is stale
      if (e.status === 409) loadCircuit();
    }
  }

  async function loadEvents(reset) {
    if (reset) { cursor = null; clear(tbody); }
    try {
      const page = await circuitEvents(app, cursor);
      if (dead) return;
      page.items.forEach((e) => tbody.append(eventRow(e)));
      cursor = page.nextCursor;
      more.hidden = !cursor;
      empty.hidden = tbody.children.length > 0;
    } catch (e) {
      if (dead || e.status === 401) return;
      status.append(message('error', e.status === 404 ? 'App not found.' : 'Could not load the history: ' + e.message));
    }
  }

  async function loadCircuit() {
    try {
      const body = await fallbackCircuits();
      if (dead) return;
      const c = body.items.find((i) => i.appId === app);
      if (!c) { clear(actions); status.append(message('error', 'App not found.')); return; }
      showCircuit(body.enabled ? c : null);
      if (!body.enabled) state.textContent = 'Circuit breaker disabled';
      // suspended wins over everything, also over a disabled breaker
      if (body.suspended) status.append(message('error', 'Fallbacks suspended by configuration'));
    } catch (e) {
      if (dead || e.status === 401) return;
      status.append(message('error', 'Could not load the circuit: ' + e.message));
    }
  }

  more.addEventListener('click', () => loadEvents(false));
  loadCircuit();
  loadEvents(true);
  return () => { dead = true; };
}
