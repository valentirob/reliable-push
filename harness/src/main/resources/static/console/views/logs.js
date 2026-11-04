import { api, el, field, query, fmtTime, errorBox } from '../api.js';

export function mount(root, ctx) {
  const err = errorBox();
  const trace = el('input', { type: 'text' });
  const tid = el('input', { type: 'text' });
  const action = el('input', { type: 'text', placeholder: 'e.g. fallback.dispatched' });
  const limit = el('input', { type: 'number', min: '1', max: '500', value: '100' });
  const body = el('tbody');
  async function load() {
    err.show(null);
    try {
      const rows = await api('/logs' + query({ traceId: trace.value.trim(), trackingId: tid.value.trim(), eventAction: action.value.trim(), limit: limit.value }));
      body.replaceChildren(...rows.map((r) => el('tr', {},
        el('td', { text: r['@timestamp'] ? fmtTime(r['@timestamp']) : '' }), el('td', { text: r['log.level'] || '' }),
        el('td', { text: r['event.action'] || '' }), el('td', {}, el('code', { text: r['trace.id'] || '' })),
        el('td', {}, el('code', { text: r['rp.tracking_id'] || '' })), el('td', { text: r.message || '' }))));
      if (!rows.length) body.replaceChildren(el('tr', {}, el('td', { colspan: '6', class: 'muted', text: 'No matching log lines (the backend log file is only available in the demo/e2e stack).' })));
    } catch (e) { err.show(e); }
  }
  root.append(el('form', { class: 'card', onsubmit: (ev) => { ev.preventDefault(); load(); } },
    el('div', { class: 'row' }, field('trace.id', trace), field('rp.tracking_id', tid), field('event.action', action), field('Limit', limit),
      el('button', { type: 'submit', text: 'Search' }))), err,
  el('div', { class: 'card table-wrap' }, el('table', {},
    el('thead', {}, el('tr', {}, ['Time', 'Level', 'event.action', 'trace.id', 'rp.tracking_id', 'Message'].map((t) => el('th', { text: t })))), body)));
  load();
}
