import { devices } from '../api.js';
import { el, fmtTime, link, message } from '../dom.js';

export function render(root, ctx) {
  const [app, userId] = ctx.args;
  const out = el('div', {});
  root.append(el('p', {}, link('#/search', '← Search')), out);
  let dead = false;
  if (!app || !userId) {
    out.append(message('error', 'Missing app or user id.'));
    return null;
  }
  (async () => {
    try {
      const body = await devices(app, userId);
      if (dead) return;
      out.append(el('section', { class: 'card' },
        el('h2', {}, 'Devices of ', el('code', { text: userId }), ' in ', el('code', { text: app })),
        body.items.length === 0 ? message('muted', 'No devices.') :
          el('div', { class: 'table-wrap' }, el('table', { id: 'devices' },
            el('thead', {}, el('tr', {}, ['Device', 'Platform', 'Registered', 'Last seen', 'Revoked'].map((h) => el('th', { text: h })))),
            el('tbody', {}, body.items.map((d) => el('tr', {},
              el('td', { class: 'id', text: d.deviceId }), el('td', { text: d.platform }),
              el('td', { text: fmtTime(d.createdAt) }), el('td', { text: fmtTime(d.lastSeenAt) }),
              el('td', { text: d.revokedAt ? fmtTime(d.revokedAt) : '–' }))))))));
    } catch (e) {
      if (dead || e.status === 401) return;
      out.append(message('error', e.status === 404 ? 'Not found.' : 'Could not load the devices: ' + e.message));
    }
  })();
  return () => { dead = true; };
}
