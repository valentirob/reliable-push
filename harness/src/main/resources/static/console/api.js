// Shared helpers. All DOM content is created with textContent / createElement: never HTML strings.
export function el(tag, props, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
    else if (k === 'text') node.textContent = String(v);
    else node.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

export function field(labelText, input) {
  return el('label', {}, labelText, input);
}

export async function api(path, options = {}) {
  const init = { method: options.method || 'GET', headers: { Accept: 'application/json' } };
  if (options.body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(options.body);
  }
  let res;
  try {
    res = await fetch('/console/api' + path, init);
  } catch (e) {
    throw new Error('Cannot reach the harness');
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
  if (!res.ok) {
    const detail = data && (data.detail || data.title);
    throw new Error('HTTP ' + res.status + (detail ? ': ' + detail : ''));
  }
  return data;
}

export function query(params) {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') p.set(k, v);
  const s = p.toString();
  return s ? '?' + s : '';
}

export function fmtTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d) ? '' : d.toLocaleTimeString([], { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

export function fmtDuration(ms) {
  if (ms <= 0) return '0:00';
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const mm = String(h ? m : m).padStart(h ? 2 : 1, '0'), rr = String(r).padStart(2, '0');
  return h ? h + ':' + mm + ':' + rr : mm + ':' + rr;
}

export function badge(state) {
  return el('span', { class: 'badge ' + String(state).replace(/[^A-Za-z_]/g, ''), text: state });
}

export function errorBox() {
  const box = el('p', { class: 'error', role: 'alert' });
  box.show = (e) => { box.textContent = e ? (e.message || String(e)) : ''; };
  return box;
}

export function appSelect(apps, onchange) {
  const sel = el('select', { onchange });
  for (const a of apps) sel.append(el('option', { value: a.appId, text: a.appId }));
  return sel;
}

export async function copyText(text, button) {
  try {
    await navigator.clipboard.writeText(text);
    const old = button.textContent;
    button.textContent = 'Copied';
    setTimeout(() => { button.textContent = old; }, 1200);
  } catch (e) {
    const area = el('textarea', { readonly: true });
    area.value = text;
    button.after(area);
    area.select();
  }
}
