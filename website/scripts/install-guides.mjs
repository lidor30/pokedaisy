// The install guides for GitHub, made from the manual's pages and their translations
// (docs/manual/translations/<lang>/<page>.md), so the two places never drift: the 3DS one (docs/manual/3ds.md ->
// 3ds/INSTALL.md and 3ds/INSTALL.<lang>.md) and the Linux one (docs/manual/linux-install.md -> linux/INSTALL*.md).
// Edit the manual, then run this; test/docs.test.js fails while a file here is stale.
//
//   node scripts/install-guides.mjs          write them
//   node scripts/install-guides.mjs --check  exit 1 if one is out of date
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';
import { LANGUAGES } from '../src/i18n/index.js';

const SITE = 'https://pokedaisy.com';
// Each guide: its manual page and the repository folder its GitHub copies go in.
const GUIDES = [
  { slug: '3ds', dir: '3ds' },
  { slug: 'linux-install', dir: 'linux' },
];
const repo = join(dirname(fileURLToPath(import.meta.url)), '../..');
const manual = join(repo, 'docs/manual');

const fileOf = (code) => (code === 'en' ? 'INSTALL.md' : `INSTALL.${code}.md`);
const siteOf = (slug, code) => (code === 'en' ? `${SITE}/docs/${slug}/` : `${SITE}/${code}/docs/${slug}/`);
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

function source(slug, code) {
  return code === 'en' ? join(manual, `${slug}.md`) : join(manual, 'translations', code, `${slug}.md`);
}

/** One language's page as GitHub Markdown: a title, the language links, links and pictures pointed at their homes. */
function guide(slug, code, langs) {
  const src = readFileSync(source(slug, code), 'utf8');
  const m = /^---\n([\s\S]*?)\n---\n/.exec(src);
  const meta = YAML.parse(m[1]);
  const body = src
    .slice(m[0].length)
    .trim()
    // Screenshots live with the manual.
    .replace(/\]\(shots\//g, '](../docs/manual/shots/')
    // The page's own anchors stay here; other manual pages are on the website (English).
    .replace(new RegExp(`\\]\\(${slug}\\.md(#[a-z0-9-]+)?\\)`, 'g'), (_, a) => `](${a ?? `#`})`)
    .replace(/\]\(([a-z0-9-]+)\.md(#[a-z0-9-]+)?\)/g, (_, page, a) => `](${SITE}/docs/${page}/${a ?? ''})`);
  const links = LANGUAGES.filter((l) => langs.includes(l.code))
    .map((l) => (l.code === code ? `**${l.name}**` : `[${l.name}](${fileOf(l.code)})`))
    .join(' · ');
  return [
    `<!-- Made from docs/manual/${code === 'en' ? '' : `translations/${code}/`}${slug}.md by website/scripts/install-guides.mjs: edit that page, not this file. -->`,
    '',
    `# ${meta.title}`,
    '',
    `*${meta.summary}*`,
    '',
    links,
    '',
    `${ON_SITE[code]}: ${siteOf(slug, code)}`,
    '',
    body,
    '',
  ].join('\n');
}

/** { '3ds/INSTALL.md': text, 'linux/INSTALL.md': text, ... } for English and every translation the manual has. */
export function installGuides() {
  const codes = existsSync(join(manual, 'translations')) ? readdirSync(join(manual, 'translations')) : [];
  return Object.fromEntries(
    GUIDES.flatMap(({ slug, dir }) => {
      const langs = ['en', ...codes.filter((c) => existsSync(source(slug, c)))];
      return langs.map((code) => [`${dir}/${fileOf(code)}`, guide(slug, code, langs)]);
    }),
  );
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
