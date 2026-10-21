// Desktop-app control for the Agent ("Desktop" mode), built on Windows UI Automation.
//
// The assistant works only on the windows that the user names (a part of each title; up to 5). It sees one window's
// controls as a numbered text outline ([12] Button "Equals") and acts on a control by number: press, type text,
// scroll. This is the accessibility interface that screen readers use, so nothing is synthesised at mouse/keyboard
// level and the assistant cannot reach outside the allowed windows. Controls that an app does not expose
// (custom-drawn areas, games) cannot be operated this way.
//
// Several windows: every tool takes an optional "window" (one of the allowed titles). The window used last stays the
// current one, and control numbers always belong to the latest outline of the current window.
import { RISKY } from './browser.mjs';

const b64 = (s) => Buffer.from(String(s), 'utf8').toString('base64');
const str = (v, n) => { if (typeof v !== 'string') throw new Error(`"${n}" must be text.`); return v; };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** "Calculator, Notepad" -> ['Calculator', 'Notepad'] (at most 5) */
export function parseTargets(text) {
  return [...new Set(String(text ?? '').split(/[,;\n]+/).map(s => s.trim()).filter(Boolean))].slice(0, 5).map(s => s.slice(0, 120));
}

/** Make `name` (one of the allowed titles) the current window. Anything else is refused. */
function useWindow(ctx, name) {
  if (!ctx.targets?.length) throw new Error('No window was chosen. Type part of the window title in the box above the chat first.');
  if (name === undefined || name === null || name === '') {
    ctx.target = ctx.target || ctx.targets[0];
    return ctx.target;
  }
  const want = String(name).trim().toLowerCase();
  const hit = ctx.targets.find(t => t.toLowerCase() === want) || ctx.targets.find(t => t.toLowerCase().includes(want) || want.includes(t.toLowerCase()));
  if (!hit) throw new Error(`Not allowed: "${name}". You may only use: ${ctx.targets.map(t => `"${t}"`).join(', ')}.`);
  if (hit !== ctx.target) { ctx.target = hit; ctx.desktop = null; }     // numbers of the old window no longer apply
  return ctx.target;
}

async function ps(ctx, args) {
  if (!ctx.target) throw new Error('No window was chosen. Type part of the window title in the box above the chat first.');
  if (!ctx.helpers?.ps) throw new Error('Desktop control is not available here.');
  const out = await ctx.helpers.ps('desktop.ps1', ['-TitleB64', b64(ctx.target), ...args]);
  let j;
  try { j = JSON.parse(out.trim().split(/\r?\n/).filter(Boolean).pop()); } catch { throw new Error('Could not read the window (unexpected answer).'); }
  if (j.error) throw new Error(j.error);
  return j;
}

function describe(it, n) {
  const can = [];
  if (/Invoke|Toggle|SelectionItem|ExpandCollapse/.test(it.pats)) can.push('click');
  if (/Value/.test(it.pats) && !it.password) can.push('type');
  if (it.password) can.push('password');
  if (/Scroll(?!Item)/.test(it.pats)) can.push('scroll');
  const value = it.value ? ` value="${it.value}"` : '';
  return `[${n}] ${it.type} "${it.name}"${value}${it.enabled ? '' : ' [disabled]'}${can.length ? ` (${can.join(', ')})` : ''}`;
}

async function snapshot(ctx) {
  const j = await ps(ctx, ['-Cmd', 'snapshot']);
  const refs = {};
  const lines = j.items.map((it, i) => { refs[i + 1] = it; return describe(it, i + 1); });
  ctx.desktop = { refs, window: ctx.target };
  return `Window: ${j.window} (${j.w}x${j.h} px)\n${lines.join('\n') || '(no controls found: this app may not expose its controls)'}${j.cut ? '\n[outline cut short: the window has many more controls]' : ''}`;
}

async function observe(ctx, lead) {
  if (ctx.skipObserve) return lead;
  await sleep(450);
  return `${lead}\n${await snapshot(ctx)}`;
}

const need = (ctx, ref) => {
  const it = ctx.desktop?.refs?.[ref];
  if (!it) throw new Error('Unknown number. Call desktop_snapshot first (for this window) and use a number from it.');
  return it;
};

