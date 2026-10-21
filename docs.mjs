// Reads text out of documents for the Agent's read_file tool: PDF, Word (.docx), PowerPoint (.pptx), Excel (.xlsx).
// Everything happens on this PC. Scanned PDFs (pictures of pages) are read with the built-in Windows OCR.
// Safeguards: size limits, a cap on how far a compressed part may expand (zip bombs), and pdf.js without eval.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const DOC_EXT = new Set(['pdf', 'docx', 'pptx', 'xlsx']);
const MODULE_DIR = path.dirname(fileURLToPath(import.meta.url));
const MAX_DOC_BYTES = 100 * 1024 * 1024;
const MAX_XML_BYTES = 60 * 1024 * 1024;     // one unpacked part of an Office file
const MAX_PDF_PAGES = 1000;
const MAX_TEXT_CHARS = 4_000_000;

// ---------- zip (Office files) ----------

function zipIndex(buf) {
  let end = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 66000; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { end = i; break; }
  }
  if (end < 0) throw new Error('This does not look like an Office file (zip structure not found).');
  const count = buf.readUInt16LE(end + 10);
  let off = buf.readUInt32LE(end + 16);
  const entries = new Map();
  for (let i = 0; i < count; i++) {
    if (off + 46 > buf.length || buf.readUInt32LE(off) !== 0x02014b50) throw new Error('The file is damaged.');
    const nameLen = buf.readUInt16LE(off + 28), extraLen = buf.readUInt16LE(off + 30), commentLen = buf.readUInt16LE(off + 32);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    entries.set(name, { method: buf.readUInt16LE(off + 10), csize: buf.readUInt32LE(off + 20), size: buf.readUInt32LE(off + 24), local: buf.readUInt32LE(off + 42) });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

function zipRead(buf, e) {
  if (e.size > MAX_XML_BYTES) throw new Error('A part of this file expands to an unusually large size, so it was not read.');
  const nameLen = buf.readUInt16LE(e.local + 26), extraLen = buf.readUInt16LE(e.local + 28);
  const start = e.local + 30 + nameLen + extraLen;
  const raw = buf.subarray(start, start + e.csize);
  if (e.method === 0) return raw;
  if (e.method !== 8) throw new Error('Unsupported compression in this file.');
  try { return zlib.inflateRawSync(raw, { maxOutputLength: MAX_XML_BYTES }); }
  catch { throw new Error('A part of this file expands to an unusually large size or is damaged, so it was not read.'); }
}

const decodeEntities = (s) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'")
  .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16))).replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
  .replace(/&amp;/g, '&');
const tidy = (s) => s.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();

function docxText(buf) {
  const zip = zipIndex(buf);
  const e = zip.get('word/document.xml');
  if (!e) throw new Error('No document body found in the Word file.');
  const xml = zipRead(buf, e).toString('utf8');
  const text = xml.replace(/<w:tab\b[^>]*\/>/g, '\t').replace(/<w:br\b[^>]*\/>/g, '\n').replace(/<\/w:tc>/g, '\t')
    .replace(/<\/w:tr>/g, '\n').replace(/<\/w:p>/g, '\n').replace(/<[^>]+>/g, '');
  return { text: tidy(decodeEntities(text)), note: 'Word document' };
}

function pptxText(buf) {
  const zip = zipIndex(buf);
  const slides = [...zip.keys()].map(n => /^ppt\/slides\/slide(\d+)\.xml$/.exec(n)).filter(Boolean)
    .sort((a, b) => Number(a[1]) - Number(b[1]));
  if (!slides.length) throw new Error('No slides found in the PowerPoint file.');
  const parts = slides.map(([name, n]) => {
    const xml = zipRead(buf, zip.get(name)).toString('utf8');
    const text = xml.replace(/<\/a:p>/g, '\n').replace(/<a:br\b[^>]*\/>/g, '\n').replace(/<[^>]+>/g, '');
    return `--- Slide ${n} ---\n${tidy(decodeEntities(text))}`;
  });
  return { text: parts.join('\n\n'), note: `PowerPoint, ${slides.length} slides` };
}

function colIndex(letters) { let n = 0; for (const ch of letters) n = n * 26 + (ch.charCodeAt(0) - 64); return n - 1; }

