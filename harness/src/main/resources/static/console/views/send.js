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
  const submit = el('button', { type: 'submit', text: 'Send push' });
  // The behaviour chosen per device survives re-renders of the list (keyed by device id).
  const choices = new Map();
  // Only the answer to the latest device-list request may render: an older (stale) answer would rebuild the
  // selects with their defaults, or show "no device", after the user picked a behaviour.
  let loadSeq = 0;
  let loading = false;
  let typing = null;

  function setLoading(on) {
    loading = on;
    submit.disabled = on;
  }

  function renderDevices(devs) {
    perDevice.replaceChildren(...(devs.length ? devs.map((d) => {
      let choice = choices.get(d.deviceId);
      if (!choice) {
        choice = { behaviour: BEHAVIOURS[0][0], override: '' };
        choices.set(d.deviceId, choice);
      }
      const sel = el('select', {}, BEHAVIOURS.map(([v, l]) => el('option', { value: v, text: l + ' (' + v + ')' })));
      sel.value = choice.behaviour;
      sel.addEventListener('change', () => { choice.behaviour = sel.value; });
      const custom = el('input', { type: 'text', placeholder: 'custom, e.g. deliver:500ms' });
      custom.value = choice.override;
      custom.addEventListener('input', () => { choice.override = custom.value; });
      return el('div', { class: 'row', 'data-device': d.deviceId },
        el('span', {}, d.platform + ' ', el('code', { text: d.deviceId.slice(0, 8) })),
        field('Behaviour', sel), field('Override', custom));
    }) : [el('p', { class: 'muted', text: 'This user has no simulated device: the push reaches nobody.' })]));
  }

  function perDeviceValues() {
    const out = {};
    perDevice.querySelectorAll('[data-device]').forEach((row) => {
      const [sel, custom] = [row.querySelector('select'), row.querySelector('input')];
      out[row.getAttribute('data-device')] = custom.value.trim() || sel.value;
    });
    return out;
  }

  async function loadDevices() {
    clearTimeout(typing);
    const seq = ++loadSeq;
    const appId = app.value, userId = user.value;
    setLoading(true);
    try {
      const list = await api('/devices' + query({ appId, userId }));
      if (seq !== loadSeq) return;
      renderDevices(list.filter((d) => d.userId === userId));
    } catch (e) {
      if (seq === loadSeq) err.show(e);
    } finally {
      if (seq === loadSeq) setLoading(false);
    }
  }
  user.addEventListener('change', loadDevices);
  user.addEventListener('input', () => {
    // the list shown belongs to the previous user id until the debounced reload answers
    clearTimeout(typing);
    setLoading(true);
    typing = setTimeout(loadDevices, 300);
  });

  async function showPreviews(tid) {
    const p = await api('/payload-preview' + query({ trackingId: tid, title: title.value, body: text.value }));
    previews.replaceChildren(...[['APNs', p.apns], ['FCM v1 (Android mode v1)', p.fcmV1], ['FCM v2 (Android mode v2)', p.fcmV2], ['adb debug broadcast (sample app on an emulator; paste into a host shell)', p.androidDebugBroadcast]].map(([name, payload]) => {
      const btn = el('button', { type: 'button', text: 'Copy' });
      btn.addEventListener('click', () => copyText(payload, btn));
      return el('div', { class: 'card' }, el('h3', { text: name }), btn, el('pre', { text: payload }));
    }));
  }
  const form = el('form', { class: 'card', onsubmit: async (ev) => {
    ev.preventDefault();
    if (loading) return;
    err.show(null);
    result.textContent = '';
    try {
      const r = await api('/push', { method: 'POST', body: {
        appId: app.value, userId: user.value, title: title.value, body: text.value, policy: policy.value,
        timeoutMinutes: Number(timeout.value) || null, perDevice: perDeviceValues() } });
      result.textContent = 'Sent. Tracking id: ' + r.trackingId;
      await showPreviews(r.trackingId);
    } catch (e) { err.show(e); }
  } },
  el('div', { class: 'row' }, field('App', app), field('User id', user), field('Policy', policy), field('Timeout (minutes)', timeout)),
  el('div', { class: 'row' }, field('Title', title), field('Message', text)),
  el('h3', { text: 'Per-device behaviour' }), perDevice,
  submit);
  root.append(form, err, result, el('h3', { text: 'Payload the customer backend would send (spec 6)' }), previews);
  loadDevices();
  return { unmount() { clearTimeout(typing); loadSeq++; } };
}
