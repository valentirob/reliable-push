import { api, el, field, query, errorBox, appSelect, copyText } from '../api.js';

const BEHAVIOURS = [['deliver:1s', 'deliver after n s'], ['read:1s:3s', 'deliver then read'], ['never', 'never'], ['late:30s', 'offline, late delivery']];

export function mount(root, ctx) {
  const err = errorBox();
  const result = el('p', { role: 'status' });
  const app = appSelect(ctx.apps, () => loadDevices());
  const user = el('input', { type: 'text', value: 'alice', maxlength: '200' });
  const title = el('input', { type: 'text', value: 'Hello', maxlength: '1000' });
  const text = el('textarea', { rows: '2', maxlength: '1000' });
  text.value = 'Your order has shipped';
  const policy = el('select', {}, el('option', { value: 'NOT_DELIVERED', text: 'NOT_DELIVERED' }), el('option', { value: 'NOT_READ', text: 'NOT_READ' }));
  const timeout = el('input', { type: 'number', min: '1', value: '2' });
  const perDevice = el('div');
  const previews = el('div', { class: 'cols' });
  let devs = [];

  async function loadDevices() {
    try {
      devs = (await api('/devices' + query({ appId: app.value }))).filter((d) => d.userId === user.value);
      perDevice.replaceChildren(...(devs.length ? devs.map((d) => {
        const sel = el('select', {}, BEHAVIOURS.map(([v, l]) => el('option', { value: v, text: l + ' (' + v + ')' })));
        const custom = el('input', { type: 'text', placeholder: 'custom, e.g. deliver:500ms' });
        return el('div', { class: 'row', 'data-device': d.deviceId },
          el('span', {}, d.platform + ' ', el('code', { text: d.deviceId.slice(0, 8) })),
          field('Behaviour', sel), field('Override', custom));
      }) : [el('p', { class: 'muted', text: 'This user has no simulated device: the push reaches nobody.' })]));
      perDevice.getValues = () => {
        const out = {};
        perDevice.querySelectorAll('[data-device]').forEach((row) => {
          const [sel, custom] = [row.querySelector('select'), row.querySelector('input')];
          out[row.getAttribute('data-device')] = custom.value.trim() || sel.value;
        });
        return out;
      };
    } catch (e) { err.show(e); }
  }
  user.addEventListener('change', loadDevices);

  async function showPreviews(tid) {
    const p = await api('/payload-preview' + query({ trackingId: tid, title: title.value, body: text.value }));
    previews.replaceChildren(...[['APNs', p.apns], ['FCM v1 (Android mode v1)', p.fcmV1], ['FCM v2 (Android mode v2)', p.fcmV2]].map(([name, payload]) => {
      const btn = el('button', { type: 'button', text: 'Copy' });
      btn.addEventListener('click', () => copyText(payload, btn));
      return el('div', { class: 'card' }, el('h3', { text: name }), btn, el('pre', { text: payload }));
    }));
  }
  const form = el('form', { class: 'card', onsubmit: async (ev) => {
    ev.preventDefault();
    err.show(null);
    result.textContent = '';
    try {
      const r = await api('/push', { method: 'POST', body: {
        appId: app.value, userId: user.value, title: title.value, body: text.value, policy: policy.value,
        timeoutMinutes: Number(timeout.value) || null, perDevice: perDevice.getValues ? perDevice.getValues() : {} } });
      result.textContent = 'Sent. Tracking id: ' + r.trackingId;
      await showPreviews(r.trackingId);
    } catch (e) { err.show(e); }
  } },
  el('div', { class: 'row' }, field('App', app), field('User id', user), field('Policy', policy), field('Timeout (minutes)', timeout)),
  el('div', { class: 'row' }, field('Title', title), field('Message', text)),
  el('h3', { text: 'Per-device behaviour' }), perDevice,
  el('button', { type: 'submit', text: 'Send push' }));
  root.append(form, err, result, el('h3', { text: 'Payload the customer backend would send (spec 6)' }), previews);
  loadDevices();
}
