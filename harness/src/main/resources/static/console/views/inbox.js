import { api, el, query, fmtTime, errorBox, appSelect } from '../api.js';

export function mount(root, ctx) {
  const err = errorBox();
  // the form shows the applied mode until the user edits it; the 3 s refresh never overwrites an unapplied choice
  let dirty = false;
  const app = appSelect(ctx.apps, () => { dirty = false; load(); });
  const mode = el('select', {}, ['OK', 'FAIL_500', 'FAIL_400', 'HANG'].map((m) => el('option', { value: m, text: m })));
  const hang = el('input', { type: 'number', min: '0', max: '120000', value: '5000' });
  const status = el('p', { role: 'status' });
  const body = el('tbody');
  const edited = () => { dirty = true; };
  mode.addEventListener('change', edited);
  hang.addEventListener('input', edited);
  let loadSeq = 0;

  async function load() {
    const seq = ++loadSeq;
    const appId = app.value;
    try {
      const d = await api('/inbox' + query({ appId }));
      if (seq !== loadSeq) return;
      err.show(null);
      const editing = dirty || document.activeElement === mode || document.activeElement === hang;
      if (!editing) {
        mode.value = d.mode.mode;
        if (d.mode.mode === 'HANG') hang.value = String(d.mode.hangMs);
      }
      const pending = editing && mode.value !== d.mode.mode ? ' — not applied yet: ' + mode.value : '';
      status.textContent = 'Receiver mode: ' + d.mode.mode + (d.mode.mode === 'HANG' ? ' (' + d.mode.hangMs + ' ms)' : '') + ' — in flight: ' + d.inFlight + pending;
      body.replaceChildren(...d.calls.map((c) => el('tr', {},
        el('td', { text: fmtTime(c.receivedAt) }),
        el('td', {}, el('code', { text: c.trackingId || '(none)' })),
        el('td', { text: c.attempt }),
        el('td', {}, el('code', { text: c.idempotencyKey || '' })),
        el('td', { text: c.signatureValid ? 'valid' : 'INVALID', class: c.signatureValid ? 'ok' : 'error' }),
        el('td', { text: c.reason || '' }),
        el('td', {}, c.pending ? el('span', { class: 'badge inflight', text: 'in flight' }) : String(c.respondedStatus)))));
    } catch (e) { if (seq === loadSeq) err.show(e); }
  }
  const onEvent = (ev) => { if (ev.detail && ev.detail.type === 'webhook') load(); };
  ctx.bus.addEventListener('event', onEvent);
  const onResync = () => load();
  ctx.bus.addEventListener('resync', onResync);
  const timer = setInterval(load, 3000);
  const form = el('form', { class: 'card', onsubmit: async (ev) => {
    ev.preventDefault();
    err.show(null);
    try {
      await api('/inbox/mode', { method: 'POST', body: { appId: app.value, mode: mode.value, hangMs: Number(hang.value) || 0 } });
      dirty = false;
      await load();
    } catch (e) { err.show(e); }
  } }, el('h3', { text: 'Receiver behaviour' }),
  el('div', { class: 'row' }, el('label', {}, 'App', app), el('label', {}, 'Answer with', mode), el('label', {}, 'Hang time (ms)', hang),
    el('button', { type: 'submit', text: 'Apply' })));
  root.append(form, err, status, el('div', { class: 'card table-wrap' }, el('table', {},
    el('thead', {}, el('tr', {}, ['Received', 'Tracking id', 'Attempt', 'Idempotency-Key', 'HMAC', 'Reason', 'Answered'].map((t) => el('th', { text: t })))), body)));
  load();
  return { unmount() { loadSeq++; clearInterval(timer); ctx.bus.removeEventListener('event', onEvent); ctx.bus.removeEventListener('resync', onResync); } };
}
