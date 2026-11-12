// The site's languages (src/i18n): every string the pages translate has its text in every language, keeps its
// {placeholders} and links, reads dash-free like the manual, and Japanese has the pixel font's glyphs.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { collectKeys, keysIn } from '../scripts/i18n-keys.mjs';
import {
  TABLES, PREFIXED, LANGUAGES, detectLanguage, langOfPath, stripLang, localePath, translate, isLocalizedPath,
} from '../src/i18n/index.js';

const keys = collectKeys();
// The manual's translated pages (docs/manual/translations/<lang>/*.md) draw their headings in the pixel fonts too.
const manualIn = (code) => {
  const dir = new URL(`../../docs/manual/translations/${code}/`, import.meta.url);
  return existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith('.md')).map((f) => readFileSync(new URL(f, dir), 'utf8')).join('') : '';
};
const placeholders = (s) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
const tags = (s) => [...s.matchAll(/<\/?([a-z]+)[^>]*>/g)].map((m) => m[0]).sort();

test('finds the strings the pages translate', () => {
  assert.ok(keys.length > 300, `only ${keys.length} keys`);
  assert.deepEqual(keysIn(`tr('A \\'b\\''), msg("C"), tr(\`D {n}\`), tr(\`\${x}\`), other('E')`), ["A 'b'", 'C', 'D {n}']);
  for (const k of ['ROM CHECK', 'Request a game', 'Game and version', 'A single-screen device']) assert.ok(keys.includes(k), k);
});

for (const code of PREFIXED) {
  const table = TABLES[code];
  test(`${code}: every string translated, none left over`, () => {
    // Portugal's falls back to Brazil's string by string, so it may leave one out.
    const missing = keys.filter((k) => !table[k] && !(code === 'pt' && TABLES['pt-br'][k]));
    assert.deepEqual(missing, [], `${code}.json lacks (node scripts/i18n-keys.mjs ${code}):\n${missing.join('\n')}`);
    const stale = Object.keys(table).filter((k) => !keys.includes(k));
    assert.deepEqual(stale, [], `${code}.json keeps strings no page uses any more`);
  });
  test(`${code}: placeholders and links kept`, () => {
    const bad = Object.entries(table).filter(([en, own]) =>
      JSON.stringify(placeholders(en)) !== JSON.stringify(placeholders(own)) || JSON.stringify(tags(en)) !== JSON.stringify(tags(own)));
    assert.deepEqual(bad.map(([en]) => en), []);
  });
  test(`${code}: no em or en dashes, and no spaced hyphen used as one`, () => {
    const bad = Object.values(table).filter((own) => /[–—]/.test(own) || /\S - \S/.test(own));
    assert.deepEqual(bad, []);
  });
}

test('Japanese has the pixel font for every character it uses', () => {
  const font = readFileSync(new URL('../public/fonts/PixelMplusJP-site.ttf', import.meta.url));
  const have = cmap(font);
  const text = Object.values(TABLES.ja).join('') + manualIn('ja') + LANGUAGES.map((l) => l.name).join('');
  // Base.astro's @font-face unicode-range: what the browser draws with it rather than Pixel Operator.
  const jp = (c) => (c >= 0x3000 && c <= 0x30ff) || (c >= 0x3400 && c <= 0x4dbf) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0xff00 && c <= 0xffef);
  const lacking = [...new Set([...text].map((c) => c.codePointAt(0)).filter(jp))].filter((c) => !have.has(c));
  assert.deepEqual(lacking.map((c) => String.fromCodePoint(c)), [], 'run python3 website/scripts/gen_site_jp_font.py');
});

