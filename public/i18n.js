// A small translation layer. The page is written in English; a language pack (for example lang-ja.js) adds
// translations. Without a pack nothing changes, so an English-only build simply leaves the pack file out.
//
// How it works: text shown in the page (text nodes, placeholder / title attributes, alert and confirm messages) is
// looked up in the pack, first as an exact sentence and then against patterns for sentences that contain numbers or
// names. Anything without a translation stays English. Text produced by the AI (replies, file previews, tool output)
// is never translated.
(function () {
  const meta = document.querySelector('meta[name="app-lang"]');
  const DEFAULT = (meta && meta.content) || 'en';
  const packs = {};                       // lang -> { exact: {}, rules: [[RegExp, string | function]] }
  let lang = DEFAULT;
  try { lang = localStorage.getItem('ds-lang') || DEFAULT; } catch {}
  const SKIP = 'script,style,pre,textarea,code,.text,.think,.msg.user,[data-no-i18n]';
  const ATTRS = ['placeholder', 'title', 'aria-label'];
  const MARKERS = /^(⚠ |🔧 |📄 |🛡 )/;

  function line(s, pack) {
    const core0 = s.trim();
    if (!core0) return s;
    const lead = s.slice(0, s.indexOf(core0)), trail = s.slice(s.indexOf(core0) + core0.length);
    let core = core0, prefix = '';
    const m = core.match(MARKERS);
    if (m) { prefix = m[1]; core = core.slice(prefix.length); }
    let out = pack.exact[core];
    if (out === undefined) {
      for (const [re, to] of pack.rules) {
        const mm = core.match(re);
        if (mm) { out = typeof to === 'function' ? to(...mm) : core.replace(re, to); break; }
      }
    }
    return out === undefined ? s : lead + prefix + out + trail;
  }

  function tr(text) {
    const pack = packs[lang];
    if (!pack || typeof text !== 'string' || !text.trim()) return text;
    return text.includes('\n') ? text.split('\n').map(l => line(l, pack)).join('\n') : line(text, pack);
  }

  // ---- the page ----
  function skipped(node) {
    const el = node.nodeType === 1 ? node : node.parentElement;
    return !el || !!el.closest(SKIP);
  }

  function doText(node) {
    if (skipped(node)) return;
    const cur = node.nodeValue;
    const ours = node.__ja !== undefined && cur === node.__ja;
    const source = ours ? node.__en : cur;
    const out = lang === 'en' ? source : tr(source);
    if (out !== cur) node.nodeValue = out;
    if (out !== source) { node.__en = source; node.__ja = out; } else { node.__en = node.__ja = undefined; }
  }

  function doAttrs(el) {
    if (el.closest && el.closest('[data-no-i18n]')) return;      // attributes (placeholder, title) are app text even on a textarea
    for (const a of ATTRS) {
      if (!el.hasAttribute || !el.hasAttribute(a)) continue;
      const cur = el.getAttribute(a);
      const st = (el.__tr = el.__tr || {})[a];
      const ours = st && st.ja === cur;
      const source = ours ? st.en : cur;
      const out = lang === 'en' ? source : tr(source);
      if (out !== cur) el.setAttribute(a, out);
      el.__tr[a] = out !== source ? { en: source, ja: out } : undefined;
    }
  }

  function walk(root) {
    if (root.nodeType === 3) return doText(root);
    if (root.nodeType !== 1) return;
    doAttrs(root);
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
    for (let n = w.nextNode(); n; n = w.nextNode()) n.nodeType === 3 ? doText(n) : doAttrs(n);
  }

  let observer = null;
  function start() {
    walk(document.body);
    if (observer) observer.disconnect();
    observer = new MutationObserver((records) => {
      for (const r of records) {
        if (r.type === 'characterData') doText(r.target);
        else if (r.type === 'attributes') doAttrs(r.target);
        else r.addedNodes.forEach(walk);
      }
    });
    observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  }

  // alert / confirm messages
  const _alert = window.alert.bind(window), _confirm = window.confirm.bind(window);
  window.alert = (m) => _alert(tr(String(m)));
  window.confirm = (m) => _confirm(tr(String(m)));

  function langButton() {
    const b = document.getElementById('langBtn');
    if (!b) return;
    const others = Object.keys(packs);
    b.hidden = others.length === 0;
    b.textContent = lang === 'en' ? (packs.ja ? '日本語' : '') : 'English';
    b.onclick = () => api.setLang(lang === 'en' ? Object.keys(packs)[0] : 'en');
  }

  const api = {
    get lang() { return lang; },
    tr,
    register(code, pack) {
      const exact = {};
      for (const k of Object.keys(pack.exact || {})) exact[k.trim()] = pack.exact[k];     // lookups use trimmed text
      packs[code] = { exact, rules: pack.rules || [] }; if (document.body) { langButton(); start(); } },
    setLang(code) {
      lang = code;
      try { localStorage.setItem('ds-lang', code); } catch {}
      document.documentElement.lang = code;
      langButton(); start();
    },
  };
  window.I18N = api;
  window.t = tr;
  document.documentElement.lang = lang;
})();
