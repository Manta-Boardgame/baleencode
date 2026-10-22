// Tests for the page reader's safety checks and its HTML-to-text converter. No network: everything here is decided
// before a connection is made, or works on text.   npm test
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { isPrivateAddress, checkUrl, LONG_URL, htmlToText, fetchPage } from '../webfetch.mjs';

test('private, loopback, link-local and other non-public addresses are recognised', () => {
  for (const ip of ['127.0.0.1', '10.1.2.3', '172.16.0.1', '172.31.255.255', '192.168.1.1', '169.254.169.254', '100.64.0.1',
    '0.0.0.0', '198.18.0.1', '224.0.0.1', '255.255.255.255', '::1', '::', 'fc00::1', 'fd12::1', 'fe80::1', 'ff02::1',
    '::ffff:127.0.0.1', '::ffff:10.0.0.1', '::ffff:7f00:1', 'not-an-ip']) {
    assert.equal(isPrivateAddress(ip), true, ip);
  }
  for (const ip of ['8.8.8.8', '1.1.1.1', '172.32.0.1', '100.128.0.1', '2606:4700::1111', '::ffff:8.8.8.8']) {
    assert.equal(isPrivateAddress(ip), false, ip);
  }
});

test('only public http(s) addresses pass; tricks that point at this machine are refused before any connection', () => {
  for (const [u, why] of [
    ['file:///C:/Windows/win.ini', /Only http and https/], ['ftp://example.com/', /Only http and https/],
    ['javascript:alert(1)', /Only http and https/], ['http://localhost:4141/', /public/], ['http://LOCALHOST./', /public/],
    ['http://printer.local/', /public/], ['http://router.home.arpa/', /public/], ['http://127.0.0.1/', /public/],
    ['http://2130706433/', /public/], ['http://0x7f.1/', /public/], ['http://[::1]/', /public/], ['http://[::ffff:127.0.0.1]/', /public/],
    ['http://169.254.169.254/latest/meta-data/', /public/], ['https://user:pw@example.com/', /user name or password/],
    ['not a url', /not a valid/], ['https://example.com/' + 'a'.repeat(2100), /too long/],
  ]) assert.throws(() => checkUrl(u), why, u);
  assert.equal(checkUrl('https://example.com/a#frag').href, 'https://example.com/a');
});

test('long addresses (which could carry data out) are flagged so the tool asks first', () => {
  assert.equal(LONG_URL(new URL('https://example.com/search?q=php')), false);
  assert.equal(LONG_URL(new URL('https://example.com/?q=' + 'x'.repeat(121))), true);
  assert.equal(LONG_URL(new URL('https://example.com/' + 'p'.repeat(300))), true);
});

test('fetchPage refuses a private address without connecting', async () => {
  await assert.rejects(fetchPage('http://127.0.0.1:9/'), /public/);
  await assert.rejects(fetchPage('http://192.168.0.1/'), /public/);
});

test('HTML becomes readable text: headings, lists, tables and entities; scripts, styles and menus are left out', () => {
  const t = htmlToText(`<!DOCTYPE html><html><head><title>Ex &amp; Co</title><style>p{color:red}</style></head><body>
    <nav>Home | About</nav><script>alert("x<y")</script><!-- note -->
    <h2>Section</h2><p>One&nbsp;&lt;two&gt; &#x41;&#66; caf&eacute;</p><ul><li>a</li><li>b</li></ul>
    <table><tr><td>k</td><td>v</td></tr></table><a href="x" title='a > b'>link</a><br>end<footer>foot</footer></body></html>`);
  assert.match(t, /^# Ex & Co\n/);
  assert.match(t, /## Section/);
  assert.match(t, /One <two> AB caf&eacute;/);          // unknown entities stay as they are
  assert.match(t, /- a\n- b/);
  assert.match(t, /\| k \| v/);
  assert.match(t, /link\nend/);
  for (const gone of ['alert', 'color:red', 'Home | About', 'foot', 'note', 'DOCTYPE']) assert.ok(!t.includes(gone), gone);
});

test('the title appears once, also on a page without <head>', () => {
  assert.equal(htmlToText('<title>T</title><p>Hello <b>world</b></p>'), '# T\n\nHello world');
});

test('only the main content is kept when the page marks it', () => {
  const body = '<p>' + 'Article text. '.repeat(50) + '</p>';
  const t = htmlToText(`<div>Sidebar menu</div><main id="content">${body}</main><div>Related links</div>`);
  assert.match(t, /Article text\./);
  assert.ok(!t.includes('Sidebar') && !t.includes('Related'));
});

test('a hostile page cannot make the converter hang (it works in one pass)', () => {
  const pages = ['<script>'.repeat(20000) + 'x'.repeat(1_000_000), '<a href="'.repeat(50000), '<main>'.repeat(30000) + 'y'.repeat(500000),
    '<div>'.repeat(300000), '<'.repeat(1_500_000), '<p '.repeat(400000), '&amp;'.repeat(300000), '<!--'.repeat(300000)];
  const t0 = Date.now();
  for (const p of pages) htmlToText(p);
  assert.ok(Date.now() - t0 < 3000, `took ${Date.now() - t0} ms`);   // the old regular expressions took minutes
});

test('every script the page loads is also copied into the Android app (a missing one leaves the app blank)', () => {
  const html = fs.readFileSync(new URL('../public/index.html', import.meta.url), 'utf8');
  const copy = fs.readFileSync(new URL('../scripts/copy-vendor.mjs', import.meta.url), 'utf8');
  const scripts = [...html.matchAll(/<script src="([^"]+)"/g)].map(m => m[1]).filter(s => !s.startsWith('vendor/'));
  assert.ok(scripts.includes('htmltext.js'));
  assert.ok(scripts.indexOf('htmltext.js') < scripts.indexOf('agent-android.js'), 'htmltext.js must load first');
  for (const s of scripts) {
    if (s === 'lang-ja.js' && !copy.includes("'lang-ja.js'")) continue;      // the English build has no language pack
    assert.ok(copy.includes(`'${s}'`), `${s} is not copied into the Android assets`);
  }
});