const act = (ctx, it, action, extra = []) =>
  ps(ctx, ['-Cmd', 'act', '-Path', it.path, '-Action', action, '-ExpectB64', b64(`${it.type}|${it.name}`), ...extra]);

const WINDOW_PARAM = { type: 'string', description: 'Which allowed window to use (one of the titles you were given). Omit to use the current window.' };
const tag = (ctx) => (ctx.targets?.length > 1 ? ` in "${ctx.target}"` : '');

export const DESKTOP_TOOLS = {
  desktop_snapshot: {
    group: 'desktop', kind: 'observe',
    def: { description: 'List the controls of a window (buttons, text boxes, text ...) with numbers. Use the numbers with desktop_click, desktop_type and desktop_scroll. With "window" you switch to another allowed window.',
      parameters: { type: 'object', properties: { window: WINDOW_PARAM } } },
    prepare: (a, ctx) => { useWindow(ctx, a.window); return { abs: [], title: `Look at the window${tag(ctx)}` }; },
    run: (_p, ctx) => snapshot(ctx),
  },

  desktop_screenshot: {
    group: 'desktop', kind: 'observe',
    def: { description: 'Take a screenshot of a window (only that window). Use it when the outline is not enough to understand the layout.',
      parameters: { type: 'object', properties: { window: WINDOW_PARAM } } },
    prepare: (a, ctx) => { useWindow(ctx, a.window); return { abs: [], title: `Take a screenshot of the window${tag(ctx)}` }; },
    run: async (_p, ctx) => {
      const j = await ps(ctx, ['-Cmd', 'shot']);
      return { text: `Screenshot of "${ctx.target}" (${j.w}x${j.h} px). Act through control numbers, not coordinates.`, image: { mime: 'image/jpeg', b64: j.b64, w: j.w, h: j.h } };
    },
  },

  desktop_click: {
    group: 'desktop', kind: 'act',
    def: { description: 'Press / toggle / select / expand the control with this number (from the latest desktop_snapshot of the current window).',
      parameters: { type: 'object', properties: { ref: { type: 'integer' }, window: WINDOW_PARAM }, required: ['ref'] } },
    prepare: (a, ctx) => {
      useWindow(ctx, a.window);
      const it = need(ctx, a.ref);
      return { abs: [], title: `Click [${a.ref}] ${it.type} "${it.name}"${tag(ctx)}`, it, dangerous: RISKY.test(it.name) };
    },
    run: async (prep, ctx) => { await act(ctx, prep.it, 'click'); return observe(ctx, 'Clicked.'); },
  },

  desktop_type: {
    group: 'desktop', kind: 'act',
    def: { description: 'Put text into the text control with this number, replacing its content.',
      parameters: { type: 'object', properties: { ref: { type: 'integer' }, text: { type: 'string' }, window: WINDOW_PARAM }, required: ['ref', 'text'] } },
    prepare: (a, ctx) => {
      useWindow(ctx, a.window);
      const it = need(ctx, a.ref);
      str(a.text, 'text');
      return { abs: [], title: `Type into [${a.ref}] ${it.type} "${it.name}"${tag(ctx)}`, detail: a.text.length > 300 ? a.text.slice(0, 300) + '…' : a.text,
        it, text: a.text, dangerous: it.password };
    },
    run: async (prep, ctx) => { await act(ctx, prep.it, 'settext', ['-TextB64', b64(prep.text)]); return observe(ctx, 'Typed.'); },
  },

  desktop_scroll: {
    group: 'desktop', kind: 'observe',
    def: { description: 'Scroll the scrollable control with this number down or up.',
      parameters: { type: 'object', properties: { ref: { type: 'integer' }, direction: { type: 'string', enum: ['down', 'up'] }, amount: { type: 'integer' }, window: WINDOW_PARAM }, required: ['ref', 'direction'] } },
    prepare: (a, ctx) => { useWindow(ctx, a.window); return { abs: [], title: `Scroll [${a.ref}] ${a.direction === 'up' ? 'up' : 'down'}${tag(ctx)}`, it: need(ctx, a.ref), a }; },
    run: async (prep, ctx) => {
      await act(ctx, prep.it, prep.a.direction === 'up' ? 'scroll_up' : 'scroll_down', ['-Amount', String(Math.min(Math.max(Number(prep.a.amount) || 3, 1), 20))]);
      return observe(ctx, 'Scrolled.');
    },
  },
};
