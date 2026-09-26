// Astro integration that publishes Elci's AI-ingestion artifacts next to the site:
//
//   /llms.txt                  index of the documentation (llmstxt.org convention)
//   /llms-full.txt             every page as one Markdown file
//   /ai/pages/<slug>.md        each page as plain Markdown
//   /ai/starter-pack.md        the AI starter pack (also a page)
//   /ai/agent-instructions.md  compact rules for AGENTS.md / CLAUDE.md / Cursor rules
//   /ai/catalog.json           module and library signatures
//   /ai/diagnostics.json       ELCI diagnostic codes
//   /ai/examples.json          the example projects, with their full sources
//   /ai/manifest.json          versions, source revision and checksums of everything above
//
// Everything is generated from the documentation pages and the repository (bundled Elci
// modules, stdlib inventory, diagnostic catalogue, examples), so it cannot drift from them.
// `astro build` writes the files into the output directory; `astro dev` serves them on request.
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const www = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(www, '..');
const docs = resolve(www, 'src/content/docs');
// Reading order: the sidebar's sections.
const SECTIONS = ['start', 'ai', 'learn', 'contracts', 'testing', 'tutorials', 'advanced', 'reference'];
const SECTION_TITLES = {
  start: 'Start here',
  ai: 'Build with AI',
  learn: 'Learn the language',
  contracts: 'Write contracts',
  testing: 'Test your contracts',
  tutorials: 'Tutorials',
  advanced: 'Advanced',
  reference: 'Reference',
};
// Examples without a README of their own.
const EXAMPLE_SUMMARIES = {
  threshold: 'A spending validator comparing datum and redeemer, using a bundled Julc Java library (Julc.Math).',
  signed: 'Nominal ledger hashes and transaction fields: a spend validator checking a signatory.',
  functional: 'A function project: custom types and list traversal, run with elci eval.',
  'core-completion': 'A function project: nested patterns, generic aliases, destructuring and structural equality.',
  'custom-library': 'Using a custom on-chain Java library packaged as a source JAR (library-jars), including a Java record type.',
  'elci-library': 'A library written in Elci in its own source directory, imported by the application like any module.',
};

const sha256 = (text) => createHash('sha256').update(text).digest('hex');
const json = (value) => JSON.stringify(value, null, 2) + '\n';

