// What each supported game can do, for the games table's details window (Matrix.astro): docs/ROM_FEATURES.md's
// first table, read when the site is built, so the doc stays the one place it's written. Its footnotes are
// developers' notes and stay out; a cell becomes yes / part / no / na.
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { msg } from '../i18n/index.js';

export function romFeaturesFile() {
  return resolve(process.env.ROM_FEATURES ?? join(process.cwd(), '../docs/ROM_FEATURES.md'));
}

/**
 * The doc's columns, by its header's words, in the window's groups: [doc column, label, what it does].
 * A new column in the doc fails test/romFeatures.test.js until it's here.
 */
export const FEATURE_GROUPS = [
  [msg('COMPANION'), [
    ['Trainer card', msg('TRAINER CARD'), msg('Your trainer card, drawn like the game\'s, front and back.')],
  ]],
  [msg('ON THE GAME SCREEN'), [
    ['EXPAND', msg('EXPAND'), msg('The map drawn past the game\'s edges to fill a wide screen.')],
    ['Battle buttons', msg('BATTLE BUTTONS'), msg('FIGHT, BAG, POKéMON and RUN from the companion press the game\'s own menus.')],
    ['One-press picks', msg('ONE-PRESS PICKS'), msg('A tapped move is picked at once, without walking the cursor.')],
    ['POKéMON switch', msg('POKéMON SWITCH'), msg('Tap a party member in battle to send it out.')],
  ]],
  [msg('QUALITY OF LIFE'), [
    ['Field items', msg('ITEMS IN ONE PRESS'), msg('USE, GIVE and TAKE from the companion\'s bag.')],
    ['FLY', msg('QUICK FLY'), msg('Fly to a town from the companion\'s map or the quick menu.')],
    ['PC anywhere', msg('PC ANYWHERE'), msg('Open your PC boxes wherever you stand.')],
    ['Wild encounters', msg('WILD ENCOUNTERS OFF'), msg('One switch stops random encounters.')],
    ['Reusable TMs', msg('REUSABLE TMS'), msg('A TM stays in your bag after it teaches a move.')],
    ['Field reads', msg('TEXT SPEED AND AUTO-RUN'), msg('Faster text boxes, running without holding B, and running indoors.')],
    ['Bike / surf', msg('BIKE AND SURF SPEED'), msg('A speed of their own for riding and surfing.')],
    ['Player look', msg('PLAYER LOOK'), msg('Play as another game\'s hero.')],
    ['Shiny odds', msg('SHINY ODDS'), msg('Wild Pokémon turn up shiny at the odds you pick.')],
    ['TMs from the bag', msg('TEACH A TM'), msg('USE on a TM or HM in the companion\'s bag opens the game\'s own screen for teaching it.')],
    ['FLASH', msg('QUICK FLASH'), msg('Light up a dark cave from the quick menu.')],
  ]],
];
export const FEATURE_COLUMNS = FEATURE_GROUPS.flatMap(([, rows]) => rows.map((r) => r[0]));

/** One cell: its mark, footnote numbers dropped. */
export function cellState(text) {
  const t = String(text).replace(/[⁰¹²³⁴⁵⁶⁷⁸⁹]+/g, '').trim();
  if (t.startsWith('✅')) return 'yes';
  if (t.startsWith('◐')) return 'part';
  if (t.toLowerCase().startsWith('n/a')) return 'na';
  if (t.startsWith('—') || t === '-' || t === '') return 'no';
  throw new Error(`ROM_FEATURES.md: a cell reads "${text}"`);
}

const split = (line) => line.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());

