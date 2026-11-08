// DOM helpers. Server values reach the page only through textContent / text nodes, never through markup strings.
const SVG_NS = 'http://www.w3.org/2000/svg';
// Attributes that could carry markup, script or inline style are never set (CSP forbids them anyway).
const BLOCKED = /^(on.+|sty|inner|outer)/i;

function apply(node, props, children) {
  for (const [k, v] of Object.entries(props || {})) {
    if (v === null || v === undefined || v === false || BLOCKED.test(k)) continue;
    if (k === 'text') node.textContent = String(v);
    else if (k === 'on') for (const [ev, fn] of Object.entries(v)) node.addEventListener(ev, fn);
    else node.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

export function el(tag, props, ...children) {
  return apply(document.createElement(tag), props, children);
}

export function svg(tag, props, ...children) {
  return apply(document.createElementNS(SVG_NS, tag), props, children);
}

export function clear(node) {
  node.replaceChildren();
  return node;
}

export function labelled(text, input) {
  return el('label', {}, text, input);
}

export function option(value, text) {
  return el('option', { value, text: text === undefined ? value : text });
}

export function dash(v) {
  return v === null || v === undefined ? '–' : String(v);
}

export function fmtRate(r) {
  return typeof r === 'number' ? (r * 100).toFixed(1) + ' %' : '–';
}

export function fmtTime(iso) {
  if (!iso) return '–';
  const d = new Date(iso);
  return isNaN(d) ? '–' : d.toLocaleString([], { hour12: false });
}

export function fmtMs(ms) {
  if (ms === null || ms === undefined) return '–';
  return ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : ms + ' ms';
}

export function fmtAge(seconds) {
  if (seconds === null || seconds === undefined) return '–';
  if (seconds < 120) return seconds + ' s';
  if (seconds < 7200) return Math.floor(seconds / 60) + ' min';
  return Math.floor(seconds / 3600) + ' h';
}

export function chip(state) {
  const s = String(state || '');
  return el('span', { class: 'chip state-' + s.replace(/[^A-Z_]/g, ''), text: s });
}

export function link(href, text) {
  return el('a', { href, text });
}

export function message(kind, text) {
  return el('p', { class: kind, role: kind === 'error' ? 'alert' : null, text });
}