async function walk(dir) {
  const out = [];
  for (const entry of (await readdir(dir, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
    const path = resolve(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await walk(path)));
    else out.push(path);
  }
  return out;
}

function frontmatter(raw, file) {
  const match = raw.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!match) throw new Error(`Missing frontmatter: ${file}`);
  const field = (name) => {
    const m = match[1].match(new RegExp(`^${name}:\\s*(.+)$`, 'm'));
    return m ? m[1].trim().replace(/^['"]|['"]$/g, '') : '';
  };
  const order = match[1].match(/^\s+order:\s*(\d+)/m);
  return { title: field('title'), description: field('description'), order: order ? Number(order[1]) : 99, body: match[2] };
}

/** MDX to plain Markdown: drop imports, unwrap Starlight components, keep their content. */
function markdown(body, link) {
  let text = body
    .replace(/^import\s.+?from\s+['"].+?['"];?\s*$/gm, '')
    .replace(/^export\s+const\s.+$/gm, '')
    .replace(/<LinkCard\s+([^>]*?)\/>/g, (_, attrs) => {
      const get = (n) => (attrs.match(new RegExp(`${n}="([^"]*)"`)) || [])[1] || '';
      return `- [${get('title')}](${get('href')}): ${get('description')}`;
    })
    .replace(/<TabItem\s+label="([^"]*)"[^>]*>/g, '**$1**\n')
    .replace(/<Aside\s+type="(\w+)"[^>]*>/g, (_, type) => `**${type[0].toUpperCase() + type.slice(1)}:** `)
    .replace(/<\/?(Steps|FileTree|CardGrid|Tabs|TabItem|Aside|Card|Badge)\b[^>]*>/g, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  // Absolute site links, so exported pages work outside the site.
  return text.replace(/\]\((\/[^)\s]*)\)/g, (_, path) => `](${link(path)})`);
}

async function pages(link) {
  const result = [];
  for (const file of await walk(docs)) {
    if (!/\.mdx?$/.test(file)) continue;
    const slug = relative(docs, file).replace(/\.mdx?$/, '').replace(/(^|\/)index$/, '');
    const section = slug.split('/')[0];
    const { title, description, order, body } = frontmatter(await readFile(file, 'utf8'), file);
    const content = `# ${title}\n\n> ${description}\n\nSource: ${link(`/${slug}/`)}\n\n${markdown(body, link)}\n`
      .replaceAll('https://__ELCI_DOCS__', link('') || 'https://<your-elci-docs-host>');
    result.push({ slug, section, title, description, order, content });
  }
  return result.sort(
    (a, b) => SECTIONS.indexOf(a.section) - SECTIONS.indexOf(b.section) || a.order - b.order || a.slug.localeCompare(b.slug),
  );
}

/** Top-level signatures of a bundled Elci module's exposed functions. */
async function elciModule(file) {
  const source = await readFile(file, 'utf8');
  // The exposing list may span lines and contain `Type(..)`, so balance the parentheses.
  const name = source.match(/^module\s+(\S+)\s+exposing/m)[1];
  let at = source.indexOf('(', source.indexOf('exposing')), depth = 0, list = '';
  for (; at < source.length; at++) {
    const c = source[at];
    if (c === '(') depth++;
    if (c === ')') depth--;
    list += c;
    if (depth === 0) break;
  }
  const exposed = new Set(list.slice(1, -1).split(',').map((n) => n.trim().replace(/\(\.\.\)$/, '')));
  const functions = [];
  const lines = source.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^([a-z]\w*)\s+:\s*(.*)$/);
    if (!m || !exposed.has(m[1])) continue;
    let signature = m[2];
    while (i + 1 < lines.length && /^\s+\S/.test(lines[i + 1]) && !lines[i + 1].trim().startsWith(m[1] + ' ')) {
      signature += ' ' + lines[++i].trim();
    }
    // The doc comment directly above the signature, if any.
    let j = i - 1, doc = '';
    while (j >= 0 && lines[j].trim() === '') j--;
    if (j >= 0 && lines[j].trim() === '-}') {
      let k = j;
      while (k >= 0 && !lines[k].trim().startsWith('{-|')) k--;
      doc = lines.slice(k, j + 1).join(' ').replace(/\{-\||-\}/g, '').replace(/\s+/g, ' ').trim();
    }
    functions.push({ name: m[1], signature: signature.replace(/\s+/g, ' ').trim(), ...(doc ? { summary: doc } : {}) });
  }
  const types = [...exposed].filter((n) => /^[A-Z]/.test(n));
  return { module: name, types, functions };
}

/** Elm's default imports (ADR-008): every project and test module has them. */
const DEFAULT_IMPORTS = {
  Basics: '(imported by default: import Basics exposing (..))',
  List: '(imported by default: import List)',
  Maybe: '(imported by default: import Maybe exposing (Maybe(..)))',
  Result: '(imported by default: import Result exposing (Result(..)))',
  String: '(imported by default: import String)',
  Tuple: '(imported by default: import Tuple)',
};

