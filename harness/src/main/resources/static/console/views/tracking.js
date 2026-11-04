import { api, el, query, badge, fmtTime, fmtDuration, errorBox, appSelect } from '../api.js';

export function mount(root, ctx) {
  const err = errorBox();
  const app = appSelect(ctx.apps, () => load());
  const body = el('tbody');
  const detail = el('div', { class: 'card' }, el('p', { class: 'muted', text: 'Select a notification.' }));
  const rows = new Map(); // trackingId -> {n, tr, cells}
  let selected = null;
  const cfg = () => ctx.apps.find((a) => a.appId === app.value) || { windowMinutes: 20, timeScaleSeconds: 60 };

  function countdown(cell, target) {
    cell.target = target ? new Date(target).getTime() : null;
  }
  function tickCell(cell, now) {
    if (cell.target === null || cell.target === undefined) { cell.textContent = ''; return; }
    cell.textContent = fmtDuration(cell.target - now);
  }
  const closed = (s) => ['CANCELLED', 'FALLBACK_TRIGGERED', 'FALLBACK_FAILED', 'FALLBACK_EXPIRED', 'READ'].includes(s);

  function renderRow(n) {
    let r = rows.get(n.trackingId);
    if (!r) {
      const cells = { state: el('td'), due: el('td'), end: el('td') };
      const tr = el('tr', { tabindex: '0' },
        el('td', {}, el('code', { text: n.trackingId.slice(0, 8) })), el('td', { text: n.userId }), cells.state, el('td', { text: n.policy }), cells.due, cells.end,
        el('td', {}, el('button', { type: 'button', text: 'Details', onclick: () => select(n.trackingId) })));
      r = { tr, cells, n };
      rows.set(n.trackingId, r);
      body.prepend(tr);
    }
    r.n = n;
    r.cells.state.replaceChildren(badge(n.state));
    countdown(r.cells.due, closed(n.state) ? null : n.dueAt);
    const win = cfg().windowMinutes * cfg().timeScaleSeconds * 1000;
    countdown(r.cells.end, closed(n.state) || !n.dueAt ? null : new Date(n.dueAt).getTime() + win);
    r.tr.className = selected === n.trackingId ? 'selected' : '';
    tickCell(r.cells.due, Date.now());
    tickCell(r.cells.end, Date.now());
  }

  async function load() {
    err.show(null);
    try {
      const list = await api('/notifications' + query({ appId: app.value }));
      rows.clear();
      body.replaceChildren();
      [...list].reverse().forEach(renderRow);
    } catch (e) { err.show(e); }
  }

  async function select(id) {
    selected = id;
    rows.forEach((r, k) => { r.tr.className = k === id ? 'selected' : ''; });
    await renderDetail();
  }
  async function renderDetail() {
    if (!selected) return;
    try {
      const d = await api('/notifications/' + selected);
      const items = d.timeline.map((e, i) => ({ at: e.at, kind: e.kind, details: e.details, seq: i }));
      const hooks = d.webhooks.map((w) => w.pending
        ? el('li', {}, fmtTime(w.receivedAt) + ' ', el('span', { class: 'badge inflight', text: 'in flight' }), ' webhook attempt ' + w.attempt)
        : null);
      items.sort((a, b) => new Date(a.at) - new Date(b.at) || a.seq - b.seq);
      const cancelBtn = el('button', { type: 'button', text: 'Cancel notification', onclick: async () => {
        err.show(null);
        try { await api('/notifications/' + selected + '/cancel', { method: 'POST' }); } catch (e) { err.show(e); }
      } });
      detail.replaceChildren(
        el('h3', { text: 'Notification ' + selected }),
        d.notification ? el('p', {}, badge(d.notification.state), ' user ', el('strong', { text: d.notification.userId }), ' ', cancelBtn) : null,
        el('ul', { class: 'timeline' }, items.map((e) => el('li', {}, fmtTime(e.at) + ' ', el('strong', { text: e.kind }), ' ', el('span', { class: 'muted', text: summarize(e.details) }))), hooks));
    } catch (e) { err.show(e); }
  }
  function summarize(details) {
    return Object.entries(details || {}).map(([k, v]) => k + '=' + (v && typeof v === 'object' ? JSON.stringify(v) : String(v))).join(' ');
  }

  let refetch = null;
  const onEvent = (ev) => {
    const m = ev.detail;
    if (!m || !m.trackingId) return;
    schedule();
  };
  const schedule = () => {
    // The stream is not globally ordered across threads: re-read the server-side (time-ordered) view, debounced.
    clearTimeout(refetch);
    refetch = setTimeout(async () => {
      await load();
      if (selected) await renderDetail();
    }, 250);
  };
  ctx.bus.addEventListener('event', onEvent);
  ctx.bus.addEventListener('resync', schedule);
  const timer = setInterval(() => {
    const now = Date.now();
    rows.forEach((r) => { tickCell(r.cells.due, now); tickCell(r.cells.end, now); });
  }, 1000);

  root.append(el('div', { class: 'row' }, el('label', {}, 'App', app)), err,
    el('div', { class: 'card table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {}, ['Tracking id', 'User', 'State', 'Policy', 'Due in', 'Window ends in', ''].map((t) => el('th', { text: t })))), body)),
    detail);
  load();
  return { unmount() { clearInterval(timer); clearTimeout(refetch); ctx.bus.removeEventListener('event', onEvent); ctx.bus.removeEventListener('resync', schedule); } };
}
