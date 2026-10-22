// Local server for BaleenCode (listens on 127.0.0.1 only, so it is reachable from this PC only)
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { exec, execFile, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { ALLOWED_PROVIDERS, BLOCKED_PROVIDERS, MODEL, GATEWAY } from './providers.mjs';
import { runAgent, SEARCH_TOOL } from './agent.mjs';
import { fetchPage } from './webfetch.mjs';

const PORT = 4141;
const MAX_BODY_BYTES = 110 * 1024 * 1024; // uploads larger than this are rejected
const MAX_PROMPT_CHARS = 2_000_000;       // the model takes ~1M tokens; this is a sanity cap
const ALLOWED_HOSTS = new Set([`localhost:${PORT}`, `127.0.0.1:${PORT}`]);
const LEDGER = new URL('./usage-ledger.json', import.meta.url);
const PUBLIC_DIR = fileURLToPath(new URL('./public/', import.meta.url));
const scriptPath = (name) => fileURLToPath(new URL(`./scripts/windows/${name}`, import.meta.url));

// The app was called "DeepSeek Agent" before; move its data folder (trash, backups, browser profile) to the new name once
try {
  const local = process.env.LOCALAPPDATA ?? os.homedir();
  const oldDir = path.join(local, 'DeepSeekChat'), newDir = path.join(local, 'BaleenCode');
  // Merge into the new folder without overwriting anything; the old folder is removed only when it is empty
  const merge = (from, to) => {
    fs.mkdirSync(to, { recursive: true });
    for (const name of fs.readdirSync(from)) {
      const a = path.join(from, name), b = path.join(to, name);
      try {
        if (!fs.existsSync(b)) fs.renameSync(a, b);
        else if (fs.statSync(a).isDirectory() && fs.statSync(b).isDirectory()) merge(a, b);
      } catch {}
    }
    try { if (!fs.readdirSync(from).length) fs.rmdirSync(from); } catch {}
  };
  if (fs.existsSync(oldDir)) merge(oldDir, newDir);
} catch {}

function loadLedger() {
  try { const l = JSON.parse(fs.readFileSync(LEDGER, 'utf8')); return { chargedUsd: 0, marketUsd: 0, ...l }; }
  catch { return { inputTokens: 0, outputTokens: 0, chargedUsd: 0, marketUsd: 0, calls: 0 }; }
}

// Actual (free) credit balance on Vercel, cached for 60 seconds
let creditsCache = null, creditsAt = 0, creditsKey = '';
async function getCredits(apiKey, force = false) {
  if (!apiKey) return null;
  if (!force && creditsCache && creditsKey === apiKey && Date.now() - creditsAt < 60_000) return creditsCache;
  try {
    const r = await fetch(`${GATEWAY}/v1/credits`, { headers: { Authorization: `Bearer ${apiKey}` } });
    if (!r.ok) return creditsCache;
    const j = await r.json();
    creditsCache = { balance: Number(j.balance), used: Number(j.total_used) };
    creditsAt = Date.now();
    creditsKey = apiKey;
  } catch {}
  return creditsCache;
}

function addUsage({ inTok, outTok, cost, market }) {
  const l = loadLedger();
  l.calls = (l.calls ?? 0) + 1;
  l.inputTokens = (l.inputTokens ?? 0) + inTok;
  l.outputTokens = (l.outputTokens ?? 0) + outTok;
  l.chargedUsd = (l.chargedUsd ?? 0) + cost;
  l.marketUsd = (l.marketUsd ?? 0) + market;
  fs.writeFileSync(LEDGER, JSON.stringify(l, null, 2));
}

function runPowerShell(script, args, maxBuffer, sta = false) {
  return new Promise((resolve, reject) => {
    execFile('powershell.exe',
      ['-NoProfile', ...(sta ? ['-STA'] : []), '-ExecutionPolicy', 'Bypass', '-File', scriptPath(script), ...args],
      { encoding: 'utf8', maxBuffer },
      (err, stdout, stderr) => {
        if (!err) return resolve(stdout);
        // Show only the first line of the script's error; PowerShell appends file paths and positions after it
        const first = String(stderr ?? '').split(/\r?\n/).map(l => l.trim()).find(Boolean) ?? '';
        reject(new Error(/^[\x20-\x7E]+$/.test(first) ? first : 'The file could not be processed.'));
      });
  });
}

/**
 * Keep Windows awake while a long goal runs (the screen may still turn off). The helper process ends when it is
 * stopped, or by itself when this server exits. Returns the stop function.
 */
function keepAwake() {
  let child;
  try {
    child = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', scriptPath('keepawake.ps1'), '-ParentPid', String(process.pid)],
      { stdio: 'ignore', windowsHide: true });
    child.on('error', () => {});
  } catch { return () => {}; }
  return () => { try { child.kill(); } catch {} };
}

