// Web page HTML to readable text, for the page reader (web_fetch, and pages pasted into Chat).
// One copy for both platforms: the page loads it as a script (Android), and webfetch.mjs runs it in a sandbox (Windows).
//
// It works in a single pass over the page, so its time grows in step with the page size: a hostile page (thousands of
// unclosed tags, for example) cannot make it hang. Regular expressions with back-references over the whole page did,
// in our tests (450 KB took 20 s).
(function (root) {
  'use strict';
  const MAX_INPUT = 2000000;                        // characters of HTML looked at (pages are cut at 3 MB before this)
  // Elements whose content is never text for a reader (navigation and footers are left out too)
  const SKIP = new Set(['script', 'style', 'noscript', 'svg', 'template', 'iframe', 'head', 'nav', 'footer', 'math', 'object']);
  // (a list item ends where the next one starts, so </li> adds nothing)
  const BLOCK_END = new Set(['p', 'div', 'section', 'article', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'table', 'tr',
    'blockquote', 'pre', 'header', 'main', 'dd', 'dt', 'figure', 'figcaption', 'aside', 'form']);
  const ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', copy: '©', reg: '®', hellip: '…', mdash: '—',
    ndash: '–', laquo: '«', raquo: '»', yen: '¥', middot: '·', trade: '™', times: '×', bull: '•', rsquo: '’', lsquo: '‘',
    rdquo: '”', ldquo: '“' };
  const NAME = /<(\/?)([a-zA-Z][a-zA-Z0-9-]*)/y;

  /** Index just past the ">" that ends the tag starting at `from`, honouring quoted attribute values; -1 if none */
  function tagEnd(s, from) {
    let q = '';
    for (let i = from; i < s.length; i++) {
      const c = s[i];
      if (q) { if (c === q) q = ''; }
      else if (c === '"' || c === "'") q = c;
      else if (c === '>') return i + 1;
    }
    return -1;
  }

  /** The part between the first <main> (or <article>) and the last closing tag, when the site marks its main content */
  function mainPart(s) {
    for (const tag of ['main', 'article']) {
      const open = new RegExp('<' + tag + '[\\s>]', 'i').exec(s);
      if (!open) continue;
      const close = new RegExp('</' + tag + '\\s*>', 'gi');
      close.lastIndex = open.index;
      let last = null, m;
      while ((m = close.exec(s))) last = m;
      if (last && last.index + last[0].length - open.index > 500) return s.slice(open.index, last.index + last[0].length);
    }
    return s;
  }

  function decodeEntities(t) {
    return t.replace(/&(#x[0-9a-f]{1,6}|#\d{1,7}|[a-z]{2,8});/gi, (m, e) => {
      if (e[0] === '#') {
        const n = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
        return n > 0 && n <= 0x10ffff ? String.fromCodePoint(n) : m;
      }
      return Object.prototype.hasOwnProperty.call(ENT, e.toLowerCase()) ? ENT[e.toLowerCase()] : m;
    });
  }

  function htmlToText(html) {
    let s = String(html).slice(0, MAX_INPUT);
    const title = /<title[^>]{0,200}>([^<]{0,300})<\/title>/i.exec(s);
    s = mainPart(s);
    const out = [];
    let i = 0;
    while (i < s.length) {
      const lt = s.indexOf('<', i);
      if (lt < 0) { out.push(s.slice(i)); break; }
      if (lt > i) out.push(s.slice(i, lt));
      if (s.startsWith('<!--', lt)) {                 // comment
        const end = s.indexOf('-->', lt + 4);
        i = end < 0 ? s.length : end + 3;
        continue;
      }
      if (s[lt + 1] === '!' || s[lt + 1] === '?') {   // <!DOCTYPE …>, <?xml …?>
        const end = s.indexOf('>', lt);
        i = end < 0 ? s.length : end + 1;
        continue;
      }
      NAME.lastIndex = lt;
      const m = NAME.exec(s);
      if (!m) { out.push('<'); i = lt + 1; continue; }  // a lone "<" in the text
      const end = tagEnd(s, NAME.lastIndex);
      if (end < 0) break;                              // the page ends inside a tag
      const closing = m[1] === '/', name = m[2].toLowerCase();
      i = end;
      if (!closing && SKIP.has(name) && s[end - 2] !== '/') {
        const close = new RegExp('</' + name + '\\s*>', 'gi');
        close.lastIndex = end;
        const c = close.exec(s);
        i = c ? c.index + c[0].length : s.length;      // unclosed: the rest of the page is that element
        continue;
      }
      if (!closing) {
        if (name === 'br') out.push('\n');
        else if (name === 'li') out.push('\n- ');
        else if (/^h[1-6]$/.test(name)) out.push('\n\n' + '#'.repeat(Number(name[1])) + ' ');
        else if (name === 'td' || name === 'th') out.push(' | ');
        else if (name === 'p' || name === 'div' || name === 'tr') out.push('\n');
      } else if (BLOCK_END.has(name)) out.push('\n');
    }
    const text = decodeEntities(out.join(''))
      .replace(/[ \t\r\f\v ]+/g, ' ')
      .replace(/ ?\n ?/g, '\n')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
    const t = title ? decodeEntities(title[1]).trim() : '';
    return (t ? `# ${t}\n\n` : '') + text;
  }

  root.BaleenHtml = { htmlToText };
})(typeof globalThis !== 'undefined' ? globalThis : this);