function xlsxText(buf) {
  const zip = zipIndex(buf);
  const shared = [];
  const ss = zip.get('xl/sharedStrings.xml');
  if (ss) {
    for (const m of zipRead(buf, ss).toString('utf8').matchAll(/<si\b[^>]*>([\s\S]*?)<\/si>/g)) {
      shared.push(decodeEntities([...m[1].matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)].map(x => x[1]).join('')));
    }
  }
  let names = [];
  const wb = zip.get('xl/workbook.xml');
  if (wb) names = [...zipRead(buf, wb).toString('utf8').matchAll(/<sheet\b[^>]*\bname="([^"]*)"/g)].map(m => decodeEntities(m[1]));
  const sheets = [...zip.keys()].map(n => /^xl\/worksheets\/sheet(\d+)\.xml$/.exec(n)).filter(Boolean)
    .sort((a, b) => Number(a[1]) - Number(b[1]));
  if (!sheets.length) throw new Error('No sheets found in the Excel file.');
  const out = [];
  sheets.forEach(([name], idx) => {
    const xml = zipRead(buf, zip.get(name)).toString('utf8');
    const rows = [];
    for (const r of xml.matchAll(/<row\b[^>]*>([\s\S]*?)<\/row>/g)) {
      const cells = [];
      for (const c of r[1].matchAll(/<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
        const ref = /\br="([A-Z]+)\d+"/.exec(c[1]);
        if (!ref) continue;
        const type = (/\bt="([^"]*)"/.exec(c[1]) ?? [])[1];
        const body = c[2] ?? '';
        let v = (/<v>([\s\S]*?)<\/v>/.exec(body) ?? [])[1] ?? '';
        if (type === 's') v = shared[Number(v)] ?? '';
        else if (type === 'inlineStr') v = decodeEntities([...body.matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)].map(x => x[1]).join(''));
        else v = decodeEntities(v);
        if (v !== '') cells[colIndex(ref[1])] = v.replace(/[\t\r\n]+/g, ' ');
      }
      if (cells.length) rows.push(Array.from(cells, x => x ?? '').join('\t'));
      if (rows.length >= 5000) { rows.push('[… more rows not shown]'); break; }
    }
    out.push(`--- Sheet: ${names[idx] ?? `Sheet${idx + 1}`} ---\n${rows.join('\n') || '(empty)'}`);
  });
  return { text: out.join('\n\n'), note: `Excel, ${sheets.length} sheet${sheets.length > 1 ? 's' : ''}` };
}

// ---------- PDF ----------

let pdfjs = null;
async function pdfText(buf) {
  pdfjs ??= await import('pdfjs-dist/legacy/build/pdf.mjs');
  const base = path.join(MODULE_DIR, 'node_modules', 'pdfjs-dist') + path.sep;
  const doc = await pdfjs.getDocument({ data: new Uint8Array(buf), cMapUrl: base + 'cmaps' + path.sep, cMapPacked: true,
    standardFontDataUrl: base + 'standard_fonts' + path.sep, isEvalSupported: false, enableXfa: false, verbosity: 0 }).promise;
  if (doc.numPages > MAX_PDF_PAGES) throw new Error(`PDFs with more than ${MAX_PDF_PAGES} pages are not supported.`);
  const pages = [];
  let empty = 0;
  for (let i = 1; i <= doc.numPages; i++) {
    const content = await (await doc.getPage(i)).getTextContent();
    let line = '', lastY = null;
    const out = [];
    for (const it of content.items) {
      if (typeof it.str !== 'string') continue;
      const y = it.transform ? Math.round(it.transform[5]) : null;
      if (lastY !== null && y !== null && Math.abs(y - lastY) > 2) { out.push(line); line = ''; }
      line += it.str;
      if (it.hasEOL) { out.push(line); line = ''; }
      lastY = y;
    }
    if (line) out.push(line);
    const t = out.join('\n');
    if (t.replace(/\s/g, '').length < 30) empty++;
    pages.push(`--- page ${i} ---\n${t}`);
    if (pages.join('').length > MAX_TEXT_CHARS) { pages.push('[… the rest of the document is not shown]'); break; }
  }
  await doc.destroy();
  return { text: pages.join('\n\n'), numPages: doc.numPages, empty };
}

// Same fix as the chat: some Japanese PDFs give ASCII symbols instead of circled numbers
const CIRCLED = '①②③④⑤⑥⑦⑧⑨⑩';
const CIRCLED_ASCII = '!"#$%&\'()';
function restoreCircled(text) {
  const japanese = (text.match(/[぀-ヿ一-鿿]/g) || []).length;
  if (japanese < text.length * 0.3) return text;
  const hits = [...CIRCLED_ASCII].filter(c => (text.split(c).length - 1) >= 2).length;
  if (hits < 3) return text;
  return text.replace(/["!#$%&'()]/g, c => CIRCLED[CIRCLED_ASCII.indexOf(c)] || c);
}

// ---------- entry point ----------

const cache = new Map();   // path|mtime|size -> { text, note }; lets the model read a long file in several calls

/** helpers.ocrPdf(absPath, maxPages) -> { text, pages } is used for scanned PDFs */
export async function readDocument(abs, helpers) {
  const st = fs.statSync(abs);
  if (st.size > MAX_DOC_BYTES) throw new Error('The file is too large (over 100 MB).');
  const key = `${abs}|${st.mtimeMs}|${st.size}`;
  if (cache.has(key)) return cache.get(key);

  const ext = path.extname(abs).toLowerCase().slice(1);
  const buf = fs.readFileSync(abs);
  let result;
  if (ext === 'docx') result = docxText(buf);
  else if (ext === 'pptx') result = pptxText(buf);
  else if (ext === 'xlsx') result = xlsxText(buf);
  else if (ext === 'pdf') {
    const r = await pdfText(buf);
    if (r.empty > r.numPages / 2 && helpers?.ocrPdf) {
      const o = await helpers.ocrPdf(abs, 30);
      result = { text: o.text, note: `PDF, ${r.numPages} pages, scanned: read with OCR${o.pages > 30 ? ' (first 30 pages only)' : ''}` };
    } else {
      result = { text: restoreCircled(r.text), note: `PDF, ${r.numPages} pages${r.empty ? `, ${r.empty} without text` : ''}` };
    }
  } else throw new Error(`.${ext} is not a supported document type.`);

  if (!result.text.trim()) throw new Error('No text could be found in this file.');
  if (cache.size >= 6) cache.delete(cache.keys().next().value);
  cache.set(key, result);
  return result;
}
