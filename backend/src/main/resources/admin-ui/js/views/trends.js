import { dailyStats } from '../api.js';
import { clear, dash, el, fmtMs, fmtRate, labelled, message, option, svg } from '../dom.js';

const RANGES = [30, 90, 400];
const W = 640, H = 220, L = 44, R = 10, T = 10, B = 26;

function dayString(d) { return d.toISOString().slice(0, 10); }

function niceMax(v) {
  if (v <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 5, 10]) if (v <= m * p) return m * p;
  return v;
}

/** series: [{name, cls, key}] over days; null values break the line; live days get a hollow marker. */
function lineChart(title, days, series, yMax, fmtY) {
  const n = days.length;
  const x = (i) => L + (n <= 1 ? (W - L - R) / 2 : (i * (W - L - R)) / (n - 1));
  const y = (v) => T + (H - T - B) * (1 - v / yMax);
  const root = svg('svg', { class: 'chart', viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': title },
    svg('title', {}, title));
  for (let t = 0; t <= 4; t++) {
    const v = (yMax * t) / 4;
    root.append(svg('line', { class: 'axis', x1: L, x2: W - R, y1: y(v), y2: y(v) }),
      svg('text', { x: L - 5, y: y(v) + 4, 'text-anchor': 'end' }, fmtY(v)));
  }
  [0, Math.floor((n - 1) / 2), n - 1].filter((i, k, a) => i >= 0 && a.indexOf(i) === k).forEach((i) => {
    root.append(svg('text', { x: x(i), y: H - 8, 'text-anchor': i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle' }, days[i].day));
  });
  for (const s of series) {
    let d = '';
    let open = false;
    days.forEach((day, i) => {
      const v = day[s.key];
      if (typeof v !== 'number') { open = false; return; }
      d += (open ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(Math.min(v, yMax)).toFixed(1);
      open = true;
    });
    root.append(svg('path', { class: 'line ' + s.cls, d }));
    days.forEach((day, i) => {
      const v = day[s.key];
      if (typeof v !== 'number') return;
      if (day.live) root.append(svg('circle', { class: 'live ' + s.cls, cx: x(i), cy: y(Math.min(v, yMax)), r: 4 }));
      else if (n <= 60) root.append(svg('circle', { class: 'dot ' + s.cls.replace('s', 'd'), cx: x(i), cy: y(Math.min(v, yMax)), r: 2.5 }));
    });
  }
  return root;
}

function legend(items) {
  return el('div', { class: 'legend' }, items.map(([cls, name]) => el('span', {}, el('span', { class: 'swatch ' + cls }), name)));
}

function dataTable(days) {
  const head = ['Day', 'Registered', 'Cancelled', 'No device', 'Delivered', 'Read', 'iOS', 'Android', 'FB trig.', 'FB failed', 'FB expired',
    'p50', 'p95', 'Delivery', 'Read rate', 'FB rate'];
  const rows = days.slice().reverse().map((d) => el('tr', { class: d.live ? 'live-day' : null },
    el('td', { text: d.day + (d.live ? ' (live)' : '') }),
    [d.registered, d.cancelled, d.noDeviceRegistered, d.delivered, d.read, d.deliveredIos, d.deliveredAndroid, d.fallbackTriggered,
      d.fallbackFailed, d.fallbackExpired].map((v) => el('td', { class: 'num', text: dash(v) })),
    el('td', { class: 'num', text: fmtMs(d.deliveryP50Ms) }), el('td', { class: 'num', text: fmtMs(d.deliveryP95Ms) }),
    el('td', { class: 'num', text: fmtRate(d.deliveryRate) }), el('td', { class: 'num', text: fmtRate(d.readRate) }),
    el('td', { class: 'num', text: fmtRate(d.fallbackRate) })));
  return el('div', { class: 'table-wrap' }, el('table', { id: 'trend-table' },
    el('thead', {}, el('tr', {}, head.map((h, i) => el('th', { class: i ? 'num' : null, text: h })))),
    el('tbody', {}, rows)));
}

export function render(root, ctx) {
  const appSel = el('select', { id: 'trend-app' }, ctx.me.apps.map((a) => option(a)));
  let range = 30;
  const buttons = RANGES.map((r) => el('button', {
    type: 'button', 'aria-pressed': String(r === range), text: r + ' days',
    on: { click: () => { range = r; buttons.forEach((b, i) => b.setAttribute('aria-pressed', String(RANGES[i] === r))); load(); } },
  }));
  const out = el('div', { id: 'trend-out' });
  root.append(el('div', {}, el('div', { class: 'row' }, labelled('App', appSel), el('div', {}, buttons)), out));
  let dead = false;
  let seq = 0;

  async function load() {
    if (!appSel.value) { clear(out).append(message('muted', 'No apps visible to this key.')); return; }
    const mine = ++seq;
    const end = new Date(Date.now() + 86400000); // tomorrow 00:00 UTC is exclusive: includes today
    const to = dayString(end);
    const from = dayString(new Date(end.getTime() - range * 86400000));
    try {
      const body = await dailyStats(appSel.value, from, to);
      if (dead || mine !== seq) return;
      clear(out);
      const days = body.days;
      if (!days.length) { out.append(message('muted', 'No data for this range yet.')); return; }
      const maxVol = niceMax(Math.max(...days.map((d) => Math.max(d.registered, d.delivered, d.read))));
      out.append(
        el('section', { class: 'card' }, el('h2', { text: 'Daily volume' }),
          legend([['sw1', 'Registered'], ['sw2', 'Delivered'], ['sw3', 'Read']]),
          lineChart('Daily volume: registered, delivered, read', days,
            [{ key: 'registered', cls: 's1' }, { key: 'delivered', cls: 's2' }, { key: 'read', cls: 's3' }], maxVol, (v) => String(Math.round(v)))),
        el('section', { class: 'card' }, el('h2', { text: 'Daily rates' }),
          legend([['sw1', 'Delivery rate'], ['sw2', 'Read rate'], ['sw3', 'Fallback rate']]),
          lineChart('Daily rates: delivery, read, fallback', days,
            [{ key: 'deliveryRate', cls: 's1' }, { key: 'readRate', cls: 's2' }, { key: 'fallbackRate', cls: 's3' }], 1, (v) => Math.round(v * 100) + '%'),
          days.some((d) => d.live) ? el('p', { class: 'muted', text: 'Hollow markers and italic rows: the day is still changing.' }) : null),
        el('section', { class: 'card' }, el('h2', { text: 'Numbers' }), dataTable(days)));
    } catch (e) {
      if (dead || mine !== seq || e.status === 401) return;
      clear(out).append(message('error', e.status === 404 ? 'App not found.' : 'Could not load the trends: ' + e.message));
    }
  }

  appSel.addEventListener('change', load);
  load();
  return () => { dead = true; };
}
