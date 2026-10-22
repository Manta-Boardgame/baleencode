// A small, safe Markdown renderer for the AI's replies.
//
// Safety: the text is HTML-escaped FIRST, so nothing the model (or a web page it read) writes can become markup. Only the
// tags below are then built from the escaped text: headings, paragraphs, line breaks, bold, italic, inline code, code blocks,
// lists, block quotes, tables, and links to http(s) addresses only (opened outside the app, without a referrer).
(function () {
  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (s) => String(s).replace(/[&<>"']/g, c => ESC[c]);

  // Inline formatting on text that is already escaped
  function inline(s) {
    const codes = [];
    s = s.replace(/`([^`\n]+)`/g, (_m, c) => { codes.push(c); return `\u0000${codes.length - 1}\u0000`; });
    s = s.replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g, (_m, label, url) => `<a href="${url}" target="_blank" rel="noopener noreferrer">${label}</a>`);
    s = s.replace(/(^|[\s(（「])(https?:\/\/[^\s<)）」]+)/g, (_m, pre, url) => {
      const tail = url.match(/(?:[.,;:!?。、]|&quot;|&#39;)+$/)?.[0] ?? '';     // sentence punctuation after an address
      const u = tail ? url.slice(0, -tail.length) : url;
      return `${pre}<a href="${u}" target="_blank" rel="noopener noreferrer">${u}</a>${tail}`;
    });
    s = s.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/(^|[^*\w])\*([^*\n]+)\*(?!\w)/g, '$1<em>$2</em>');
    s = s.replace(/\u0000(\d+)\u0000/g, (_m, i) => `<code>${codes[Number(i)]}</code>`);
    return s;
  }

  const isRow = (l) => /^\s*\|.*\|\s*$/.test(l);
  const isSep = (l) => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(l);
  const cells = (l) => l.trim().replace(/^\||\|$/g, '').split('|').map(c => inline(c.trim()));

  function blocks(text) {
    const lines = text.split('\n');
    const out = [];
    let para = [];
    const flush = () => { if (para.length) { out.push(`<p>${para.map(inline).join('<br>')}</p>`); para = []; } };
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i];
      let m;
      if (!l.trim()) { flush(); continue; }
      if ((m = l.match(/^(#{1,6})\s+(.*)$/))) { flush(); const n = Math.min(m[1].length + 2, 6); out.push(`<h${n}>${inline(m[2])}</h${n}>`); continue; }
      if (/^\s*(---|\*\*\*|___)\s*$/.test(l)) { flush(); out.push('<hr>'); continue; }
      if (isRow(l) && i + 1 < lines.length && isSep(lines[i + 1])) {
        flush();
        const head = cells(l); i += 1;
        const body = [];
        while (i + 1 < lines.length && isRow(lines[i + 1])) { i += 1; body.push(cells(lines[i])); }
        out.push(`<div class="mdtable"><table><thead><tr>${head.map(c => `<th>${c}</th>`).join('')}</tr></thead><tbody>${body.map(r => `<tr>${r.map(c => `<td>${c}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`);
        continue;
      }
      if (/^\s*([-*+]|\d+[.)])\s+/.test(l)) {
        flush();
        const ordered = /^\s*\d+[.)]\s+/.test(l);
        const items = [];
        while (i < lines.length && /^\s*([-*+]|\d+[.)])\s+/.test(lines[i])) {
          let item = lines[i].replace(/^\s*([-*+]|\d+[.)])\s+/, '');
          while (i + 1 < lines.length && /^\s{2,}\S/.test(lines[i + 1]) && !/^\s*([-*+]|\d+[.)])\s+/.test(lines[i + 1])) { i += 1; item += '<br>' + lines[i].trim(); }
          items.push(`<li>${inline(item)}</li>`);
          i += 1;
        }
        i -= 1;
        out.push(ordered ? `<ol>${items.join('')}</ol>` : `<ul>${items.join('')}</ul>`);
        continue;
      }
      if (/^\s*&gt;\s?/.test(l)) {
        flush();
        const q = [];
        while (i < lines.length && /^\s*&gt;\s?/.test(lines[i])) { q.push(inline(lines[i].replace(/^\s*&gt;\s?/, ''))); i += 1; }
        i -= 1;
        out.push(`<blockquote>${q.join('<br>')}</blockquote>`);
        continue;
      }
      para.push(l);
    }
    flush();
    return out.join('');
  }

  /** Markdown text -> safe HTML */
  function render(text) {
    const src = esc(String(text ?? '').replace(/\r\n/g, '\n'));
    const parts = [];
    const re = /```([\w+.#-]*)[^\n]*\n?([\s\S]*?)(?:```|$)/g;    // an unclosed fence (while streaming) runs to the end
    let last = 0, m;
    while ((m = re.exec(src))) {
      parts.push(blocks(src.slice(last, m.index)));
      parts.push(`<pre class="code"><code>${m[2].replace(/\n$/, '')}</code></pre>`);
      last = re.lastIndex;
      if (m[0].length === 0) break;
    }
    parts.push(blocks(src.slice(last)));
    return parts.join('');
  }

  window.MD = { render };
})();
