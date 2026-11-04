import { api, el, query, fmtTime, errorBox, appSelect } from '../api.js';

export function mount(root, ctx) {
  const err = errorBox();
  const app = appSelect(ctx.apps, () => load());
  const mode = el('select', {}, ['OK', 'FAIL_500', 'FAIL_400', 'HANG'].map((m) => el('option', { value: m, text: m })));
  const hang = el('input', { type: 'number', min: '0', max: '120000', value: '5000' });
  const status = el('p', { role: 'status' });
  const body = el('tbody');

  async function load() {
    err.show(null);
    try {
      const d = await api('/inbox' + query({ appId: app.value }));
      mode.value = d.mode.mode;
      status.textContent = 'Receiver mode: ' + d.mode.mode + (d.mode.mode === 'HANG' ? ' (' + d.mode.hangMs + ' ms)' : '') + ' — in flight: ' + d.inFlight;
      body.replaceChildren(...d.calls.map((c) => el('tr', {},
        el('td', { text: fmtTime(c.receivedAt) }),
        el('td', {}, el('code', { text: c.trackingId || '(none)' })),
        el('td', { text: c.attempt }),
        el('td', {}, el('code', { text: c.idempotencyKey || '' })),
        el('td', { text: c.signatureValid ? 'valid' : 'INVALID', class: c.signatureValid ? 'ok' : 'error' }),
        el('td', { text: c.reason || '' }),
        el('td', {}, c.pending ? el('span', { class: 'badge inflight', text: 'in flight' }) : String(c.respondedStatus)))));
    } catch (e) { err.show(e); }
  }
  const onEvent = (ev) => { if (ev.detail && ev.detail.type === 'webhook') load(); };
  ctx.bus.addEventListener('event', onEvent);
  const onResync = () => load();
  ctx.bus.addEventListener('resync', onResync);
  const timer = setInterval(load, 3000);
  const form = el('form', { class: 'card', onsubmit: async (ev) => {
    ev.preventDefault();
    err.show(null);
    try { await api('/inbox/mode', { method: 'POST', body: { appId: app.value, mode: mode.value, hangMs: Number(hang.value) || 0 } }); await load(); } catch (e) { err.show(e); }
  } }, el('h3', { text: 'Receiver behaviour' }),
  el('div', { class: 'row' }, el('label', {}, 'App', app), el('label', {}, 'Answer with', mode), el('label', {}, 'Hang time (ms)', hang),
    el('button', { type: 'submit', text: 'Apply' })));
  root.append(form, err, status, el('div', { class: 'card table-wrap' }, el('table', {},
    el('thead', {}, el('tr', {}, ['Received', 'Tracking id', 'Attempt', 'Idempotency-Key', 'HMAC', 'Reason', 'Answered'].map((t) => el('th', { text: t })))), body)));
  load();
  return { unmount() { clearInterval(timer); ctx.bus.removeEventListener('event', onEvent); ctx.bus.removeEventListener('resync', onResync); } };
}
