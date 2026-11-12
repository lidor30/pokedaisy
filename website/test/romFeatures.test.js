// The games table's details window reads docs/ROM_FEATURES.md (src/lib/romFeatures.js): every column there has a
// label here, every game in the table finds its rows, and every row belongs to a game.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { FEATURE_COLUMNS, UNLISTED, cellState, loadRomFeatures, rowsFor, shortVersion } from '../src/lib/romFeatures.js';

const data = JSON.parse(readFileSync(new URL('../src/data/compat.json', import.meta.url), 'utf8'));
const table = loadRomFeatures();

test('every feature column has its label and words on the site', () => {
  assert.deepEqual(table.columns.filter((c) => !FEATURE_COLUMNS.includes(c)), [], 'add it to FEATURE_GROUPS');
  assert.deepEqual(FEATURE_COLUMNS.filter((c) => !table.columns.includes(c)), [], 'gone from ROM_FEATURES.md');
});

test('every game in the table has its feature rows', () => {
  const games = [...new Set(data.matrix.rows.map((r) => r.game))];
  assert.deepEqual(games.filter((g) => rowsFor(table, g).length === 0), []);
});

test('every feature row belongs to a game in the table', () => {
  const games = [...new Set(data.matrix.rows.map((r) => r.game))];
  const owned = new Set(games.flatMap((g) => rowsFor(table, g)));
  assert.deepEqual(table.rows.filter((r) => !owned.has(r) && !r.blocked && !UNLISTED.includes(r.game)).map((r) => `${r.game} | ${r.version}`), []);
});

test('a blocked game\'s row keeps its name and is marked blocked', () => {
  const row = table.rows.find((r) => r.game === 'Ghost Grey');
  assert.ok(row, 'Ghost Grey\'s row');
  assert.equal(row.blocked, true);
  assert.equal(data.matrix.rows.some((r) => r.game === 'Ghost Grey'), false, 'blocked games stay off the site');
});

test('cells read with their footnotes dropped', () => {
  assert.equal(cellState('✅ ¹²'), 'yes');
  assert.equal(cellState('◐ ⁹'), 'part');
  assert.equal(cellState('— ²⁹'), 'no');
  assert.equal(cellState('n/a'), 'na');
  assert.throws(() => cellState('maybe'));
});

test('versions show languages and regions as two-letter codes', () => {
  assert.equal(shortVersion('France, Germany, Spain, Italy, Korea; Japan rev 1'), 'FR/DE/ES/IT/KR; JP rev 1');
  assert.equal(shortVersion('Spain, Italy, Japan, Korea rev 0; Europe rev 10'), 'ES/IT/JP/KR rev 0; EU rev 10');
  assert.equal(shortVersion('USA/Europe rev 1'), 'US/EU rev 1');
  assert.equal(shortVersion('ES / DE / FR / IT / JP'), 'ES/DE/FR/IT/JP');
  assert.equal(shortVersion('USA rev 0, ES / DE / FR / IT'), 'US rev 0, ES/DE/FR/IT');
  assert.equal(shortVersion('English / Spanish Alpha 9 v0'), 'EN/ES Alpha 9 v0');
  assert.equal(shortVersion('2.1.1.1, 2.1.1.1 French'), '2.1.1.1, 2.1.1.1 FR');
  assert.equal(shortVersion('USA, USA/Europe rev 1, AU'), 'US, US/EU rev 1, AU');
  assert.equal(shortVersion('1.1.4, 1.2 (3 builds)'), '1.1.4, 1.2 (3 builds)');
});