async function catalog() {
  const bundled = [];
  for (const [dir, kind] of [['core', 'core'], ['test', 'test-library']]) {
    const base = resolve(repo, 'src/main/resources/elci', dir);
    for (const file of (await readdir(base)).filter((f) => f.endsWith('.elm')).sort()) {
      const module = await elciModule(resolve(base, file));
      bundled.push({
        ...module,
        kind,
        import: DEFAULT_IMPORTS[module.module] ?? `import ${module.module}`,
        availableIn: kind === 'core' ? 'all modules' : 'test modules only',
      });
    }
  }
  const intrinsicsFile = resolve(www, 'src/data/intrinsics.json');
  const intrinsics = existsSync(intrinsicsFile) ? JSON.parse(await readFile(intrinsicsFile, 'utf8')).modules : [];
  const stdlib = {};
  for (const line of (await readFile(resolve(repo, 'docs/stdlib-compatibility.tsv'), 'utf8')).split('\n')) {
    const [library, method, status, signature] = line.split('\t');
    if (!library || status === 'UNSUPPORTED') continue;
    (stdlib[library] ??= []).push({ name: method, signature, generic: status === 'GENERIC' });
  }
  return {
    note:
      'Signatures of the modules an Elci program can use. Bundled Elci modules are parsed from their sources; ' +
      'Java libraries are the compile-verified exports, imported after mapping them under [java-modules], e.g. ' +
      '"Julc.Math" = "org.julclang.stdlib.lib.MathLib".',
    bundledModules: bundled,
    compilerProvidedModules: intrinsics,
    javaLibraries: Object.entries(stdlib).map(([library, functions]) => ({
      library,
      class: `org.julclang.stdlib.lib.${library}`,
      functions,
    })),
  };
}

async function diagnostics(link) {
  const text = await readFile(resolve(repo, 'docs/DIAGNOSTICS.md'), 'utf8');
  return {
    format: 'path:line:column: CODE: message',
    reference: link('/reference/diagnostics/'),
    codes: [...text.matchAll(/^\|\s*`(ELCI\d{4})`\s*\|\s*(.+?)\s*\|$/gm)].map((m) => ({ code: m[1], meaning: m[2] })),
  };
}

async function examples() {
  const result = [];
  const base = resolve(repo, 'examples');
  for (const name of (await readdir(base)).sort()) {
    const dir = resolve(base, name);
    const files = {};
    let summary = EXAMPLE_SUMMARIES[name] || '';
    for (const file of await walk(dir)) {
      const path = relative(dir, file);
      if (path.startsWith('build/') || !/(\.elm|\.toml|\.md|\.java)$/.test(path)) continue;
      const text = await readFile(file, 'utf8');
      if (path === 'README.md' && !summary)
        summary = text.split('\n\n').find((p) => p.trim() && !p.startsWith('#'))?.replace(/\s+/g, ' ').trim() || '';
      files[path] = text;
    }
    result.push({ name, summary, url: `https://github.com/bloxbean/elci-lang/tree/main/examples/${name}`, files });
  }
  return { note: 'Every example compiles and its tests pass with the matching Elci version.', examples: result };
}

function versions() {
  const properties = existsSync(resolve(repo, 'gradle.properties'))
    ? execFileSync('cat', [resolve(repo, 'gradle.properties')], { encoding: 'utf8' })
    : '';
  const get = (key) => (properties.match(new RegExp(`^${key}=(.+)$`, 'm')) || [])[1];
  let revision = null;
  try {
    revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim();
  } catch {}
  return { elciVersion: get('version'), julcVersion: get('julcVersion'), sourceRevision: revision };
}

