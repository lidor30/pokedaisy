// Every public page, docs included, for search engines (robots.txt points here). A page that comes in every
// language (src/i18n) is listed once per language, each naming the others (hreflang), as the pages' <head> does.
import { loadDocs } from '../lib/docs.js';
import { GAME_PAGES } from '../lib/gamePages.js';
import { LANGUAGES, localePath } from '../i18n/index.js';

export function GET({ site }) {
  const base = String(site ?? 'https://pokedaisy.com').replace(/\/$/, '');
  const localized = ['/', '/compatibility/', '/releases/', '/feedback/', ...GAME_PAGES.map((g) => `/g/${g.slug}/`)];
  const english = ['/docs/', ...loadDocs().pages.map((p) => `/docs/${p.slug}/`)];
  const url = (path, alternates = '') => `  <url><loc>${base}${path}</loc>${alternates}</url>`;
  const entries = [
    ...localized.flatMap((path) => {
      const links = [...LANGUAGES.map((l) => [l.hreflang, localePath(l.code, path)]), ['x-default', path]]
        .map(([hreflang, href]) => `<xhtml:link rel="alternate" hreflang="${hreflang}" href="${base}${href}"/>`)
        .join('');
      return LANGUAGES.map((l) => url(localePath(l.code, path), links));
    }),
    ...english.map((path) => url(path)),
  ];
  const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n${entries.join('\n')}\n</urlset>\n`;
  return new Response(xml, { headers: { 'Content-Type': 'application/xml; charset=utf-8' } });
}
