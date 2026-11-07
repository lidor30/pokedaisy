// The site's English strings that need a translation (src/i18n): every `tr('...')` / `msg('...')` literal in
// src/, plus the feedback form's words, which live in functions/feedback-form.js (shared with the Cloud
// Function, which can't import the site). test/i18n.test.js checks each language against these.
//
//   node scripts/i18n-keys.mjs           every key, one JSON array
//   node scripts/i18n-keys.mjs fr        the keys fr.json lacks, as a JSON object to fill in ({ english: "" })
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { FORMS, DEVICES } from '../functions/feedback-form.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return /\.(astro|js|ts|mjs)$/.test(name) ? [path] : [];
  });
}

/** The string literal starting at s[i] (a quote), unescaped, and where it ends; null if it isn't a plain one. */
function literal(s, i) {
  const quote = s[i];
  let out = '';
  for (let j = i + 1; j < s.length; j++) {
    const c = s[j];
    if (c === '\\') {
      const n = s[++j];
      out += n === 'n' ? '\n' : n === 't' ? '\t' : n;
    } else if (c === quote) {
      return { text: out, end: j + 1 };
    } else if (quote === '`' && c === '$' && s[j + 1] === '{') {
      return null;
    } else {
      out += c;
    }
  }
  return null;
}

/** Every tr / msg literal in one file's text. */
export function keysIn(text) {
  const keys = [];
  const call = /\b(?:tr|msg)\(\s*/g;
  for (let m; (m = call.exec(text));) {
    const at = m.index + m[0].length;
    if (!`'"\``.includes(text[at])) continue;
    const lit = literal(text, at);
    if (lit) keys.push(lit.text);
  }
  return keys;
}

/** The feedback form's words a visitor reads: titles, labels, hints, the device choices, one placeholder. */
export function formKeys() {
  const keys = [];
  for (const form of Object.values(FORMS)) {
    keys.push(form.title);
    for (const f of form.fields) {
      keys.push(f.label);
      if (f.hint) keys.push(f.hint);
      if (f.id === 'steps') keys.push(f.placeholder);
    }
  }
  keys.push(...DEVICES.filter((d) => d.startsWith('A ')));
  return keys;
}

export function collectKeys() {
  const keys = new Set();
  for (const file of files(join(root, 'src'))) for (const k of keysIn(readFileSync(file, 'utf8'))) keys.add(k);
  for (const k of formKeys()) keys.add(k);
  return [...keys].sort();
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const keys = collectKeys();
  const lang = process.argv[2];
  if (!lang) {
    console.log(JSON.stringify(keys, null, 2));
  } else {
    const table = JSON.parse(readFileSync(join(root, 'src/i18n', `${lang}.json`), 'utf8'));
    console.log(JSON.stringify(Object.fromEntries(keys.filter((k) => !(k in table)).map((k) => [k, ''])), null, 2));
  }
}
