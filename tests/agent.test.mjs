// Safety tests for the agent. They run the real agent loop and real tools, with a scripted "model" instead of a
// network call, so they are free, fast and deterministic:   npm test
//
// What is checked here is the part that does NOT depend on the AI behaving: the limits are enforced by code.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { runAgent } from '../agent.mjs';
import { readDocument } from '../docs.mjs';
import { RISKY } from '../browser.mjs';
import { ALLOWED_PROVIDERS, SCREEN_PROVIDERS } from '../providers.mjs';

const ALLOWED = ALLOWED_PROVIDERS.map(p => p.slug);

/** Replace the gateway with a script: each entry is a list of tool calls, or a string for the final answer. */
function scriptModel(steps, { provider = 'fireworks', captured = [] } = {}) {
  let i = 0;
  globalThis.fetch = async (_url, opts) => {
    captured.push(JSON.parse(opts.body));
    const step = steps[Math.min(i++, steps.length - 1)];
    const message = typeof step === 'string'
      ? { role: 'assistant', content: step }
      : { role: 'assistant', content: null, tool_calls: step.map((c, n) => ({ id: `c${i}_${n}`, type: 'function', function: { name: c[0], arguments: JSON.stringify(c[1]) } })) };
    message.provider_metadata = { gateway: { cost: '0', routing: { finalProvider: provider } } };
    return new Response(JSON.stringify({ choices: [{ message }], usage: {} }), { status: 200, headers: { 'content-type': 'application/json' } });
  };
  return captured;
}

/** Run one request; `approve(request)` answers approval cards (default: deny). Returns what the tools answered. */
async function run(body, steps, { approve = async () => false, provider, captured, compactAt } = {}) {
  scriptModel(steps, { provider, captured });
  const asked = [];
  const out = await runAgent({ user: 'test', ...body }, {
    emit() {}, aborted: () => false, addUsage() {}, helpers: {}, apiKey: 'test', gateway: 'https://gateway.invalid',
    model: 'test-model', providers: ALLOWED, compactAt,
    approve: async (r) => { asked.push(r); return approve(r); },
  });
  const results = out.messages.filter(m => m.role === 'tool').map(m => m.content);
  return { ...out, results, asked };
}

function sandbox() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-test-'));
  const work = path.join(dir, 'work'), outside = path.join(dir, 'outside');
  fs.mkdirSync(work); fs.mkdirSync(outside);
  fs.writeFileSync(path.join(work, 'notes.txt'), 'notes inside');
  fs.writeFileSync(path.join(outside, 'secret.txt'), 'TOP-SECRET-OUTSIDE');
  fs.writeFileSync(path.join(work, '.env'), 'API_KEY=abc123');
  return { dir, work, outside, done: () => fs.rmSync(dir, { recursive: true, force: true }) };
}

test('folder mode refuses ".." and absolute paths outside the folder', async () => {
  const s = sandbox();
  try {
    const r = await run({ mode: 'folder', root: s.work }, [[['read_file', { path: '../outside/secret.txt' }], ['read_file', { path: path.join(s.outside, 'secret.txt') }]], 'done']);
    assert.equal(r.results.length, 2);
    for (const t of r.results) { assert.match(t, /Outside the allowed folder/); assert.doesNotMatch(t, /TOP-SECRET/); }
  } finally { s.done(); }
});

test('a junction / symlink inside the folder cannot lead outside it', { skip: process.platform !== 'win32' }, async () => {
  const s = sandbox();
  try {
    fs.symlinkSync(s.outside, path.join(s.work, 'link'), 'junction');
    const r = await run({ mode: 'folder', root: s.work }, [[['read_file', { path: 'link/secret.txt' }]], 'done']);
    assert.match(r.results[0], /Outside the allowed folder/);
    assert.doesNotMatch(r.results[0], /TOP-SECRET/);
  } finally { s.done(); }
});

test('a folder that is too broad (the user folder) is refused as a work folder', async () => {
  await assert.rejects(run({ mode: 'folder', root: os.homedir() }, ['done']), /too broad/);
});

