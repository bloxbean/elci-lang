// Checks the built site: every local link and asset resolves, every #anchor exists, and no page
// refers to internal design records.
import { createHash } from 'node:crypto';
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

// The AI artifacts: every page has a Markdown export listed in llms.txt, and every checksum matches.
try {
  const manifest = JSON.parse(await readFile(resolve(dist, 'ai/manifest.json'), 'utf8'));
  const llms = await readFile(resolve(dist, 'llms.txt'), 'utf8');
  const local = (url) => resolve(dist, '.' + new URL(url, 'https://site.invalid').pathname);
  for (const item of [...manifest.pages.map((p) => ({ url: p.markdown, sha256: p.sha256 })), ...manifest.artifacts]) {
    const bytes = await readFile(local(item.url));
    if (createHash('sha256').update(bytes).digest('hex') !== item.sha256) failures.push(`Checksum mismatch: ${item.url}`);
  }
  for (const page of manifest.pages)
    if (!llms.includes(page.markdown)) failures.push(`llms.txt does not list ${page.markdown}`);
  const htmlPages = [...html.keys()].filter((f) => !/\/(404|index)\.html$/.test(f.replace(dist, '')) || /\/[^/]+\/index\.html$/.test(f.replace(dist, '')));
  if (manifest.pages.length < htmlPages.length - 1)
    failures.push(`Only ${manifest.pages.length} Markdown exports for ${htmlPages.length} HTML pages`);
} catch (error) {
  failures.push(`AI artifacts: ${error.message}`);
}

if (failures.length) {
  console.error([...new Set(failures)].join('\n'));
  process.exit(1);
}
console.log(`Checked ${html.size} pages: local links, assets and anchors resolve; AI artifacts are complete.`);
