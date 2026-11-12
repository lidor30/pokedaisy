// The 3DS install guide for GitHub: 3ds/INSTALL.md and 3ds/INSTALL.<lang>.md, made from the manual's page
// (docs/manual/3ds.md) and its translations (docs/manual/translations/<lang>/3ds.md), so the two places never
// drift. Edit the manual, then run this; test/docs.test.js fails while a file here is stale.
//
//   node scripts/install-guides.mjs          write them
//   node scripts/install-guides.mjs --check  exit 1 if one is out of date
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';
import { LANGUAGES } from '../src/i18n/index.js';

const SITE = 'https://pokedaisy.com';
const SLUG = '3ds';
const repo = join(dirname(fileURLToPath(import.meta.url)), '../..');
const manual = join(repo, 'docs/manual');

const fileOf = (code) => (code === 'en' ? 'INSTALL.md' : `INSTALL.${code}.md`);
const siteOf = (code) => (code === 'en' ? `${SITE}/docs/${SLUG}/` : `${SITE}/${code}/docs/${SLUG}/`);
// What the line under the title says, in each language: the same guide on the website.
const ON_SITE = {
  en: 'Also on the website',
  ja: 'ウェブサイトでも読めます',
  ko: '웹사이트에서도 볼 수 있어요',
  fr: 'Aussi sur le site',
  de: 'Auch auf der Website',
  it: 'Anche sul sito',
  es: 'También en la web',
  'pt-br': 'Também no site',
  pt: 'Também no site',
};

function source(code) {
  return code === 'en' ? join(manual, `${SLUG}.md`) : join(manual, 'translations', code, `${SLUG}.md`);
}

/** One language's page as GitHub Markdown: a title, the language links, links and pictures pointed at their homes. */
function guide(code, langs) {
  const src = readFileSync(source(code), 'utf8');
  const m = /^---\n([\s\S]*?)\n---\n/.exec(src);
  const meta = YAML.parse(m[1]);
  const body = src
    .slice(m[0].length)
    .trim()
    // Screenshots live with the manual.
    .replace(/\]\(shots\//g, '](../docs/manual/shots/')
    // The page's own anchors stay here; other manual pages are on the website (English).
    .replace(new RegExp(`\\]\\(${SLUG}\\.md(#[a-z0-9-]+)?\\)`, 'g'), (_, a) => `](${a ?? `#`})`)
    .replace(/\]\(([a-z0-9-]+)\.md(#[a-z0-9-]+)?\)/g, (_, page, a) => `](${SITE}/docs/${page}/${a ?? ''})`);
  const links = LANGUAGES.filter((l) => langs.includes(l.code))
    .map((l) => (l.code === code ? `**${l.name}**` : `[${l.name}](${fileOf(l.code)})`))
    .join(' · ');
  return [
    `<!-- Made from docs/manual/${code === 'en' ? '' : `translations/${code}/`}${SLUG}.md by website/scripts/install-guides.mjs: edit that page, not this file. -->`,
    '',
    `# ${meta.title}`,
    '',
    `*${meta.summary}*`,
    '',
    links,
    '',
    `${ON_SITE[code]}: ${siteOf(code)}`,
    '',
    body,
    '',
  ].join('\n');
}

/** { '3ds/INSTALL.md': text, ... } for English and every translation the manual has. */
export function installGuides() {
  const translated = existsSync(join(manual, 'translations'))
    ? readdirSync(join(manual, 'translations')).filter((c) => existsSync(source(c)))
    : [];
  const langs = ['en', ...translated];
  return Object.fromEntries(langs.map((code) => [`3ds/${fileOf(code)}`, guide(code, langs)]));
}

/** The guides whose file on disk isn't what installGuides() makes. */
export function staleInstallGuides() {
  return Object.entries(installGuides())
    .filter(([path, text]) => !existsSync(join(repo, path)) || readFileSync(join(repo, path), 'utf8') !== text)
    .map(([path]) => path);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (process.argv.includes('--check')) {
    const stale = staleInstallGuides();
    if (stale.length) {
      console.error(`out of date (node website/scripts/install-guides.mjs): ${stale.join(', ')}`);
      process.exit(1);
    }
  } else {
    for (const [path, text] of Object.entries(installGuides())) {
      writeFileSync(join(repo, path), text);
      console.log(path);
    }
  }
}