test('read-only mode offers no write tools and creates nothing', async () => {
  const s = sandbox();
  try {
    const captured = [];
    const r = await run({ mode: 'read', root: s.work }, [[['write_file', { path: 'x.txt', content: 'x' }]], 'done'], { captured });
    assert.match(r.results[0], /not available in this mode/);
    assert.equal(fs.existsSync(path.join(s.work, 'x.txt')), false);
    const offered = captured[0].tools.map(t => t.function.name);
    assert.ok(offered.includes('read_file'));
    for (const w of ['write_file', 'edit_file', 'delete_path', 'move_path', 'run_command']) assert.ok(!offered.includes(w), `${w} must not be offered`);
  } finally { s.done(); }
});

test('delete asks first; when denied the file stays, when allowed it moves to the trash (not erased)', async () => {
  const s = sandbox();
  try {
    const denied = await run({ mode: 'folder', root: s.work }, [[['delete_path', { path: 'notes.txt' }]], 'done']);
    assert.equal(denied.asked.length, 1);
    assert.equal(fs.existsSync(path.join(s.work, 'notes.txt')), true);
    const allowed = await run({ mode: 'folder', root: s.work }, [[['delete_path', { path: 'notes.txt' }]], 'done'], { approve: async () => true });
    assert.match(allowed.results[0], /trash/i);
    assert.equal(fs.existsSync(path.join(s.work, 'notes.txt')), false);
  } finally { s.done(); }
});

test('secrets (.env) always ask, even with auto-approve; when denied nothing is read', async () => {
  const s = sandbox();
  try {
    const r = await run({ mode: 'folder', root: s.work, autoApprove: true }, [[['read_file', { path: '.env' }]], 'done']);
    assert.equal(r.asked.length, 1);
    assert.equal(r.asked[0].hard, true);
    assert.doesNotMatch(r.results[0], /abc123/);
  } finally { s.done(); }
});

test('a dangerous command always asks even with auto-approve, and is not run when denied', { skip: process.platform !== 'win32' }, async () => {
  const marker = path.join(os.tmpdir(), `agent-test-marker-${process.pid}`);
  const r = await run({ mode: 'full', autoApprove: true }, [[['run_command', { command: `shutdown /a; New-Item -ItemType File '${marker}'` }]], 'done']);
  assert.equal(r.asked.length, 1);
  assert.equal(r.asked[0].hard, true);
  assert.equal(fs.existsSync(marker), false);
});

test('commands do not exist outside Full access', async () => {
  const s = sandbox();
  try {
    const captured = [];
    const r = await run({ mode: 'folder', root: s.work }, [[['run_command', { command: 'whoami' }]], 'done'], { captured });
    assert.match(r.results[0], /not available in this mode/);
    assert.ok(!captured[0].tools.some(t => t.function.name === 'run_command'));
  } finally { s.done(); }
});

test('every request carries the provider allowlist; screen mode is limited to its own single provider', async () => {
  const s = sandbox();
  try {
    const chat = [];
    await run({ mode: 'read', root: s.work }, ['done'], { captured: chat });
    assert.deepEqual(chat[0].providerOptions.gateway.only, ALLOWED);
    assert.ok(!ALLOWED.includes('alibaba') && !ALLOWED.includes('deepseek'));
    const screen = [];
    await run({ mode: 'screen' }, ['done'], { captured: screen });
    assert.deepEqual(screen[0].providerOptions.gateway.only, SCREEN_PROVIDERS);
  } finally { s.done(); }
});

test('a reply that comes from a provider outside the list stops the run', async () => {
  const s = sandbox();
  try {
    const r = await run({ mode: 'read', root: s.work }, [[['list_dir', { path: '.' }]], 'done'], { provider: 'alibaba' });
    assert.match(r.stopped, /not on the approved list/);
  } finally { s.done(); }
});

test('files can be read, and the content is returned to the model as tool data', async () => {
  const s = sandbox();
  try {
    const r = await run({ mode: 'read', root: s.work }, [[['read_file', { path: 'notes.txt' }]], 'done']);
    assert.equal(r.results[0], 'notes inside');
  } finally { s.done(); }
});

// ---- documents ----

