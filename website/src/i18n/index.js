// The site's languages: every language the app's own text comes in (companion/i18n/L10n.kt's AppLanguage),
// which also covers every language a supported ROM comes in (English, Japanese, French, German, Italian,
// Spanish, and Korean for the Korean DS carts). English lives at the root (/compatibility/), the others under their prefix (/fr/compatibility/).
//
// Text is looked up by its English, like the app's tr(): `tr("ROM CHECK")` on a page, msg() to mark a
// string kept in a table and translated where it's drawn. src/i18n/<code>.json maps each English string to the
// language's own; a missing one shows the English. test/i18n.test.js finds every tr / msg string in the source
// and fails while a language lacks one (or keeps one nobody uses any more), loses a {placeholder} or a tag, or
// uses a dash. `pt` (Portugal) falls back to `pt-br` string by string, as the app's PT does to BR's game words.
import { fill } from './fill.js';
import ja from './ja.json' with { type: 'json' };
import ko from './ko.json' with { type: 'json' };
import fr from './fr.json' with { type: 'json' };
import de from './de.json' with { type: 'json' };
import it from './it.json' with { type: 'json' };
import es from './es.json' with { type: 'json' };
import ptBr from './pt-br.json' with { type: 'json' };
import pt from './pt.json' with { type: 'json' };

/**
 * [code]: the URL prefix (none for English) and the tables' file name. [hreflang]: the page's `lang` and its
 * alternates. [name]: the language in its own words, for the picker. [locale]: dates and numbers.
 */
export const LANGUAGES = [
  { code: 'en', hreflang: 'en', name: 'English', locale: 'en-US', og: 'en_US' },
  { code: 'ja', hreflang: 'ja', name: '日本語', locale: 'ja-JP', og: 'ja_JP' },
  { code: 'ko', hreflang: 'ko', name: '한국어', locale: 'ko-KR', og: 'ko_KR' },
  { code: 'fr', hreflang: 'fr', name: 'Français', locale: 'fr-FR', og: 'fr_FR' },
  { code: 'de', hreflang: 'de', name: 'Deutsch', locale: 'de-DE', og: 'de_DE' },
  { code: 'it', hreflang: 'it', name: 'Italiano', locale: 'it-IT', og: 'it_IT' },
  { code: 'es', hreflang: 'es', name: 'Español', locale: 'es-ES', og: 'es_ES' },
  { code: 'pt-br', hreflang: 'pt-BR', name: 'Português (Brasil)', locale: 'pt-BR', og: 'pt_BR' },
  { code: 'pt', hreflang: 'pt-PT', name: 'Português (Portugal)', locale: 'pt-PT', og: 'pt_PT' },
];
export const DEFAULT_LANG = 'en';
export const CODES = LANGUAGES.map((l) => l.code);
/** The languages with a URL prefix: every page's getStaticPaths under src/pages/[lang]/. */
export const PREFIXED = CODES.filter((c) => c !== DEFAULT_LANG);
export const language = (code) => LANGUAGES.find((l) => l.code === code) ?? LANGUAGES[0];

export { fill };
export const TABLES = { ja, ko, fr, de, it, es, 'pt-br': ptBr, pt };

/** The page's language, from its path's first segment ('/fr/docs/' -> 'fr'), else English. */
export function langOfPath(pathname) {
  const first = String(pathname).split('/')[1];
  return PREFIXED.includes(first) ? first : DEFAULT_LANG;
}

/** The path without its language prefix: '/fr/compatibility/' -> '/compatibility/'. */
export function stripLang(pathname) {
  const lang = langOfPath(pathname);
  return lang === DEFAULT_LANG ? pathname : pathname.slice(lang.length + 1) || '/';
}

/**
 * The pages that come in every language. The manual (/docs/) is English only for now: it's the one place the
 * manual is written, and the app's HELP reads the same text.
 */
const LOCALIZED = ['/compatibility/', '/feedback/', '/releases/', '/g/'];
export function isLocalizedPath(path) {
  const page = String(path).split(/[?#]/)[0];
  return page === '/' || page === '' || LOCALIZED.some((p) => page.startsWith(p));
}

/**
 * A site path ('/compatibility/', '/#games', '/feedback/#bug') in [lang]. Links off the site, and to pages that
 * aren't translated (the docs), are left alone.
 */
export function localePath(lang, path) {
  if (lang === DEFAULT_LANG || !path.startsWith('/') || path.startsWith('//') || !isLocalizedPath(path)) return path;
  return `/${lang}${path}`;
}

/**
 * The supported language a browser asks for first (`navigator.languages`), or null. Portuguese goes by region
 * like the app's (AppLanguage.ofLocale): Brazil's for pt-BR, Portugal's for any other pt.
 */
export function detectLanguage(wanted) {
  for (const tag of wanted ?? []) {
    const [base, region] = String(tag).toLowerCase().split(/[-_]/);
    if (base === 'pt') return region === 'br' ? 'pt-br' : 'pt';
    if (CODES.includes(base)) return base;
  }
  return null;
}

/** [english] in [lang], with {name} placeholders filled from [vars]. */
export function translate(lang, english, vars) {
  let text = english;
  if (lang !== DEFAULT_LANG) {
    const own = TABLES[lang]?.[english] ?? (lang === 'pt' ? TABLES['pt-br'][english] : undefined);
    if (own) text = own;
  }
  return vars ? fill(text, vars) : text;
}


/** Marks a string for translation where it's kept (a table of features); `tr(x)` translates it where it's drawn. */
export const msg = (english) => english;

/** A page's (or component's) translator: `const tr = trFor(Astro.url)`, then `tr('ROM CHECK')`. */
export function trFor(url) {
  const lang = langOfPath(url.pathname);
  const tr = (english, vars) => translate(lang, english, vars);
  tr.lang = lang;
  tr.path = (path) => localePath(lang, path);
  tr.locale = language(lang).locale;
  return tr;
}
