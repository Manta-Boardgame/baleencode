// Browser control for the Agent ("Browser" and "Screen" modes).
//
// The app opens its OWN Edge/Chrome window with a separate profile (nothing from your normal browser, its
// logins or its history). It is driven through the browser's built-in DevTools protocol, so no mouse/keyboard
// injection into Windows is involved and the AI can only act inside that one browser window.
//
// Two ways of looking at a page:
//   text   - a list of the visible text and controls with numbers ([12] button "Submit"); cheap, exact, works with a text model
//   visual - a screenshot of the page; the model answers with pixel coordinates
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const PORT = 9333;
const DATA_DIR = path.join(process.env.LOCALAPPDATA ?? os.homedir(), 'BaleenCode');
const PROFILE = path.join(DATA_DIR, 'browser-profile');
const EXE_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
];
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const clip = (t, max) => (t.length <= max ? t : `${t.slice(0, max)}\n…[truncated]`);

// ---------- connection ----------

async function httpJson(p, opts) {
  const r = await fetch(`http://127.0.0.1:${PORT}${p}`, { signal: AbortSignal.timeout(3000), ...opts });
  return r.json();
}

async function ensureBrowser() {
  try { await httpJson('/json/version'); return; } catch {}
  const exe = EXE_CANDIDATES.find(p => fs.existsSync(p));
  if (!exe) throw new Error('Microsoft Edge or Google Chrome was not found on this PC.');
  fs.mkdirSync(PROFILE, { recursive: true });
  const child = spawn(exe, [`--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`, '--no-first-run',
    '--no-default-browser-check', '--window-size=1280,900', 'about:blank'], { detached: true, stdio: 'ignore' });
  child.unref();
  for (let i = 0; i < 50; i++) {
    await sleep(300);
    try { await httpJson('/json/version'); return; } catch {}
  }
  throw new Error('The browser window did not start.');
}

class Page {
  constructor(ws) {
    this.ws = ws; this.seq = 0; this.pending = new Map(); this.closed = false;
    ws.addEventListener('message', (e) => {
      const m = JSON.parse(e.data);
      const p = m.id && this.pending.get(m.id);
      if (p) { this.pending.delete(m.id); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result); }
    });
    ws.addEventListener('close', () => { this.closed = true; for (const p of this.pending.values()) p.reject(new Error('The browser window was closed.')); this.pending.clear(); });
  }
  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.seq;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`Browser did not answer (${method}).`)); }, 20000);
      this.pending.set(id, { resolve: (v) => { clearTimeout(timer); resolve(v); }, reject: (e) => { clearTimeout(timer); reject(e); } });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description?.split('\n')[0] ?? 'Script error in the page.');
    return r.result?.value;
  }
}