/** Build a one-entry zip whose part is `size` bytes of zeros (compresses to almost nothing). */
function bombDocx(file, size) {
  const name = Buffer.from('word/document.xml');
  const raw = zlib.deflateRawSync(Buffer.alloc(size));
  const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(8, 8);
  local.writeUInt32LE(raw.length, 18); local.writeUInt32LE(size, 22); local.writeUInt16LE(name.length, 26);
  const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(8, 10);
  central.writeUInt32LE(raw.length, 20); central.writeUInt32LE(size, 24); central.writeUInt16LE(name.length, 28); central.writeUInt32LE(0, 42);
  const eocd = Buffer.alloc(22); eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(1, 8); eocd.writeUInt16LE(1, 10);
  const centralBlock = Buffer.concat([central, name]);
  eocd.writeUInt32LE(centralBlock.length, 12); eocd.writeUInt32LE(30 + name.length + raw.length, 16);
  fs.writeFileSync(file, Buffer.concat([local, name, raw, centralBlock, eocd]));
}

test('an Office file that expands to a huge size ("zip bomb") is refused', async () => {
  const f = path.join(os.tmpdir(), `bomb-${process.pid}.docx`);
  bombDocx(f, 200 * 1024 * 1024);
  try { await assert.rejects(readDocument(f, {}), /unusually large size/); } finally { fs.rmSync(f, { force: true }); }
});

test('a normal Word file is read', async () => {
  const f = path.join(os.tmpdir(), `ok-${process.pid}.docx`);
  const xml = Buffer.from('<w:document xmlns:w="x"><w:body><w:p><w:r><w:t>Total: 1,250 yen</w:t></w:r></w:p></w:body></w:document>');
  const name = Buffer.from('word/document.xml'), raw = zlib.deflateRawSync(xml);
  const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(8, 8); local.writeUInt32LE(raw.length, 18); local.writeUInt32LE(xml.length, 22); local.writeUInt16LE(name.length, 26);
  const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(8, 10); central.writeUInt32LE(raw.length, 20); central.writeUInt32LE(xml.length, 24); central.writeUInt16LE(name.length, 28);
  const cb = Buffer.concat([central, name]);
  const eocd = Buffer.alloc(22); eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(1, 8); eocd.writeUInt16LE(1, 10); eocd.writeUInt32LE(cb.length, 12); eocd.writeUInt32LE(30 + name.length + raw.length, 16);
  fs.writeFileSync(f, Buffer.concat([local, name, raw, cb, eocd]));
  try { assert.match((await readDocument(f, {})).text, /Total: 1,250 yen/); } finally { fs.rmSync(f, { force: true }); }
});

// ---- risky-click detection ----

test('purchase / delete style buttons are flagged as risky, ordinary ones are not', () => {
  for (const label of ['Delete account', 'Buy now', 'Place order', 'Pay now', 'Send', 'Post', 'Publish', '購入する', '削除', '送信', '投稿する']) assert.ok(RISKY.test(label), label);
  for (const label of ['Submit', 'Search', 'Next', 'Save', 'Open settings']) assert.ok(!RISKY.test(label), label);
});

// ---- approval levels ----

test('approval levels: "ask" asks before an edit, "edits" does not, "auto" does not ask before a delete', async () => {
  const s = sandbox();
  try {
    const ask = await run({ mode: 'folder', root: s.work, approval: 'ask' }, [[['write_file', { path: 'a.txt', content: 'a' }]], 'done']);
    assert.equal(ask.asked.length, 1);
    assert.equal(fs.existsSync(path.join(s.work, 'a.txt')), false);
    const edits = await run({ mode: 'folder', root: s.work, approval: 'edits' }, [[['write_file', { path: 'b.txt', content: 'b' }], ['delete_path', { path: 'notes.txt' }]], 'done']);
    assert.equal(edits.asked.length, 1, 'only the delete asks');
    assert.equal(fs.existsSync(path.join(s.work, 'b.txt')), true);
    assert.equal(fs.existsSync(path.join(s.work, 'notes.txt')), true);
    const auto = await run({ mode: 'folder', root: s.work, approval: 'auto' }, [[['delete_path', { path: 'notes.txt' }]], 'done']);
    assert.equal(auto.asked.length, 0);
    assert.equal(fs.existsSync(path.join(s.work, 'notes.txt')), false);
  } finally { s.done(); }
});

