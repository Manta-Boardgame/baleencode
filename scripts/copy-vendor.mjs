// Copy pdf.js (and the font data it needs for CJK PDFs) into public/vendor and the Android assets.
// Runs automatically after `npm install`.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const src = path.join(root, 'node_modules', 'pdfjs-dist');
const targets = [
  path.join(root, 'public', 'vendor'),
  path.join(root, 'android', 'app', 'src', 'main', 'assets', 'vendor'),
];

for (const dest of targets) {
  fs.rmSync(dest, { recursive: true, force: true });
  fs.mkdirSync(dest, { recursive: true });
  for (const f of ['pdf.min.mjs', 'pdf.worker.min.mjs']) {
    fs.copyFileSync(path.join(src, 'build', f), path.join(dest, f));
  }
  for (const d of ['cmaps', 'standard_fonts']) {
    fs.cpSync(path.join(src, d), path.join(dest, d), { recursive: true });
  }
  console.log('pdf.js copied to', path.relative(root, dest));
}

// The Android app shows the same page as the desktop version
const assets = path.join(root, 'android', 'app', 'src', 'main', 'assets');
for (const f of ['index.html', 'icon.png', 'agent-android.js', 'i18n.js']) {
  fs.copyFileSync(path.join(root, 'public', f), path.join(assets, f));
}
