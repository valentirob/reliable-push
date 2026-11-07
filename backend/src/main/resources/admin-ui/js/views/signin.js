import { signIn } from '../api.js';
import { el, message } from '../dom.js';

export function render(root, ctx) {
  const input = el('input', { id: 'key', type: 'password', autocomplete: 'off', spellcheck: 'false', required: true });
  const status = el('div', { 'aria-live': 'polite' });
  const button = el('button', { id: 'signin', type: 'submit', class: 'primary', text: 'Sign in' });
  const form = el('form', { class: 'card signin' },
    el('h2', { text: 'Sign in' }),
    el('label', { for: 'key' }, 'Admin key', input),
    el('p', { class: 'muted', text: 'The key stays in this browser tab only and is sent as a header.' }),
    button, status);
  form.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const key = input.value.trim();
    if (!key) { status.replaceChildren(message('error', 'Enter the admin key.')); return; }
    button.disabled = true;
    status.replaceChildren(message('muted', 'Checking…'));
    try {
      const me = await signIn(key);
      input.value = '';
      ctx.onSignedIn(me);
    } catch (e) {
      status.replaceChildren(message('error', e.status === 401 ? 'The key was not accepted.' : 'Sign-in failed: ' + e.message));
      button.disabled = false;
    }
  });
  root.append(form);
  input.focus();
  return null;
}
