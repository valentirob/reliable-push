import { api, el, errorBox, badge } from '../api.js';

export function mount(root, ctx) {
  const err = errorBox();
  const list = el('div');
  const out = el('div', { class: 'card' }, el('p', { class: 'muted', text: 'No run yet.' }));
  let busy = false;

  function showResults(results) {
    out.replaceChildren(el('h3', { text: 'Result' }), ...results.map((r) => el('div', {},
      el('p', {}, el('strong', { text: r.scenario || r.name || '' }), ' ', badge(String(r.status)), ' ', el('span', { class: 'muted', text: (r.durationMs ?? '') + ' ms' })),
      r.failure ? el('pre', { text: 'expected: ' + r.failure.expected + '\nactual: ' + r.failure.actual + '\nhint: ' + (r.failure.hint || '') }) : null,
      r.logs ? el('pre', { text: Array.isArray(r.logs) ? r.logs.join('\n') : String(r.logs) }) : null)));
  }
  async function run(names, button) {
    if (busy) return;
    busy = true;
    err.show(null);
    out.replaceChildren(el('p', { role: 'status', text: 'Running ' + (names ? names.join(', ') : 'all scenarios') + ' … watch Live tracking in another tab.' }));
    try {
      const r = await api('/scenarios/run', { method: 'POST', body: names ? { names } : {} });
      showResults(r.results);
    } catch (e) { err.show(e); out.replaceChildren(); } finally { busy = false; }
  }
  (async () => {
    try {
      const s = await api('/scenarios');
      const runAll = el('button', { type: 'button', text: 'Run all', onclick: () => run(null) });
      list.replaceChildren(runAll, el('div', { class: 'table-wrap' }, el('table', {},
        el('thead', {}, el('tr', {}, ['Scenario', 'Description', ''].map((t) => el('th', { text: t })))),
        el('tbody', {}, s.map((x) => el('tr', {}, el('td', {}, el('code', { text: x.name })), el('td', { text: x.description }),
          el('td', {}, x.externalControl
            ? el('span', { class: 'muted', text: 'needs scripts/e2e.sh (kills workers)' })
            : el('button', { type: 'button', text: 'Run', onclick: () => run([x.name]) }))))))));
    } catch (e) { err.show(e); }
  })();
  root.append(err, el('div', { class: 'card' }, list), out);
}
