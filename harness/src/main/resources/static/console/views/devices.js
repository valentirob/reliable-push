import { api, el, field, query, fmtTime, errorBox, appSelect } from '../api.js';

export function mount(root, ctx) {
  const err = errorBox();
  const app = appSelect(ctx.apps, () => load());
  const user = el('input', { type: 'text', value: 'alice', maxlength: '200' });
  const platform = el('select', {}, el('option', { value: 'ANDROID', text: 'ANDROID' }), el('option', { value: 'IOS', text: 'IOS' }));
  const mode = el('select', {}, el('option', { value: 'V1', text: 'v1 (data-only)' }), el('option', { value: 'V2', text: 'v2 (mutable content)' }));
  const body = el('tbody');
  const netState = el('span', { class: 'muted' });
  const netButton = el('button', { type: 'button' });
  let netOnline = true;
  async function loadNet() {
    try {
      const n = await api('/device-network');
      netOnline = n.online;
      netState.textContent = (n.enabled ? '' : 'proxy not running; ') + (n.online ? 'online' : 'offline');
      netButton.textContent = n.online ? 'Go offline' : 'Go online';
    } catch (e) { err.show(e); }
  }
  netButton.addEventListener('click', async () => {
    err.show(null);
    try { await api('/device-network', { method: 'POST', body: { online: !netOnline } }); } catch (e) { err.show(e); }
    await loadNet();
  });
  const syncMode = () => { mode.disabled = platform.value !== 'ANDROID'; };
  platform.addEventListener('change', syncMode);
  syncMode();

  async function act(fn) {
    err.show(null);
    try { await fn(); } catch (e) { err.show(e); }
    await load();
  }
  // only the answer to the latest list request renders (an app switch may overtake an earlier load)
  let loadSeq = 0;
  async function load() {
    const seq = ++loadSeq;
    const appId = app.value;
    try {
      const list = await api('/devices' + query({ appId }));
      if (seq !== loadSeq) return;
      body.replaceChildren(...list.map((d) => el('tr', {},
        el('td', { text: d.userId }), el('td', { text: (d.kind === 'REAL' ? 'REAL ' : '') + d.platform + (d.androidMode ? ' ' + d.androidMode : '') + (d.label ? ' (' + d.label + ')' : '') }),
        el('td', {}, el('code', { text: d.deviceId })),
        el('td', { text: d.tokenExpiresAt ? new Date(d.tokenExpiresAt).toLocaleString() : '' }),
        el('td', { text: d.online ? 'online' : 'offline' }),
        el('td', {}, d.kind === 'REAL' ? el('span', { class: 'muted', text: 'registered by the app' }) : [
          el('button', { type: 'button', text: 'Refresh token', onclick: () => act(() => api('/devices/' + d.deviceId + '/refresh', { method: 'POST', body: {} })) }), ' ',
          el('button', { type: 'button', text: d.online ? 'Go offline' : 'Go online', onclick: () => act(() => api('/devices/' + d.deviceId + '/online', { method: 'POST', body: { online: !d.online } })) })]))));
      if (!list.length) body.replaceChildren(el('tr', {}, el('td', { colspan: '6', class: 'muted', text: 'No devices yet.' })));
    } catch (e) { if (seq === loadSeq) err.show(e); }
  }
  const form = el('form', { class: 'card', onsubmit: (ev) => {
    ev.preventDefault();
    act(() => api('/devices', { method: 'POST', body: { appId: app.value, userId: user.value, platform: platform.value, androidMode: platform.value === 'ANDROID' ? mode.value : null } }));
  } },
  el('h3', { text: 'Register a simulated device' }),
  el('div', { class: 'row' }, field('App', app), field('User id', user), field('Platform', platform), field('Android mode', mode),
    el('button', { type: 'submit', text: 'Register device' })));
  const net = el('div', { class: 'card' }, el('h3', { text: 'Real device network (iOS Simulator via 127.0.0.1:8091)' }),
    el('div', { class: 'row' }, netState, netButton));
  root.append(form, net, err, el('div', { class: 'card table-wrap' }, el('table', {},
    el('thead', {}, el('tr', {}, ['User', 'Platform', 'Device id', 'Token expires', 'Status', 'Actions'].map((t) => el('th', { text: t })))), body)),
  el('p', { class: 'muted', text: 'Last loaded ' + fmtTime(new Date().toISOString()) }));
  load();
  loadNet();
  return { unmount() { loadSeq++; } };
}