/** All artifacts as a map from site path to content. */
export async function generate(site) {
  const origin = (site || '').replace(/\/$/, '');
  const link = (path) => `${origin}${path}`;
  const all = await pages(link);
  const files = new Map();
  for (const page of all) files.set(`ai/pages/${page.slug}.md`, page.content);
  const starter = all.find((p) => p.slug === 'ai/starter-pack');
  const instructions = all.find((p) => p.slug === 'ai/agent-instructions');
  if (starter) files.set('ai/starter-pack.md', starter.content);
  if (instructions) files.set('ai/agent-instructions.md', instructions.content);
  files.set('ai/catalog.json', json(await catalog()));
  files.set('ai/diagnostics.json', json(await diagnostics(link)));
  files.set('ai/examples.json', json(await examples()));

  const { elciVersion, julcVersion, sourceRevision } = versions();
  let full = `# Elci documentation\n\nElci ${elciVersion} (Julc ${julcVersion}). Every page of ${origin || 'the Elci docs'}, in reading order.\n`;
  for (const page of all) full += `\n---\n\n${page.content}`;
  files.set('llms-full.txt', full);

  let index =
    `# Elci\n\n> Elci is an Elm-syntax functional language for Cardano smart contracts. It compiles to Plutus V3 ` +
    `scripts through Julc, with named multi-purpose validators, strict datum/redeemer decoding and built-in unit, ` +
    `scenario and property tests. Development preview.\n\n` +
    `Elci is not browser Elm: no Html, ports, Float, typeclasses or Elm packages. Every top-level declaration needs a ` +
    `type signature. Evaluation is strict. Verify generated code with \`elci check\` and \`elci test\`.\n\n` +
    `## Start here for agents\n\n` +
    `- [AI starter pack](${link('/ai/starter-pack.md')}): everything needed to write correct Elci; read it first\n` +
    `- [Agent instructions](${link('/ai/agent-instructions.md')}): compact rules for AGENTS.md or CLAUDE.md\n` +
    `- [Full documentation](${link('/llms-full.txt')}): every page in one file\n\n` +
    `## Machine-readable\n\n` +
    `- [Catalog](${link('/ai/catalog.json')}): signatures of bundled modules and Java libraries\n` +
    `- [Diagnostics](${link('/ai/diagnostics.json')}): ELCI codes\n` +
    `- [Examples](${link('/ai/examples.json')}): complete example projects\n` +
    `- [Manifest](${link('/ai/manifest.json')}): versions and checksums\n`;
  for (const section of SECTIONS) {
    const inSection = all.filter((p) => p.section === section);
    if (!inSection.length) continue;
    index += `\n## ${SECTION_TITLES[section]}\n\n`;
    index += inSection.map((p) => `- [${p.title}](${link(`/ai/pages/${p.slug}.md`)}): ${p.description}`).join('\n') + '\n';
  }
  files.set('llms.txt', index);

  files.set(
    'ai/manifest.json',
    json({
      schemaVersion: 1,
      site: origin || null,
      elciVersion,
      julcVersion,
      sourceRevision,
      sourceRepository: 'https://github.com/bloxbean/elci-lang',
      note: 'Generated at build time from the documentation and the repository at sourceRevision.',
      pages: all.map((p) => ({
        title: p.title,
        description: p.description,
        url: link(`/${p.slug}/`),
        markdown: link(`/ai/pages/${p.slug}.md`),
        sha256: sha256(p.content),
      })),
      artifacts: [...files.entries()]
        .filter(([path]) => !path.startsWith('ai/pages/'))
        .map(([path, content]) => ({ url: link(`/${path}`), sha256: sha256(content) })),
    }),
  );
  return files;
}

export default function aiArtifacts({ site } = {}) {
  return {
    name: 'elci-ai-artifacts',
    hooks: {
      'astro:build:done': async ({ dir, logger }) => {
        const out = fileURLToPath(dir);
        const files = await generate(site);
        for (const [path, content] of files) {
          const target = resolve(out, path);
          await mkdir(dirname(target), { recursive: true });
          await writeFile(target, content);
        }
        logger.info(`wrote ${files.size} AI artifacts (llms.txt, llms-full.txt, /ai/...)`);
      },
      'astro:server:setup': ({ server }) => {
        server.middlewares.use(async (req, res, next) => {
          const path = decodeURIComponent((req.url || '').split('?')[0]).replace(/^\//, '');
          if (!(path === 'llms.txt' || path === 'llms-full.txt' || /^ai\/.+\.(md|json)$/.test(path))) return next();
          const content = (await generate(site)).get(path);
          if (content === undefined) return next();
          res.setHeader('Content-Type', path.endsWith('.json') ? 'application/json' : 'text/markdown; charset=utf-8');
          res.end(content);
        });
      },
    },
  };
}
