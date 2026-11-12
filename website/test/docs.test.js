// The manual (docs/manual) stays drawable by both readers, linked up, dash-free and in step with the app:
// every setting the app has is named somewhere in it. See docs/manual/README.md.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { docsApi, docsDir, loadDocs, loadTranslations } from '../src/lib/docs.js';
import { PREFIXED } from '../src/i18n/index.js';
import { staleInstallGuides } from '../scripts/install-guides.mjs';

const dir = docsDir();
const english = readdirSync(dir).filter((f) => f.endsWith('.md'));
// The translated pages (docs/manual/translations/<lang>/<slug>.md) follow the same writing rules.
const tdir = join(dir, 'translations');
const translated = existsSync(tdir)
  ? readdirSync(tdir).flatMap((l) => readdirSync(join(tdir, l)).filter((f) => f.endsWith('.md')).map((f) => `translations/${l}/${f}`))
  : [];
const files = [...english, ...translated];
const text = (f) => readFileSync(join(dir, f), 'utf8');

test('every page parses, links resolve and screenshots exist', () => {
  const docs = loadDocs();
  assert.ok(docs.pages.length > 0);
  const api = docsApi(docs, 'https://pokedaisy.com/');
  assert.equal(api.site, 'https://pokedaisy.com');
  assert.match(api.rev, /^[0-9a-f]{12}$/);
  for (const p of docs.pages) {
    assert.ok(p.summary.length > 20, `${p.slug}: write a real summary`);
    assert.ok(p.blocks.length > 2, `${p.slug}: a page needs more than a line`);
  }
});

test('no em or en dashes, and no spaced hyphen used as one', () => {
  const bad = [];
  for (const f of files) {
    text(f).split('\n').forEach((line, i) => {
      const prose = line.replace(/`[^`]*`/g, '');
      if (/[–—]/.test(prose) || /\S - \S/.test(prose)) bad.push(`${f}:${i + 1}: ${line.trim()}`);
    });
  }
  assert.deepEqual(bad, [], 'rewrite these with a comma, colon, parentheses or a new sentence');
});

test('every setting the app has is mentioned in the manual', () => {
  // Settings rows' English labels, from the search-words map both Settings screens share.
  const kt = readFileSync(join(dir, '../../app/src/main/kotlin/com/pokedaisy/app/companion/ui/SettingRows.kt'), 'utf8');
  const map = kt.slice(kt.indexOf('val SETTING_KEYWORDS'));
  const labels = [...map.slice(0, map.indexOf('\n)')).matchAll(/^\s*"([^"]+)" to "/gm)].map((m) => m[1]);
  assert.ok(labels.length > 20, 'found the settings');
  const manual = english.filter((f) => f !== 'README.md').map(text).join('\n');
  const missing = labels.filter((l) => !manual.includes(`\`${l}\``));
  assert.deepEqual(missing, [], 'document these settings (write each label in backticks, as the app shows it)');
});

test('the docs never name a real Pokémon, move or item', () => {
  // A few the docs would be most tempted to use; the invented creatures stand in for them.
  const banned = /\b(charmander|pikachu|bulbasaur|squirtle|pidgey|onix|geodude|potion|rare candy|poké ?ball|poke ?ball|repel|ember|thunderbolt)\b/i;
  const bad = [];
  for (const f of files) text(f).split('\n').forEach((line, i) => { if (banned.test(line)) bad.push(`${f}:${i + 1}: ${line.trim()}`); });
  assert.deepEqual(bad, []);
});

test('translated pages parse, match an English page and keep its screenshots', () => {
  const docs = loadDocs();
  const { pages, bySlug } = loadTranslations(docs, undefined, PREFIXED);
  for (const p of pages) {
    const en = docs.pages.find((e) => e.slug === p.slug);
    const shots = (b) => b.flatMap((x) => (x.t === 'img' ? [x.src] : x.items ? x.items.flatMap(shots) : x.c && x.t === 'note' ? shots(x.c) : []));
    assert.deepEqual(shots(p.blocks), shots(en.blocks), `${p.lang}/${p.slug}.md: the same screenshots, in the same order, as ${p.slug}.md`);
    assert.equal(p.blocks.filter((b) => b.t === 'h2').length, en.blocks.filter((b) => b.t === 'h2').length, `${p.lang}/${p.slug}.md: the same sections as ${p.slug}.md`);
  }
  // A page translated at all comes in every language the site has.
  for (const [slug, langs] of bySlug) assert.deepEqual([...langs].sort(), [...PREFIXED].sort(), `${slug}.md's translations`);
});

test('the install guides on GitHub (3ds/INSTALL*.md, linux/INSTALL*.md) match the manual', () => {
  assert.deepEqual(staleInstallGuides(), [], 'run node website/scripts/install-guides.mjs');
});