// Read text from an image with the built-in Windows OCR (runs on this PC; the image is never uploaded)
async function ocrImage(buffer, ext) {
  const file = path.join(os.tmpdir(), `ds-ocr-${Date.now()}.${ext || 'png'}`);
  fs.writeFileSync(file, buffer);
  try {
    return await runPowerShell('ocr.ps1', ['-Path', file], 20 * 1024 * 1024);
  } finally {
    try { fs.unlinkSync(file); } catch {}
  }
}

// Render each PDF page to PNG with the built-in Windows PDF renderer; returns the page count
async function pdfToPng(pdfPath, outDir) {
  const out = await runPowerShell('pdf2png.ps1', ['-Path', pdfPath, '-OutDir', outDir], 10 * 1024 * 1024);
  return Number(String(out).trim().split(/\r?\n/)[0]);
}

// OCR a scanned PDF from disk (used by Agent mode): render the pages, read each, clean up
async function ocrPdfFile(pdfPath, maxPages = 30) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ds-agent-pdf-'));
  try {
    const src = path.join(dir, 'src.pdf');
    fs.copyFileSync(pdfPath, src);
    const pages = await pdfToPng(src, dir);
    const out = [];
    for (let i = 1; i <= Math.min(pages, maxPages); i++) {
      const png = path.join(dir, `p${String(i).padStart(4, '0')}.png`);
      out.push(`--- page ${i} ---\n${(await ocrImage(fs.readFileSync(png), 'png')).trim()}`);
    }
    return { text: out.join('\n\n'), pages };
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

// Rendered PDF pages are kept temporarily and removed after 30 minutes
const pdfJobs = new Map();
function cleanJobs() {
  const now = Date.now();
  for (const [id, job] of pdfJobs) {
    if (now - job.at > 30 * 60 * 1000) {
      pdfJobs.delete(id);
      try { fs.rmSync(job.dir, { recursive: true, force: true }); } catch {}
    }
  }
}

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

const pendingApprovals = new Map();   // approvalId -> resolve(boolean | null)
const agentRuns = new Map();          // runId -> { stop, mine } for "stop after the current step"

class TooLarge extends Error {}

async function readBody(req) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > MAX_BODY_BYTES) throw new TooLarge('File is too large.');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

