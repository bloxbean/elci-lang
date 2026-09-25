// Checks the built site: every local link and asset resolves, every #anchor exists, and no page
// refers to internal design records.
import { readFile, readdir, stat } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const dist = resolve(dirname(fileURLToPath(import.meta.url)), '../dist');

async function walk(dir) {
  const files = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = resolve(dir, entry.name);
    files.push(...(entry.isDirectory() ? await walk(path) : [path]));
  }
  return files;
}

const html = new Map();
for (const file of (await walk(dist)).filter((f) => f.endsWith('.html'))) html.set(file, await readFile(file, 'utf8'));
const ids = new Map([...html].map(([file, text]) => [file, new Set([...text.matchAll(/id="([^"]+)"/g)].map((m) => m[1]))]));

const failures = [];
for (const [file, text] of html) {
  const page = file.replace(dist, '');
  if (/\bADR[-\s]?\d+\b|\/adr\//.test(text.replace(/<script[\s\S]*?<\/script>/g, '')))
    failures.push(`Internal design-record reference: ${page}`);
  for (const match of text.matchAll(/(?:href|src)="([^"]+)"/g)) {
    const value = match[1].replaceAll('&amp;', '&');
    if (value.startsWith('#')) {
      const id = decodeURIComponent(value.slice(1));
      if (id && !ids.get(file).has(id)) failures.push(`Missing anchor in ${page}: ${value}`);
      continue;
    }
    if (!value.startsWith('/') || value.startsWith('//')) continue;
    const path = decodeURIComponent(value.split(/[?#]/)[0]);
    const target = resolve(dist, '.' + path);
    try {
      const info = await stat(target);
      const document = info.isDirectory() ? resolve(target, 'index.html') : target;
      if (info.isDirectory()) await stat(document);
      const fragment = value.split('#')[1];
      if (fragment && ids.has(document) && !ids.get(document).has(decodeURIComponent(fragment)))
        failures.push(`Missing anchor in ${page}: ${value}`);
    } catch {
      failures.push(`Broken link in ${page}: ${value}`);
    }
  }
}

if (failures.length) {
  console.error([...new Set(failures)].join('\n'));
  process.exit(1);
}
console.log(`Checked ${html.size} pages: local links, assets and anchors resolve.`);