let page = null;
export async function getPage() {
  if (page && !page.closed) return page;
  await ensureBrowser();

  // Use the tab that really sits in a visible window (the browser can also hold hidden background pages)
  const ver = await httpJson('/json/version');
  const bws = new WebSocket(ver.webSocketDebuggerUrl);
  await new Promise((res, rej) => { bws.addEventListener('open', res); bws.addEventListener('error', () => rej(new Error('Could not connect to the browser.'))); });
  const browser = new Page(bws);
  let target = null, windowId = null;
  try {
    const { targetInfos } = await browser.send('Target.getTargets');
    for (const t of targetInfos.filter(x => x.type === 'page' && /^(https?|about|edge|chrome):/.test(x.url))) {
      try { ({ windowId } = await browser.send('Browser.getWindowForTarget', { targetId: t.targetId })); target = t; break; } catch {}
    }
    if (!target) {
      await httpJson('/json/new?about:blank', { method: 'PUT' });
      for (const t of (await browser.send('Target.getTargets')).targetInfos.filter(x => x.type === 'page')) {
        try { ({ windowId } = await browser.send('Browser.getWindowForTarget', { targetId: t.targetId })); target = t; break; } catch {}
      }
    }
    if (target) await browser.send('Browser.setWindowBounds', { windowId, bounds: { windowState: 'normal', left: 60, top: 40, width: 1280, height: 900 } }).catch(() => {});
  } finally { bws.close(); }
  if (!target) throw new Error('No browser tab is open.');

  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/devtools/page/${target.targetId}`);
  await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', () => rej(new Error('Could not connect to the browser tab.'))); });
  page = new Page(ws);
  await page.send('Page.enable');
  await page.send('Runtime.enable');
  return page;
}

async function settle(p, ms = 500) {
  await sleep(ms);
  for (let i = 0; i < 20; i++) {
    try { if ((await p.eval('document.readyState')) === 'complete') break; } catch {}
    await sleep(250);
  }
}

// ---------- looking at the page ----------

// Runs inside the page. Numbers every visible control with data-agent-ref and returns the outline.
function snapshotFn() {
  const MAX = 20000;
  document.querySelectorAll('[data-agent-ref]').forEach(e => e.removeAttribute('data-agent-ref'));
  document.querySelectorAll('a[target]').forEach(a => a.removeAttribute('target'));   // keep everything in this one tab
  const lines = []; let size = 0, n = 0; const refs = {};
  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'HEAD', 'META', 'LINK', 'TEMPLATE']);
  const INTERACTIVE = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=option],[role=switch],[onclick],[contenteditable=""],[contenteditable=true]';
  const clean = (t) => String(t || '').replace(/\s+/g, ' ').trim();
  const visible = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    const s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0';
  };
  const push = (l) => { if (size < MAX) { lines.push(l); size += l.length + 1; } };
  const labelOf = (el) => {
    const tag = el.tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') {
      if (el.id) { const lab = document.querySelector('label[for="' + CSS.escape(el.id) + '"]'); if (lab) return clean(lab.innerText).slice(0, 80); }
      const wrap = el.closest('label');
      if (wrap) return clean(wrap.innerText).slice(0, 80);
      return clean(el.getAttribute('aria-label') || el.placeholder || el.title || el.name).slice(0, 80);
    }
    return clean(el.getAttribute('aria-label') || el.innerText || el.title || el.alt || el.value).slice(0, 80);
  };
  const describe = (el) => {
    const tag = el.tagName.toLowerCase();
    const type = (el.getAttribute('type') || '').toLowerCase();
    let kind = el.getAttribute('role') || tag;
    if (tag === 'a') kind = 'link';
    else if (tag === 'button') kind = 'button';
    else if (tag === 'select') kind = 'select';
    else if (tag === 'textarea') kind = 'textbox';
    else if (tag === 'input') kind = type === 'checkbox' ? 'checkbox' : type === 'radio' ? 'radio' : (type === 'submit' || type === 'button') ? 'button' : type === 'password' ? 'password' : 'textbox';
    n += 1;
    const label = labelOf(el);
    let s = '[' + n + '] ' + kind + ' "' + label + '"';
    if (kind === 'textbox') s += ' value="' + String(el.value || '').slice(0, 60) + '"';
    if (kind === 'password') s += ' value=(hidden)';
    if (type === 'checkbox' || type === 'radio') s += el.checked ? ' [checked]' : ' [unchecked]';
    if (tag === 'select') s += ' value="' + (el.selectedOptions[0] ? el.selectedOptions[0].text : '') + '" options: ' + [...el.options].map(o => o.text).slice(0, 25).join(' | ');
    if (el.disabled) s += ' [disabled]';
    if (tag === 'a') s += ' -> ' + (el.getAttribute('href') || '').slice(0, 100);
    el.setAttribute('data-agent-ref', String(n));
    refs[n] = { kind, label, password: kind === 'password' };
    return s;
  };
  const walk = (el) => {
    if (size >= MAX || SKIP.has(el.tagName) || !visible(el)) return;
    if (el.matches(INTERACTIVE)) { push(describe(el)); return; }
    if (/^H[1-6]$/.test(el.tagName)) { push('#'.repeat(Number(el.tagName[1])) + ' ' + clean(el.innerText).slice(0, 120)); return; }
    if (el.tagName === 'IFRAME') { push('[iframe: its content is not shown here]'); return; }
    for (const c of el.childNodes) {
      if (c.nodeType === 3) { const t = clean(c.textContent); if (t) push(t.slice(0, 300)); }
      else if (c.nodeType === 1) walk(c);
    }
  };
  walk(document.body);
  return { url: location.href, title: document.title, text: lines.join('\n'), refs, scrollY: Math.round(scrollY),
    scrollH: document.documentElement.scrollHeight, vh: innerHeight, truncated: size >= MAX };
}

async function snapshot(ctx) {
  const p = await getPage();
  const s = await p.eval(`(${snapshotFn.toString()})()`);
  ctx.browser = { refs: s.refs };
  const where = s.scrollH > s.vh ? ` | scrolled ${s.scrollY}px of ${s.scrollH}px` : '';
  return `Page: ${s.title || '(no title)'} — ${s.url}${where}\n${s.text || '(the page has no visible content)'}${s.truncated ? '\n[page is long: scroll down for more]' : ''}`;
}

// Width and height of a JPEG, read from its header (so the size we tell the model is the real one)
function jpegSize(buf) {
  let i = 2;
  while (i + 9 < buf.length) {
    if (buf[i] !== 0xff) { i++; continue; }
    const marker = buf[i + 1];
    if (marker >= 0xc0 && marker <= 0xc2) return { h: buf.readUInt16BE(i + 5), w: buf.readUInt16BE(i + 7) };
    i += 2 + buf.readUInt16BE(i + 2);
  }
  return null;
}

/** Screenshot of the visible part of the page. ctx.shot remembers its size so clicks can be converted to page coordinates. */
async function screenshot(ctx) {
  const p = await getPage();
  const m = await p.send('Page.getLayoutMetrics');
  const v = m.cssVisualViewport;
  const dpr = Number(await p.eval('window.devicePixelRatio')) || 1;
  const r = await p.send('Page.captureScreenshot', { format: 'jpeg', quality: 70,
    clip: { x: v.pageX, y: v.pageY, width: v.clientWidth, height: v.clientHeight, scale: 1 / dpr } });
  const size = jpegSize(Buffer.from(r.data, 'base64')) ?? { w: Math.round(v.clientWidth), h: Math.round(v.clientHeight) };
  if (ctx) ctx.shot = { w: size.w, h: size.h, cssW: v.clientWidth, cssH: v.clientHeight };
  return { image: { mime: 'image/jpeg', b64: r.data, w: size.w, h: size.h }, w: size.w, h: size.h };
}

/** What the model sees after an action: the page outline (text style) or a screenshot (visual style). */
async function observe(ctx, lead) {
  if (ctx.skipObserve) return lead;          // several actions in one step: only the last one reports the page
  const p = await getPage();
  await settle(p);
  if (ctx.style === 'visual') {
    const s = await screenshot(ctx);
    const info = await p.eval('({url: location.href, title: document.title})');
    return { text: `${lead}\nPage: ${info.title || '(no title)'} — ${info.url}\nThe screenshot is ${s.w}x${s.h} pixels; coordinates start at the top-left corner.`, image: s.image };
  }
  return `${lead}\n${await snapshot(ctx)}`;
}

// ---------- acting on the page ----------

// Buttons that spend money, delete, or reach other people: these always ask, at every approval level
export const RISKY = /\b(buy now|purchase|pay now|checkout|place order|delete|remove account|transfer|withdraw|send( money)?|post|publish|tweet|reply|subscribe|unsubscribe|sign up|confirm order)\b|購入|支払|決済|注文|削除|送金|退会|送信|投稿|公開|返信|申し?込|解約|契約/i;

async function mouseClick(p, x, y, clickCount = 1) {
  await p.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
  await p.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount });
  await p.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount });
}

const KEYS = { Enter: 13, Tab: 9, Escape: 27, Esc: 27, Backspace: 8, Delete: 46, ArrowUp: 38, ArrowDown: 40, ArrowLeft: 37, ArrowRight: 39,
  PageDown: 34, PageUp: 33, Home: 36, End: 35, Space: 32 };

async function pressKey(p, spec) {
  const parts = String(spec).split('+').map(s => s.trim());
  const key = parts.pop();
  let modifiers = 0;
  for (const m of parts) { const l = m.toLowerCase(); if (l === 'alt') modifiers |= 1; else if (l === 'ctrl' || l === 'control') modifiers |= 2; else if (l === 'meta') modifiers |= 4; else if (l === 'shift') modifiers |= 8; }
  const code = KEYS[key] ?? (key.length === 1 ? key.toUpperCase().charCodeAt(0) : 0);
  if (!code) throw new Error(`Unknown key "${spec}". Use names like Enter, Tab, Escape, ArrowDown, or Ctrl+A.`);
  const text = key === 'Enter' ? '\r' : key.length === 1 && !(modifiers & 6) ? key : undefined;
  await p.send('Input.dispatchKeyEvent', { type: text ? 'keyDown' : 'rawKeyDown', key: key.length === 1 ? key : key, windowsVirtualKeyCode: code, modifiers, text });
  await p.send('Input.dispatchKeyEvent', { type: 'keyUp', key, windowsVirtualKeyCode: code, modifiers });
}

const REF_JS = (ref) => `document.querySelector('[data-agent-ref="${Number(ref)}"]')`;
const str = (v, n) => { if (typeof v !== 'string') throw new Error(`"${n}" must be text.`); return v; };

export const BROWSER_TOOLS = {
  browser_navigate: {
    group: 'browser', style: 'both', kind: 'navigate',
    def: { description: 'Open a web address (http or https) in the browser window.',
      parameters: { type: 'object', properties: { url: { type: 'string' } }, required: ['url'] } },
    prepare: (a) => {
      let u; try { u = new URL(str(a.url, 'url')); } catch { throw new Error('That is not a valid web address.'); }
      if (!/^https?:$/.test(u.protocol)) throw new Error('Only http and https addresses can be opened.');
      // A very long query could carry data out to another site, so it always asks
      return { abs: [], title: `Open ${u.href}`, url: u.href, dangerous: (u.search.length + u.hash.length) > 200 };
    },
    run: async (prep, ctx) => {
      const p = await getPage();
      await p.send('Page.navigate', { url: prep.url });
      return observe(ctx, `Opened ${prep.url}`);
    },
  },

  browser_snapshot: {
    group: 'browser', style: 'text', kind: 'observe',
    def: { description: 'List the visible text and controls of the current page. Each control has a number you can use with browser_click, browser_type and browser_select.',
      parameters: { type: 'object', properties: {} } },
    prepare: () => ({ abs: [], title: 'Look at the page' }),
    run: async (_p, ctx) => { const p = await getPage(); await settle(p, 100); return snapshot(ctx); },
  },

  browser_screenshot: {
    group: 'browser', style: 'both', kind: 'observe',
    def: { description: 'Take a screenshot of the current page (the visible part).',
      parameters: { type: 'object', properties: {} } },
    prepare: () => ({ abs: [], title: 'Take a screenshot' }),
    run: async (_p, ctx) => { const s = await screenshot(ctx); return { text: `Screenshot ${s.w}x${s.h} pixels (coordinates start at the top-left).`, image: s.image }; },
  },

  browser_click: {
    group: 'browser', style: 'text', kind: 'act',
    def: { description: 'Click the control with this number (from browser_snapshot).',
      parameters: { type: 'object', properties: { ref: { type: 'integer' } }, required: ['ref'] } },
    prepare: (a, ctx) => {
      const info = ctx.browser?.refs?.[a.ref];
      if (!info) throw new Error('Unknown number. Call browser_snapshot first and use a number from it.');
      return { abs: [], title: `Click [${a.ref}] ${info.kind} "${info.label}"`, ref: a.ref, dangerous: RISKY.test(info.label) };
    },
    run: async (prep, ctx) => {
      const p = await getPage();
      const pos = await p.eval(`(() => { const el = ${REF_JS(prep.ref)}; if (!el) return null; el.scrollIntoView({block:'center',inline:'center'}); const r = el.getBoundingClientRect(); return {x: r.left + r.width/2, y: r.top + r.height/2}; })()`);
      if (!pos) throw new Error('That control is no longer on the page. Call browser_snapshot again.');
      await mouseClick(p, pos.x, pos.y);
      return observe(ctx, `Clicked [${prep.ref}].`);
    },
  },

  browser_click_at: {
    group: 'browser', style: 'visual', kind: 'act',
    def: { description: 'Click at pixel coordinates (x, y) of the latest screenshot. Optionally double-click.',
      parameters: { type: 'object', properties: { x: { type: 'integer' }, y: { type: 'integer' }, double: { type: 'boolean' } }, required: ['x', 'y'] } },
    prepare: (a) => {
      if (!Number.isFinite(a.x) || !Number.isFinite(a.y) || a.x < 0 || a.y < 0) throw new Error('x and y must be positive numbers inside the screenshot.');
      return { abs: [], title: `Click at (${a.x}, ${a.y})`, a };
    },
    run: async (prep, ctx) => {
      const p = await getPage();
      const sh = ctx.shot;
      if (!sh) throw new Error('Take a screenshot first (browser_screenshot), then click using its coordinates.');
      if (prep.a.x > sh.w || prep.a.y > sh.h) throw new Error(`That point is outside the screenshot (${sh.w}x${sh.h}).`);
      const x = Math.round(prep.a.x * sh.cssW / sh.w), y = Math.round(prep.a.y * sh.cssH / sh.h);   // screenshot pixels -> page pixels
      const label = await p.eval(`(() => { const el = document.elementFromPoint(${x}, ${y}); return el ? (el.innerText || el.value || el.getAttribute('aria-label') || el.title || '').toString().slice(0, 80) : ''; })()`);
      if (RISKY.test(label) && !(await ctx.ask(`Click "${label}" at (${prep.a.x}, ${prep.a.y})`, 'This looks like a purchase, payment or deletion.', true))) {
        throw new Error('The user denied this action. Do not retry it; ask the user how to proceed.');
      }
      await mouseClick(p, x, y, prep.a.double ? 2 : 1);
      return observe(ctx, `Clicked at (${prep.a.x}, ${prep.a.y}).`);
    },
  },

  browser_type: {
    group: 'browser', style: 'both', kind: 'act',
    def: { description: 'Type text. With "ref" (text style) the field is focused and its old content replaced; without it (visual style) the text goes into the field that is currently focused — click the field first. Set submit to press Enter afterwards.',
      parameters: { type: 'object', properties: { text: { type: 'string' }, ref: { type: 'integer' }, submit: { type: 'boolean' } }, required: ['text'] } },
    prepare: (a, ctx) => {
      str(a.text, 'text');
      let info = null;
      if (a.ref !== undefined) { info = ctx.browser?.refs?.[a.ref]; if (!info) throw new Error('Unknown number. Call browser_snapshot first.'); }
      return { abs: [], title: info ? `Type into [${a.ref}] "${info.label}"` : 'Type into the focused field', detail: a.text.length > 300 ? a.text.slice(0, 300) + '…' : a.text,
        a, dangerous: !!info?.password };
    },
    run: async (prep, ctx) => {
      const p = await getPage();
      const a = prep.a;
      if (a.ref !== undefined) {
        const ok = await p.eval(`(() => { const el = ${REF_JS(a.ref)}; if (!el) return false; el.scrollIntoView({block:'center'}); el.focus(); if (el.select) el.select(); else document.execCommand('selectAll'); return true; })()`);
        if (!ok) throw new Error('That field is no longer on the page. Call browser_snapshot again.');
      }
      const active = await p.eval(`(() => { const e = document.activeElement; return {type: (e && e.type) || '', tag: e ? e.tagName : ''}; })()`);
      if (active.type === 'password' && a.ref === undefined && !(await ctx.ask('Type into a password field', 'The focused field is a password field.', true))) {
        throw new Error('The user denied this action. Do not retry it; ask the user how to proceed.');
      }
      await p.send('Input.insertText', { text: a.text });
      if (a.submit) await pressKey(p, 'Enter');
      return observe(ctx, a.submit ? 'Typed the text and pressed Enter.' : 'Typed the text.');
    },
  },

  browser_select: {
    group: 'browser', style: 'text', kind: 'act',
    def: { description: 'Choose an option (by its visible text) in a drop-down list with this number.',
      parameters: { type: 'object', properties: { ref: { type: 'integer' }, option: { type: 'string' } }, required: ['ref', 'option'] } },
    prepare: (a, ctx) => {
      const info = ctx.browser?.refs?.[a.ref];
      if (!info) throw new Error('Unknown number. Call browser_snapshot first.');
      return { abs: [], title: `Choose "${str(a.option, 'option')}" in [${a.ref}] "${info.label}"`, a };
    },
    run: async (prep, ctx) => {
      const p = await getPage();
      const res = await p.eval(`(() => { const el = ${REF_JS(prep.a.ref)}; if (!el || el.tagName !== 'SELECT') return 'notselect';
        const want = ${JSON.stringify(prep.a.option)}.trim().toLowerCase();
        const o = [...el.options].find(o => o.text.trim().toLowerCase() === want) || [...el.options].find(o => o.text.trim().toLowerCase().includes(want));
        if (!o) return 'nooption'; el.value = o.value; el.dispatchEvent(new Event('input', {bubbles:true})); el.dispatchEvent(new Event('change', {bubbles:true})); return 'ok'; })()`);
      if (res === 'notselect') throw new Error('That is not a drop-down list.');
      if (res === 'nooption') throw new Error('No such option. Check the options listed in browser_snapshot.');
      return observe(ctx, `Chose "${prep.a.option}".`);
    },
  },

  browser_press_key: {
    group: 'browser', style: 'both', kind: 'act',
    def: { description: 'Press a key or a combination, e.g. Enter, Tab, Escape, ArrowDown, PageDown, Ctrl+A.',
      parameters: { type: 'object', properties: { key: { type: 'string' } }, required: ['key'] } },
    prepare: (a) => ({ abs: [], title: `Press ${str(a.key, 'key')}`, a }),
    run: async (prep, ctx) => { const p = await getPage(); await pressKey(p, prep.a.key); return observe(ctx, `Pressed ${prep.a.key}.`); },
  },

  browser_scroll: {
    group: 'browser', style: 'both', kind: 'observe',
    def: { description: 'Scroll the page. direction: "down" or "up"; amount is in screens (default 0.8). In the visual style you may give x and y to scroll the element at that point.',
      parameters: { type: 'object', properties: { direction: { type: 'string', enum: ['down', 'up'] }, amount: { type: 'number' }, x: { type: 'integer' }, y: { type: 'integer' } }, required: ['direction'] } },
    prepare: (a) => ({ abs: [], title: `Scroll ${a.direction === 'up' ? 'up' : 'down'}`, a }),
    run: async (prep, ctx) => {
      const p = await getPage();
      const m = await p.send('Page.getLayoutMetrics');
      const v = m.cssVisualViewport;
      const sh = ctx.shot;
      const px = prep.a.x !== undefined && sh ? Math.round(prep.a.x * sh.cssW / sh.w) : undefined;
      const py = prep.a.y !== undefined && sh ? Math.round(prep.a.y * sh.cssH / sh.h) : undefined;
      const dir = prep.a.direction === 'up' ? -1 : 1;
      const dy = dir * Math.round((Math.min(Math.max(Number(prep.a.amount) || 0.8, 0.1), 5)) * v.clientHeight);
      await p.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: px ?? Math.round(v.clientWidth / 2), y: py ?? Math.round(v.clientHeight / 2), deltaX: 0, deltaY: dy });
      return observe(ctx, `Scrolled ${prep.a.direction}.`);
    },
  },

  browser_back: {
    group: 'browser', style: 'both', kind: 'navigate',
    def: { description: 'Go back to the previous page.', parameters: { type: 'object', properties: {} } },
    prepare: () => ({ abs: [], title: 'Go back' }),
    run: async (_p, ctx) => {
      const p = await getPage();
      const h = await p.send('Page.getNavigationHistory');
      if (h.currentIndex <= 0) throw new Error('There is no previous page.');
      await p.send('Page.navigateToHistoryEntry', { entryId: h.entries[h.currentIndex - 1].id });
      return observe(ctx, 'Went back.');
    },
  },

  wait: {
    group: 'browser', style: 'both', kind: 'observe',
    def: { description: 'Wait a few seconds (for a page to finish loading), then look again.',
      parameters: { type: 'object', properties: { seconds: { type: 'number' } }, required: ['seconds'] } },
    prepare: (a) => ({ abs: [], title: `Wait ${Math.min(Math.max(Number(a.seconds) || 1, 0.5), 10)} s`, secs: Math.min(Math.max(Number(a.seconds) || 1, 0.5), 10) }),
    run: async (prep, ctx) => { await sleep(prep.secs * 1000); return observe(ctx, `Waited ${prep.secs} s.`); },
  },
};