// Only accept requests from this app's own page.
// - Host check blocks DNS-rebinding attacks from other websites.
// - Origin check blocks other websites from sending requests to this local server.
function isTrusted(req) {
  if (!ALLOWED_HOSTS.has(String(req.headers.host ?? ''))) return false;
  const origin = req.headers.origin;
  if (origin && !ALLOWED_HOSTS.has(origin.replace(/^https?:\/\//, ''))) return false;
  const site = req.headers['sec-fetch-site'];
  if (site && site !== 'same-origin' && site !== 'none') return false;
  return true;
}

const STATIC_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.png': 'image/png',
};

function serveStatic(req, res) {
  const rel = decodeURIComponent(new URL(req.url, 'http://localhost').pathname).replace(/^\/+/, '') || 'index.html';
  const file = path.resolve(PUBLIC_DIR, rel);
  if (!file.startsWith(PUBLIC_DIR) || !fs.existsSync(file) || !fs.statSync(file).isFile()) return false;
  res.writeHead(200, { 'Content-Type': STATIC_TYPES[path.extname(file)] ?? 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
  return true;
}

/** Build the upstream request. The provider allowlist is set here, not by the page. */
function buildChatRequest(body) {
  const messages = (Array.isArray(body.messages) ? body.messages : [])
    .filter(m => m && typeof m.content === 'string' && ['user', 'assistant', 'system'].includes(m.role))
    .map(m => ({ role: m.role, content: m.content }));
  if (!messages.length) throw new Error('No message to send.');
  const chars = messages.reduce((t, m) => t + m.content.length, 0);
  if (chars > MAX_PROMPT_CHARS) throw new Error('The conversation is too long. Start a new chat or remove the attached file.');

  const effort = ['none', 'low', 'high', 'max'].includes(body.effort) ? body.effort : 'none';
  return {
    model: MODEL,
    messages,
    stream: true,
    stream_options: { include_usage: true },
    max_tokens: Math.min(Number(body.maxTokens) || 8192, 32768),
    reasoning: { effort },
    // Web search (opt-in): AI Gateway runs Parallel's search (US); only the search words leave
    ...(body.webSearch === true ? { tools: [SEARCH_TOOL], tool_choice: 'auto' } : {}),
    // Security: route only to the allowed providers. If none of them can serve the
    // request, the gateway returns an error instead of falling back elsewhere.
    providerOptions: { gateway: { only: ALLOWED_PROVIDERS.map(p => p.slug) } },
  };
}

// Exit automatically when the page stops pinging (i.e. the window was closed)
const IDLE_EXIT_MS = 3 * 60 * 1000;
let lastSeen = Date.now();
setInterval(() => { if (Date.now() - lastSeen > IDLE_EXIT_MS) process.exit(0); }, 30 * 1000);

const server = http.createServer(async (req, res) => {
  if (!isTrusted(req)) { res.writeHead(403); return res.end(); }
  lastSeen = Date.now();
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  try {
    await handle(req, res);
  } catch (e) {
    if (res.headersSent) return res.end();
    if (e instanceof TooLarge) return send(res, 413, { error: 'The file is too large (limit 100 MB).' });
    return send(res, 500, { error: 'Unexpected error.' });
  }
});

async function handle(req, res) {
  if (req.url === '/api/ping') { res.writeHead(204); return res.end(); }

  if (req.method === 'GET' && req.url === '/api/providers') {
    return send(res, 200, { model: MODEL, allowed: ALLOWED_PROVIDERS, blocked: BLOCKED_PROVIDERS });
  }

  if (req.method === 'GET' && req.url === '/api/usage') {
    const apiKey = (req.headers['x-api-key'] || '').toString();
    return send(res, 200, { ...loadLedger(), credits: await getCredits(apiKey) });
  }

  // Stream a chat reply from the gateway straight through to the page
  if (req.method === 'POST' && req.url === '/api/chat') {
    const raw = (await readBody(req)).toString('utf8');
    let upstream;
    try {
      const apiKey = (req.headers['x-api-key'] || '').toString().trim();
      if (!apiKey) throw new Error('No API key set. Use "API key" at the top right to set one.');
      const payload = buildChatRequest(JSON.parse(raw));
      upstream = await fetch(`${GATEWAY}/v1/chat/completions`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
    } catch (e) {
      return send(res, 400, { error: e?.message ?? String(e) });
    }

    if (!upstream.ok || !upstream.body) {
      const text = await upstream.text().catch(() => '');
      let message = text.slice(0, 400);
      try { message = JSON.parse(text).error.message; } catch {}
      if (/only.*filter/i.test(message)) {
        message = 'Stopped: none of the allowed providers can serve this request right now. '
          + 'Nothing was sent to a provider outside the allowlist.';
      }
      return send(res, upstream.status === 402 ? 402 : 400, { error: message });
    }

    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store' });
    const ledger = loadLedger();
    let buffer = '';
    for await (const chunk of upstream.body) {
      const text = Buffer.from(chunk).toString('utf8');
      res.write(text);
      // Watch the stream for the final usage/cost so the ledger stays accurate
      buffer = (buffer + text).slice(-20000);
      for (const line of buffer.split('\n')) {
        if (!line.startsWith('data: ') || line.includes('[DONE]')) continue;
        try {
          const j = JSON.parse(line.slice(6));
          const gw = (j.choices?.[0]?.delta?.provider_metadata ?? j.provider_metadata)?.gateway;
          if (gw?.cost !== undefined) { ledger.lastCost = Number(gw.cost); ledger.lastMarket = Number(gw.marketCost ?? 0); }
          if (j.usage) { ledger.lastIn = j.usage.prompt_tokens ?? 0; ledger.lastOut = j.usage.completion_tokens ?? 0; }
        } catch {}
      }
    }
    res.end();

    ledger.calls += 1;
    ledger.inputTokens = (ledger.inputTokens ?? 0) + (ledger.lastIn ?? 0);
    ledger.outputTokens = (ledger.outputTokens ?? 0) + (ledger.lastOut ?? 0);
    ledger.chargedUsd = (ledger.chargedUsd ?? 0) + (ledger.lastCost ?? 0);
    ledger.marketUsd = (ledger.marketUsd ?? 0) + (ledger.lastMarket ?? 0);
    delete ledger.lastCost; delete ledger.lastMarket; delete ledger.lastIn; delete ledger.lastOut;
    fs.writeFileSync(LEDGER, JSON.stringify(ledger, null, 2));
    return;
  }

  // Chat with web search on: reads the page of an address the user pasted (public pages only; see webfetch.mjs)
  if (req.method === 'POST' && req.url === '/api/fetch') {
    let b; try { b = JSON.parse((await readBody(req)).toString('utf8')); } catch { return send(res, 400, { error: 'Bad request.' }); }
    try { return send(res, 200, { text: await fetchPage(String(b.url ?? ''), Number(b.offset) || 0) }); }
    catch (e) { return send(res, 400, { error: String(e?.message ?? e) }); }
  }

  // ---- Agent mode ----
  if (req.method === 'POST' && req.url === '/api/pickfolder') {
    try {
      const out = (await runPowerShell('pickfolder.ps1', [], 1024 * 1024, true)).trim();
      return send(res, 200, { path: out });
    } catch (e) { return send(res, 400, { error: 'Could not open the folder picker.' }); }
  }

  if (req.method === 'POST' && req.url === '/api/agent/approve') {
    let b; try { b = JSON.parse((await readBody(req)).toString('utf8')); } catch { return send(res, 400, { error: 'Bad request.' }); }
    const p = pendingApprovals.get(String(b.id));
    if (p) { pendingApprovals.delete(String(b.id)); p(b.allow === true); }
    return send(res, 200, { ok: true });
  }

  // Stop after the current step: the run ends normally, so the work so far is returned and kept
  if (req.method === 'POST' && req.url === '/api/agent/stop') {
    let b; try { b = JSON.parse((await readBody(req)).toString('utf8')); } catch { return send(res, 400, { error: 'Bad request.' }); }
    const run = agentRuns.get(String(b.id));
    if (run) {
      run.stop = true;
      for (const id of run.mine) { const p = pendingApprovals.get(id); if (p) { pendingApprovals.delete(id); p(false); } }
    }
    return send(res, 200, { ok: true });
  }

  if (req.method === 'POST' && req.url === '/api/agent') {
    let body;
    try { body = JSON.parse((await readBody(req)).toString('utf8')); } catch { return send(res, 400, { error: 'Bad request.' }); }
    const apiKey = (req.headers['x-api-key'] || '').toString().trim();
    if (!apiKey) return send(res, 400, { error: 'No API key set. Use "API key" at the top right to set one.' });

    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store' });
    let closed = false;
    const mine = new Set();
    res.on('close', () => { closed = true; for (const id of mine) { const p = pendingApprovals.get(id); if (p) { pendingApprovals.delete(id); p(false); } } });
    const emit = (ev) => { lastSeen = Date.now(); if (!closed) res.write(`data: ${JSON.stringify(ev)}

`); };
    const runId = Math.random().toString(36).slice(2, 12);
    const run = { stop: false, mine };
    agentRuns.set(runId, run);
    emit({ type: 'run', id: runId });
    const io = {
      emit, apiKey, gateway: GATEWAY, model: MODEL, providers: ALLOWED_PROVIDERS.map(p => p.slug),
      helpers: { ocr: ocrImage, ocrPdf: ocrPdfFile, ps: (script, args) => runPowerShell(script, args, 40 * 1024 * 1024), keepAwake }, addUsage,
      aborted: () => closed || run.stop,
      // Resolves true / false, or null when nobody answered in time (a goal then skips the action; it is never granted)
      approve: (r) => new Promise((resolve) => {
        const { timeoutMs, ...card } = r;
        const id = `${r.id}-${Math.random().toString(36).slice(2, 8)}`;
        mine.add(id);
        const wait = Math.min(Math.max(Number(timeoutMs) || 10 * 60 * 1000, 60 * 1000), 60 * 60 * 1000);
        const timer = setTimeout(() => { pendingApprovals.delete(id); emit({ type: 'approval_expired', approvalId: id }); resolve(null); }, wait);
        pendingApprovals.set(id, (v) => { clearTimeout(timer); resolve(v); });
        emit({ type: 'approval', ...card, approvalId: id, expiresIn: wait });
      }),
    };
    try {
      const out = await runAgent(body, io);
      emit({ type: 'done', ...out });
    } catch (e) {
      emit({ type: 'error', error: e?.message ?? String(e) });
    } finally {
      agentRuns.delete(runId);
    }
    return res.end();
  }

  // Receive a PDF and render its pages to images for OCR
  if (req.method === 'POST' && req.url === '/api/pdfprep') {
    const data = await readBody(req);
    try {
      cleanJobs();
      const id = `ds-pdf-${Date.now()}`;
      const dir = path.join(os.tmpdir(), id);
      fs.mkdirSync(dir, { recursive: true });
      const pdfPath = path.join(dir, 'src.pdf');
      fs.writeFileSync(pdfPath, data);
      const pages = await pdfToPng(pdfPath, dir);
      pdfJobs.set(id, { dir, at: Date.now() });
      return send(res, 200, { id, pages });
    } catch (e) {
      return send(res, 400, { error: 'Could not convert the PDF to images. ' + (e?.message ?? '') });
    }
  }

  // OCR one rendered PDF page
  if (req.method === 'GET' && req.url.startsWith('/api/pdfpage')) {
    const q = new URL(req.url, 'http://localhost').searchParams;
    const job = pdfJobs.get(q.get('id') ?? '');
    if (!job) return send(res, 400, { error: 'PDF job not found. Please load the file again.' });
    try {
      const file = path.join(job.dir, `p${String(Number(q.get('page'))).padStart(4, '0')}.png`);
      const text = await ocrImage(fs.readFileSync(file), 'png');
      job.at = Date.now();
      return send(res, 200, { text });
    } catch (e) {
      return send(res, 400, { error: 'Could not read the page. ' + (e?.message ?? '') });
    }
  }

  if (req.method === 'POST' && req.url === '/api/ocr') {
    const data = await readBody(req);
    try {
      const ext = (req.headers['x-file-ext'] || 'png').toString().replace(/[^a-z0-9]/gi, '').slice(0, 5);
      return send(res, 200, { text: await ocrImage(data, ext) });
    } catch (e) {
      return send(res, 400, { error: 'Could not read text from the image. ' + (e?.message ?? '') });
    }
  }

  if (req.method === 'GET' && serveStatic(req, res)) return;
  res.writeHead(404); res.end();
}

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    // Already running: just open the page
    if (!process.env.NO_OPEN) exec(`start "" http://localhost:${PORT}`);
    process.exit(0);
  } else throw e;
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`BaleenCode is running at http://localhost:${PORT} (close this window to stop)`);
  if (!process.env.NO_OPEN) exec(`start "" http://localhost:${PORT}`);
});