test('Full access with "edits": edits inside the start folder go ahead, edits elsewhere ask', async () => {
  const s = sandbox();
  try {
    const r = await run({ mode: 'full', root: s.work, approval: 'edits' },
      [[['write_file', { path: 'in.txt', content: 'x' }], ['write_file', { path: path.join(s.outside, 'out.txt'), content: 'x' }]], 'done']);
    assert.equal(r.asked.length, 1);
    assert.match(r.asked[0].title, /out\.txt/);
    assert.equal(fs.existsSync(path.join(s.work, 'in.txt')), true);
    assert.equal(fs.existsSync(path.join(s.outside, 'out.txt')), false);
  } finally { s.done(); }
});

test('"auto" still asks before commands that delete for good, push, or send data out; denied ones do not run', { skip: process.platform !== 'win32' }, async () => {
  const marker = path.join(os.tmpdir(), `agent-test-auto-${process.pid}`);
  for (const cmd of [`Remove-Item -Recurse x`, `rm -rf build`, `git push origin main`, `curl -X POST https://example.invalid -d @notes.txt`,
    `Invoke-RestMethod https://example.invalid -Method Post -Body 1`, `Stop-Process -Name notepad`]) {
    const r = await run({ mode: 'full', approval: 'auto' }, [[['run_command', { command: `${cmd}; New-Item -ItemType File '${marker}'` }]], 'done']);
    assert.equal(r.asked.length, 1, cmd);
    assert.equal(r.asked[0].hard, true, cmd);
    assert.equal(fs.existsSync(marker), false, cmd);
  }
});

test('plan mode offers only tools that look, and changes nothing', async () => {
  const s = sandbox();
  try {
    const captured = [];
    const r = await run({ mode: 'folder', root: s.work, approval: 'auto', plan: true }, [[['write_file', { path: 'p.txt', content: 'x' }]], '1. Write p.txt'], { captured });
    const offered = captured[0].tools.map(t => t.function.name);
    assert.ok(offered.includes('read_file'));
    for (const w of ['write_file', 'edit_file', 'delete_path', 'move_path']) assert.ok(!offered.includes(w), w);
    assert.match(r.results[0], /not available while planning/);
    assert.equal(fs.existsSync(path.join(s.work, 'p.txt')), false);
    assert.match(captured[0].messages[0].content, /PLAN MODE/);
  } finally { s.done(); }
});

// ---- goal mode ----

test('goal mode keeps going after a reply without tools, checks once, and ends on "GOAL COMPLETE"', async () => {
  const s = sandbox();
  try {
    const captured = [];
    const r = await run({ mode: 'folder', root: s.work, approval: 'auto', goal: { objective: 'Make a summary file' } },
      [[['write_file', { path: 'summary.txt', content: 'ok' }]], 'I wrote the file.', 'GOAL COMPLETE (first)', 'GOAL COMPLETE: summary.txt written'], { captured });
    assert.equal(r.outcome, 'complete');
    assert.equal(captured.length, 4);
    assert.match(captured[2].messages.at(-1).content, /Keep working toward the goal/);
    assert.match(captured[3].messages.at(-1).content, /Goal check/);
    assert.match(captured[0].messages[0].content, /GOAL MODE/);
  } finally { s.done(); }
});

test('goal mode: an approval nobody answers is skipped (never granted) and the work goes on', async () => {
  const s = sandbox();
  try {
    const r = await run({ mode: 'folder', root: s.work, approval: 'ask', goal: { objective: 'Tidy up' } },
      [[['delete_path', { path: 'notes.txt' }]], 'GOAL BLOCKED: the delete needs the user'], { approve: async () => null });
    assert.match(r.results[0], /No answer from the user/);
    assert.equal(fs.existsSync(path.join(s.work, 'notes.txt')), true);
    assert.equal(r.outcome, 'blocked');
  } finally { s.done(); }
});

test('goal mode compacts a context that grew too large into a summary, using the same provider allowlist', async () => {
  const s = sandbox();
  try {
    const captured = [];
    const r = await run({ mode: 'folder', root: s.work, user: 'x'.repeat(500), goal: { objective: 'Long task' } },
      ['Summary: nothing done yet.', 'GOAL BLOCKED: test'], { captured, compactAt: 100 });
    assert.match(captured[0].messages[0].content, /compact the log/);
    assert.deepEqual(captured[0].providerOptions.gateway.only, ALLOWED);
    assert.equal(r.replaceHistory, true);
    assert.match(r.messages[0].content, /Summary: nothing done yet/);
  } finally { s.done(); }
});