test('Korean has the pixel font for every syllable it uses', () => {
  const font = readFileSync(new URL('../public/fonts/GalmuriKO-site.ttf', import.meta.url));
  const have = cmap(font);
  const text = Object.values(TABLES.ko).join('') + manualIn('ko') + LANGUAGES.map((l) => l.name).join('');
  // Base.astro's @font-face unicode-range.
  const ko = (c) => (c >= 0x3131 && c <= 0x318e) || (c >= 0xac00 && c <= 0xd7a3);
  const lacking = [...new Set([...text].map((c) => c.codePointAt(0)).filter(ko))].filter((c) => !have.has(c));
  assert.deepEqual(lacking.map((c) => String.fromCodePoint(c)), [], 'run python3 website/scripts/gen_site_ko_font.py');
});

test('a browser\'s languages pick the site\'s', () => {
  assert.equal(detectLanguage(['fr-CA', 'en']), 'fr');
  assert.equal(detectLanguage(['de']), 'de');
  assert.equal(detectLanguage(['ja-JP']), 'ja');
  assert.equal(detectLanguage(['pt-BR']), 'pt-br');
  assert.equal(detectLanguage(['pt-PT']), 'pt');
  assert.equal(detectLanguage(['pt']), 'pt');
  assert.equal(detectLanguage(['nl', 'es-MX']), 'es');
  assert.equal(detectLanguage(['en-GB', 'fr']), 'en');
  assert.equal(detectLanguage(['ko-KR']), 'ko');
  assert.equal(detectLanguage(['nl']), null);
});

test('paths in each language', () => {
  assert.equal(langOfPath('/fr/compatibility/'), 'fr');
  assert.equal(langOfPath('/pt-br/'), 'pt-br');
  assert.equal(langOfPath('/compatibility/'), 'en');
  assert.equal(langOfPath('/docs/fast-forward/'), 'en');
  assert.equal(stripLang('/ja/g/unbound/'), '/g/unbound/');
  assert.equal(stripLang('/de/'), '/');
  assert.equal(localePath('it', '/feedback/#bug'), '/it/feedback/#bug');
  assert.equal(localePath('it', '/#games'), '/it/#games');
  assert.equal(localePath('it', '/docs/'), '/docs/', 'the manual is English only');
  assert.equal(localePath('it', 'https://github.com/'), 'https://github.com/');
  assert.equal(localePath('en', '/releases/'), '/releases/');
  assert.ok(isLocalizedPath('/g/emerald/') && !isLocalizedPath('/api/docs/v1/docs.json'));
});

test('a missing string shows the English, placeholders filled', () => {
  assert.equal(translate('fr', 'No such string {n}', { n: 3 }), 'No such string 3');
  assert.equal(translate('en', '{n} GAMES', { n: 44 }), '44 GAMES');
});

/** The code points a TrueType font maps (its cmap's format 4 and 12 subtables). */
function cmap(buf) {
  const u16 = (o) => buf.readUInt16BE(o);
  const u32 = (o) => buf.readUInt32BE(o);
  const tables = u16(4);
  let at = -1;
  for (let i = 0; i < tables; i++) if (buf.toString('latin1', 12 + i * 16, 16 + i * 16) === 'cmap') at = u32(12 + i * 16 + 8);
  const out = new Set();
  for (let i = 0; i < u16(at + 2); i++) {
    const sub = at + u32(at + 4 + i * 8 + 4);
    const format = u16(sub);
    if (format === 4) {
      const n = u16(sub + 6) / 2;
      const ends = sub + 14, starts = ends + n * 2 + 2, deltas = starts + n * 2, ranges = deltas + n * 2;
      for (let s = 0; s < n; s++) {
        const end = u16(ends + s * 2), start = u16(starts + s * 2), delta = u16(deltas + s * 2), range = u16(ranges + s * 2);
        for (let c = start; c <= end && c !== 0xffff; c++) {
          const glyph = range === 0 ? (c + delta) & 0xffff : u16(ranges + s * 2 + range + (c - start) * 2);
          if (glyph) out.add(c);
        }
      }
    } else if (format === 12) {
      for (let g = 0; g < u32(sub + 12); g++) {
        const o = sub + 16 + g * 12;
        for (let c = u32(o); c <= u32(o + 4); c++) out.add(c);
      }
    }
  }
  return out;
}
