import { notification, payload } from '../api.js';
import { chip, clear, el, fmtMs, fmtTime, labelled, link, message, option } from '../dom.js';

function short(id) { return id ? String(id).slice(0, 8) : 'purged'; }

function entries(d) {
  const n = d.notification;
  const list = [];
  list.push({ t: n.createdAt, order: 0, cls: 'k-created', title: 'Created', detail: 'Policy ' + n.policy + ', timeout ' + n.timeoutMinutes + ' min' });
  if (n.dueAt) list.push({ t: n.dueAt, order: 1, cls: 'k-due', title: 'Fallback due', detail: n.policy === 'NOT_READ' ? 'if not read' : 'if not delivered' });
  for (const e of d.events) {
    list.push({ t: e.occurredAt, order: 2, cls: e.type === 'READ' ? 'k-read' : 'k-delivered', title: e.type,
      detail: 'device ' + short(e.deviceId) + (e.platform ? ' (' + e.platform + ')' : '') + (e.late ? ', late' : '') +
        ', received ' + fmtTime(e.receivedAt) });
  }
  for (const a of d.attempts) {
    const cls = a.outcome === 'SUCCESS' ? 'k-attempt-ok' : a.outcome === 'FINAL' ? 'k-attempt-bad' : 'k-attempt-retry';
    list.push({ t: a.startedAt, order: 3, cls, title: 'Webhook attempt ' + a.attempt + ': ' + a.outcome,
      detail: 'HTTP ' + (a.httpStatus === null ? '–' : a.httpStatus) + ', ' + fmtMs(a.durationMs) + (a.reason ? ', ' + a.reason : '') });
  }
  list.sort((x, y) => (Date.parse(x.t) - Date.parse(y.t)) || (x.order - y.order));
  if (n.closedAt) list.push({ t: n.closedAt, order: 9, cls: 'k-closed', title: 'Closed', state: n.state });
  return list;
}

export function render(root, ctx) {
  const id = ctx.args[0] || '';
  const hint = ctx.query.get('app') || '';
  const out = el('div', {});
  root.append(el('p', {}, link('#/search', '← Search')), out);
  let dead = false;

  function devicesLink(userId) {
    const known = hint && ctx.me.apps.includes(hint) ? hint : ctx.me.apps.length === 1 ? ctx.me.apps[0] : '';
    const a = el('a', { class: 'button', text: 'Devices of this user' });
    const point = (app) => { if (app) a.setAttribute('href', '#/devices/' + encodeURIComponent(app) + '/' + encodeURIComponent(userId)); else a.removeAttribute('href'); };
    point(known);
    if (known) return a;
    const sel = el('select', { id: 'devices-app' }, option('', 'Choose app…'), ctx.me.apps.map((x) => option(x)));
    sel.addEventListener('change', () => point(sel.value));
    return el('span', { class: 'row' }, labelled('App', sel), a);
  }

  function payloadBlock(bytes) {
    if (!ctx.me.permissions.includes('payload')) {
      return el('p', { class: 'muted', id: 'payload', text: 'hidden (' + bytes + ' bytes)' });
    }
    const pre = el('pre', { id: 'payload', hidden: true });
    const status = el('span', { class: 'muted' });
    const btn = el('button', { id: 'show-payload', type: 'button', text: 'Show payload (' + bytes + ' bytes)' });
    btn.addEventListener('click', async () => {
      btn.disabled = true;
      try {
        const body = await payload(id);
        if (dead) return;
        pre.textContent = JSON.stringify(body, null, 2);
        pre.hidden = false;
        status.textContent = '';
        btn.hidden = true;
      } catch (e) {
        if (dead || e.status === 401) return;
        status.textContent = e.status === 404 ? 'Payload not available.' : 'Could not load the payload: ' + e.message;
        btn.disabled = false;
      }
    });
    return el('div', {}, btn, ' ', status, pre);
  }

  (async () => {
    try {
      const d = await notification(id);
      if (dead) return;
      const n = d.notification;
      clear(out).append(
        el('section', { class: 'card' },
          el('h2', {}, 'Notification ', chip(n.state)),
          el('p', { class: 'id' }, el('code', { text: n.trackingId })),
          el('p', {}, 'User ', el('code', { text: n.userId }), n.externalId === null ? '' : [' · external id ', el('code', { text: n.externalId })],
            ' · attempts ' + n.fallbackAttempts),
          devicesLink(n.userId)),
        el('section', { class: 'card' }, el('h2', { text: 'Timeline' }),
          el('ol', { id: 'timeline', class: 'timeline' }, entries(d).map((e) => el('li', { class: e.cls },
            el('div', {}, el('strong', { text: e.title }), e.state ? [' ', chip(e.state)] : ''),
            el('div', { class: 'when', text: fmtTime(e.t) }),
            e.detail ? el('div', { text: e.detail }) : null)))),
        el('section', { class: 'card' }, el('h2', { text: 'Fallback payload' }), payloadBlock(d.payloadBytes)));
    } catch (e) {
      if (dead || e.status === 401) return;
      clear(out).append(el('section', { class: 'card' },
        message('error', e.status === 404 ? 'Notification not found.' : 'Could not load the notification: ' + e.message)));
    }
  })();
  return () => { dead = true; };
}
