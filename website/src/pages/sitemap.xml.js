// Every public page, docs included, for search engines (robots.txt points here).
import { loadDocs } from '../lib/docs.js';
import { GAME_PAGES } from '../lib/gamePages.js';

export function GET({ site }) {
  const base = String(site ?? 'https://pokedaisy.com').replace(/\/$/, '');
  const paths = ['/', '/compatibility/', '/releases/', '/feedback/', '/docs/', ...loadDocs().pages.map((p) => `/docs/${p.slug}/`), ...GAME_PAGES.map((g) => `/g/${g.slug}/`)];
  const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${paths
    .map((p) => `  <url><loc>${base}${p}</loc></url>`)
    .join('\n')}\n</urlset>\n`;
  return new Response(xml, { headers: { 'Content-Type': 'application/xml; charset=utf-8' } });
}
