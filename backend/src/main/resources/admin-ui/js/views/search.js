import { searchNotifications } from '../api.js';
import { chip, el, fmtTime, labelled, link, message, option } from '../dom.js';

const STATES = ['PENDING', 'DELIVERED', 'READ', 'FALLBACK_PENDING', 'FALLBACK_TRIGGERED', 'FALLBACK_FAILED', 'FALLBACK_EXPIRED', 'CANCELLED', 'NO_DEVICE_REGISTERED'];

function isoOrEmpty(v) {
  if (!v) return '';
  const d = new Date(v);
  return isNaN(d) ? '' : d.toISOString();
}

export function render(root, ctx) {
  const app = el('select', { id: 'search-app' }, ctx.me.apps.map((a) => option(a)));
  const user = el('input', { id: 'search-user', type: 'text', autocomplete: 'off' });
  const ext = el('input', { id: 'search-external', type: 'text', autocomplete: 'off' });
  const tid = el('input', { id: 'search-tracking', type: 'text', autocomplete: 'off', spellcheck: 'false' });
  const state = el('select', { id: 'search-state' }, option('', 'Any state'), STATES.map((s) => option(s)));
  const from = el('input', { id: 'search-from', type: 'datetime-local' });
  const to = el('input', { id: 'search-to', type: 'datetime-local' });
  const go = el('button', { id: 'search-go', type: 'submit', class: 'primary', text: 'Search' });
  const status = el('div', { 'aria-live': 'polite' });
  const body = el('tbody', {});
  const more = el('button', { id: 'search-more', type: 'button', text: 'Next page', hidden: true });
  const results = el('div', { id: 'results', hidden: true },
    el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {}, ['Created', 'State', 'User', 'External id', 'Policy', 'Attempts', 'Tracking id'].map((h) => el('th', { text: h })))),
      body)),
    more);
  const form = el('form', { class: 'card' },
    el('div', { class: 'row' }, labelled('App', app), labelled('User id', user), labelled('External id', ext), labelled('Tracking id', tid)),
    el('div', { class: 'row' }, labelled('State', state), labelled('Created from', from), labelled('Created before', to), go),
    status);
  root.append(form, results);

  let cursor = null;
  let lastParams = null;
  let dead = false;

  // appId: the app of the search that produced the rows (the selector may have changed since)
  function addRows(items, appId) {
    for (const n of items) {
      body.append(el('tr', {},
        el('td', { text: fmtTime(n.createdAt) }),
        el('td', {}, chip(n.state)),
        el('td', { class: 'id' }, link('#/devices/' + encodeURIComponent(appId) + '/' + encodeURIComponent(n.userId), n.userId)),
        el('td', { class: 'id', text: n.externalId === null ? '–' : n.externalId }),
        el('td', { text: n.policy }),
        el('td', { class: 'num', text: n.fallbackAttempts }),
        el('td', { class: 'id' }, link('#/n/' + encodeURIComponent(n.trackingId) + '?app=' + encodeURIComponent(appId), n.trackingId))));
    }
  }

  async function run(params, append) {
    go.disabled = true;
    more.disabled = true;
    try {
      const page = await searchNotifications(params);
      if (dead) return;
      if (!append) body.replaceChildren();
      addRows(page.items, params.app);
      cursor = page.nextCursor;
      lastParams = params;
      results.hidden = false;
      more.hidden = !cursor;
      status.replaceChildren(message('muted', body.children.length ? '' : 'No notifications match.'));
    } catch (e) {
      if (dead || e.status === 401) return;
      status.replaceChildren(message('error', e.status === 404 ? 'App not found.' : 'Search failed: ' + e.message));
    } finally {
      go.disabled = false;
      more.disabled = false;
    }
  }

  form.addEventListener('submit', (ev) => {
    ev.preventDefault();
    const id = tid.value.trim();
    if (id) { location.hash = '#/n/' + encodeURIComponent(id) + '?app=' + encodeURIComponent(app.value); return; }
    const f = isoOrEmpty(from.value);
    const t = isoOrEmpty(to.value);
    if (f && t && f >= t) { status.replaceChildren(message('error', '"Created from" must be before "Created before".')); return; }
    status.replaceChildren();
    cursor = null;
    run({ app: app.value, userId: user.value.trim(), externalId: ext.value.trim(), state: state.value, from: f, to: t, limit: 50 }, false);
  });
  more.addEventListener('click', () => { if (cursor) run({ ...lastParams, cursor }, true); });
  return () => { dead = true; };
}
