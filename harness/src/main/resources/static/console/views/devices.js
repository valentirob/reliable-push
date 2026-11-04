import { api, el, field, query, fmtTime, errorBox, appSelect } from '../api.js';

export function mount(root, ctx) {
  const err = errorBox();
  const app = appSelect(ctx.apps, () => load());
  const user = el('input', { type: 'text', value: 'alice', maxlength: '200' });
  const platform = el('select', {}, el('option', { value: 'ANDROID', text: 'ANDROID' }), el('option', { value: 'IOS', text: 'IOS' }));
  const mode = el('select', {}, el('option', { value: 'V1', text: 'v1 (data-only)' }), el('option', { value: 'V2', text: 'v2 (mutable content)' }));
  const body = el('tbody');
  const syncMode = () => { mode.disabled = platform.value !== 'ANDROID'; };
  platform.addEventListener('change', syncMode);
  syncMode();

  async function act(fn) {
    err.show(null);
    try { await fn(); } catch (e) { err.show(e); }
    await load();
  }
  async function load() {
    try {
      const list = await api('/devices' + query({ appId: app.value }));
      body.replaceChildren(...list.map((d) => el('tr', {},
        el('td', { text: d.userId }), el('td', { text: d.platform + (d.androidMode ? ' ' + d.androidMode : '') }),
        el('td', {}, el('code', { text: d.deviceId })),
        el('td', { text: d.tokenExpiresAt ? new Date(d.tokenExpiresAt).toLocaleString() : '' }),
        el('td', { text: d.online ? 'online' : 'offline' }),
        el('td', {},
          el('button', { type: 'button', text: 'Refresh token', onclick: () => act(() => api('/devices/' + d.deviceId + '/refresh', { method: 'POST' })) }), ' ',
          el('button', { type: 'button', text: d.online ? 'Go offline' : 'Go online', onclick: () => act(() => api('/devices/' + d.deviceId + '/online', { method: 'POST', body: { online: !d.online } })) })))));
      if (!list.length) body.replaceChildren(el('tr', {}, el('td', { colspan: '6', class: 'muted', text: 'No simulated devices yet.' })));
    } catch (e) { err.show(e); }
  }
  const form = el('form', { class: 'card', onsubmit: (ev) => {
    ev.preventDefault();
    act(() => api('/devices', { method: 'POST', body: { appId: app.value, userId: user.value, platform: platform.value, androidMode: platform.value === 'ANDROID' ? mode.value : null } }));
  } },
  el('h3', { text: 'Register a simulated device' }),
  el('div', { class: 'row' }, field('App', app), field('User id', user), field('Platform', platform), field('Android mode', mode),
    el('button', { type: 'submit', text: 'Register device' })));
  root.append(form, err, el('div', { class: 'card table-wrap' }, el('table', {},
    el('thead', {}, el('tr', {}, ['User', 'Platform', 'Device id', 'Token expires', 'Status', 'Actions'].map((t) => el('th', { text: t })))), body)),
  el('p', { class: 'muted', text: 'Last loaded ' + fmtTime(new Date().toISOString()) }));
  load();
}