/** The first table: { columns: [doc column names], rows: [{ game, version, blocked, cells: { column: state } }] }. */
export function parseRomFeatures(md) {
  const lines = md.split('\n');
  const head = lines.findIndex((l) => /^\|\s*Game\s*\|\s*Version\s*\|/.test(l));
  if (head < 0) throw new Error('ROM_FEATURES.md: no "| Game | Version |" table');
  const columns = split(lines[head]).slice(2).map((c) => c.replace(/[⁰¹²³⁴⁵⁶⁷⁸⁹]+/g, '').trim());
  const rows = [];
  for (let i = head + 2; i < lines.length && lines[i].startsWith('|'); i++) {
    const cells = split(lines[i]);
    // A game blocked at its maker's request reads "Name (blocked ¹¹)": the site leaves it out (BlockedGames.kt).
    const name = cells[0].replace(/[⁰¹²³⁴⁵⁶⁷⁸⁹]+/g, '').trim();
    const blocked = /\(blocked\s*\)$/.test(name);
    const game = name.replace(/\s*\(blocked\s*\)$/, '');
    const version = cells[1].replace(/[⁰¹²³⁴⁵⁶⁷⁸⁹]+/g, '').trim();
    rows.push({ game, version, blocked, cells: Object.fromEntries(columns.map((c, j) => [c, cellState(cells[j + 2] ?? '')])) });
  }
  return { columns, rows };
}

/**
 * The doc's rows for a games-table name (compat.json's matrix): the same name, but where the two split games
 * differently. test/romFeatures.test.js checks every game has rows and every row is someone's.
 */
const ALIASES = {
  Ruby: ['Ruby / Sapphire'],
  Sapphire: ['Ruby / Sapphire'],
  'FireRed / Emerald, randomized': ['FireRed, randomized (UPR ZX 4.6.1)', 'Emerald, randomized (UPR ZX 4.6.1)'],
  Quetzal: ['Quetzal', 'Quetzal, Moemon Quetzal'],
  'Moemon Quetzal': ['Quetzal, Moemon Quetzal'],
};
/**
 * Rows no games-table game owns: hacks the app proves by their code (never listed by name), and Emerald Kaizo,
 * which README's Supported games table (the site's games table) doesn't list yet. Blocked games' rows are left out
 * by their `blocked` flag instead.
 */
export const UNLISTED = ['Unknown FireRed / Emerald hacks edited in place', 'Emerald Kaizo'];

export function rowsFor(table, matrixGame) {
  const names = ALIASES[matrixGame] ?? [matrixGame];
  return table.rows.filter((r) => names.includes(r.game));
}

/**
 * A version label with its languages and regions as two-letter codes, a list of codes closed up:
 * "France, Germany, Spain, Italy, Korea; Japan rev 1" -> "FR/DE/ES/IT/KR; JP rev 1", "USA/Europe rev 1" -> "US/EU rev 1".
 * Japanese is JP and Korean KR, as ROM_FEATURES.md already writes them.
 */
const CODES = [
  [/\bUSA\b/g, 'US'], [/\bEurope\b/g, 'EU'], [/\bAustralia\b/g, 'AU'],
  [/\b(English)\b/g, 'EN'], [/\b(French|France)\b/g, 'FR'], [/\b(German|Germany)\b/g, 'DE'],
  [/\b(Spanish|Spain)\b/g, 'ES'], [/\b(Italian|Italy)\b/g, 'IT'], [/\b(Japanese|Japan)\b/g, 'JP'],
  [/\b(Korean|Korea)\b/g, 'KR'],
];
export function shortVersion(version) {
  let v = String(version);
  for (const [name, code] of CODES) v = v.replace(name, code);
  // Codes in a row, by commas or slashes: one run, slashes only. A code twice is two versions ("US, US/EU rev 1"):
  // then only the slashes close up.
  return v.replace(/\b([A-Z]{2})((?:\s*[,/]\s*[A-Z]{2}\b)+)/g, (m) => {
    const codes = m.split(/\s*[,/]\s*/);
    return new Set(codes).size === codes.length ? codes.join('/') : m.replace(/\s*\/\s*/g, '/');
  });
}

let cached;
export function loadRomFeatures(file = romFeaturesFile()) {
  if (!cached || cached.file !== file) cached = { file, table: parseRomFeatures(readFileSync(file, 'utf8')) };
  return cached.table;
}
