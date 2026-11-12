// The user docs: one source, docs/manual/*.md, turned into a list of plain blocks here. Both readers draw
// those blocks: the website's /docs/ pages (DocBlocks.astro, static HTML for search engines) and the app,
// which fetches them as JSON from /api/docs/v1/docs.json (DocsApi.kt) and draws them natively. So the
// Markdown is the only place to edit, and both show the same thing.
//
// The Markdown is plain GFM with a small, fixed set of pieces (anything else fails the build, so a page
// never shows up differently in one reader):
//   front matter      title, summary, section, order, keywords (search words that aren't in the text)
//   ## / ###          headings (a page's h2s are its search sections and its table of contents)
//   paragraphs, - / 1. lists (nested), | tables |, **bold**, *italic*, `code` (button and setting names)
//   ![alt](shots/x.png "caption")   a screenshot (docs/manual/shots, made by ui-preview's docs render)
//   [text](other-page.md#anchor)    a link to another page, or https://... for outside links
//   > [!TIP] / [!NOTE] / [!WARNING] a callout
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { fromMarkdown } from 'mdast-util-from-markdown';
import { gfmFromMarkdown } from 'mdast-util-gfm';
import { gfm } from 'micromark-extension-gfm';
import GithubSlugger from 'github-slugger';
import YAML from 'yaml';

export const DOCS_API_VERSION = 1;
export const DOCS_API_PATH = `/api/docs/v${DOCS_API_VERSION}/docs.json`;

/** docs/manual, from the website folder (npm scripts and Astro run there) unless DOCS_DIR says otherwise. */
export function docsDir() {
  return resolve(process.env.DOCS_DIR ?? join(process.cwd(), '../docs/manual'));
}

const KINDS = { TIP: 'tip', NOTE: 'note', WARNING: 'warning' };

function fail(file, node, msg) {
  const at = node?.position ? `:${node.position.start.line}` : '';
  throw new Error(`${file}${at}: ${msg}`);
}

/** Width and height of a PNG, from its IHDR, and `v`: a hash of its bytes (readers cache an image by src + v). */
function pngSize(path) {
  const b = readFileSync(path);
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20), v: createHash('sha1').update(b).digest('hex').slice(0, 10) };
}

/** Inline content as spans: { text, b?, i?, code?, page?, anchor?, href? }. */
function inline(nodes, ctx, marks = {}) {
  const out = [];
  for (const n of nodes) {
    switch (n.type) {
      case 'text':
        out.push({ text: n.value.replace(/\s*\n\s*/g, ' '), ...marks });
        break;
      case 'strong':
        out.push(...inline(n.children, ctx, { ...marks, b: true }));
        break;
      case 'emphasis':
        out.push(...inline(n.children, ctx, { ...marks, i: true }));
        break;
      case 'inlineCode':
        out.push({ text: n.value, ...marks, code: true });
        break;
      case 'break':
        out.push({ text: '\n', ...marks });
        break;
      case 'link': {
        const link = linkTarget(n.url, ctx, n);
        out.push(...inline(n.children, ctx, { ...marks, ...link }));
        break;
      }
      default:
        fail(ctx.file, n, `"${n.type}" isn't one of the docs' inline pieces`);
    }
  }
  // Neighbours with the same marks become one span.
  const merged = [];
  for (const s of out) {
    const prev = merged[merged.length - 1];
    const same = prev && ['b', 'i', 'code', 'page', 'anchor', 'href'].every((k) => prev[k] === s[k]);
    if (same) prev.text += s.text;
    else merged.push({ ...s });
  }
  return merged;
}

function linkTarget(url, ctx, node) {
  if (/^https?:\/\//.test(url)) return { href: url };
  const m = /^(?:([a-z0-9-]+)\.md)?(?:#([a-z0-9-]+))?$/.exec(url);
  if (!m || (!m[1] && !m[2])) fail(ctx.file, node, `link "${url}": use other-page.md, other-page.md#anchor, #anchor or https://`);
  const link = { page: m[1] ?? ctx.slug };
  if (m[2]) link.anchor = m[2];
  ctx.links.push({ ...link, node });
  return link;
}

const plain = (spans) => spans.map((s) => s.text).join('');

function blocks(nodes, ctx) {
  const out = [];
  for (const n of nodes) {
    switch (n.type) {
      case 'heading': {
        if (n.depth !== 2 && n.depth !== 3) fail(ctx.file, n, 'only ## and ### headings (the title is the front matter\'s)');
        const text = plain(inline(n.children, ctx));
        out.push({ t: `h${n.depth}`, id: ctx.slugger.slug(text), text });
        break;
      }
      case 'paragraph': {
        // A paragraph that is only an image is a screenshot block.
        if (n.children.length === 1 && n.children[0].type === 'image') {
          out.push(image(n.children[0], ctx));
          break;
        }
        if (n.children.some((c) => c.type === 'image')) fail(ctx.file, n, 'put a screenshot on its own line');
        out.push({ t: 'p', c: inline(n.children, ctx) });
        break;
      }
      case 'list':
        out.push({
          t: n.ordered ? 'ol' : 'ul',
          ...(n.ordered && n.start !== 1 ? { start: n.start } : {}),
          items: n.children.map((li) => blocks(li.children, ctx)),
        });
        break;
      case 'table': {
        const [head, ...rows] = n.children.map((row) => row.children.map((cell) => inline(cell.children, ctx)));
        out.push({ t: 'table', head, rows });
        break;
      }
      case 'blockquote': {
        const first = n.children[0];
        const tag = first?.type === 'paragraph' && first.children[0]?.type === 'text' && /^\[!(\w+)\]\s*/.exec(first.children[0].value);
        if (!tag || !KINDS[tag[1]]) fail(ctx.file, n, 'a quote must be a callout: > [!TIP], > [!NOTE] or > [!WARNING]');
        const rest = structuredClone(n.children);
        rest[0].children[0].value = rest[0].children[0].value.slice(tag[0].length);
        if (!rest[0].children[0].value) rest[0].children.shift();
        if (!rest[0].children.length) rest.shift();
        out.push({ t: 'note', kind: KINDS[tag[1]], c: blocks(rest, ctx) });
        break;
      }
      case 'thematicBreak':
        out.push({ t: 'hr' });
        break;
      case 'html':
        if (/^<!--[\s\S]*-->$/.test(n.value.trim())) break; // comments for editors
        fail(ctx.file, n, 'no HTML in the docs (the app can\'t draw it)');
        break;
      default:
        fail(ctx.file, n, `"${n.type}" isn't one of the docs' pieces`);
    }
  }
  return out;
}

function image(n, ctx) {
  if (!/^(shots|art)\/[a-z0-9-/]+\.png$/.test(n.url)) fail(ctx.file, n, `screenshot "${n.url}": use shots/<name>.png`);
  const file = join(ctx.dir, n.url);
  if (!existsSync(file)) fail(ctx.file, n, `screenshot ${n.url} doesn't exist (render it: make docs-shots)`);
  if (!n.alt) fail(ctx.file, n, `screenshot ${n.url} needs alt text`);
  ctx.images.add(n.url);
  return { t: 'img', src: `/docs/${n.url}`, alt: n.alt, ...(n.title ? { caption: n.title } : {}), ...pngSize(file) };
}

function frontMatter(src, file) {
  const m = /^---\n([\s\S]*?)\n---\n/.exec(src);
  if (!m) throw new Error(`${file}: starts without front matter (--- title: ... ---)`);
  const meta = YAML.parse(m[1]);
  for (const k of ['title', 'summary', 'section']) if (!meta[k]) throw new Error(`${file}: front matter needs "${k}"`);
  return { meta, body: src.slice(m[0].length) };
}

/**
 * The whole manual: { version, sections: [{ id, title, pages: [slug] }], pages: [{ slug, title, summary,
 * section, keywords, blocks }] }, pages in reading order. Throws on anything a reader couldn't draw, a link
 * to a page or heading that doesn't exist, or a section that isn't listed.
 */
export function loadDocs(dir = docsDir()) {
  const sections = YAML.parse(readFileSync(join(dir, 'sections.yml'), 'utf8'));
  const known = new Map(sections.map((s) => [s.id, { id: s.id, title: s.title, pages: [] }]));
  const pages = [];
  const allLinks = [];
  const images = new Set();
  for (const f of readdirSync(dir).filter((f) => f.endsWith('.md') && f !== 'README.md').sort()) {
    const file = join(dir, f);
    const { meta, body } = frontMatter(readFileSync(file, 'utf8'), file);
    const slug = f.slice(0, -3);
    if (!/^[a-z0-9-]+$/.test(slug)) throw new Error(`${file}: name pages in lowercase-with-dashes`);
    if (!known.has(meta.section)) throw new Error(`${file}: section "${meta.section}" isn't in sections.yml`);
    const ctx = { file: f, dir, slug, slugger: new GithubSlugger(), links: [], images };
    const tree = fromMarkdown(body, { extensions: [gfm()], mdastExtensions: [gfmFromMarkdown()] });
    const page = {
      slug,
      title: String(meta.title),
      summary: String(meta.summary),
      section: meta.section,
      order: Number(meta.order ?? 100),
      keywords: (meta.keywords ?? []).map(String),
      blocks: blocks(tree.children, ctx),
    };
    pages.push(page);
    allLinks.push(...ctx.links.map((l) => ({ ...l, file: f })));
  }
  const bySlug = new Map(pages.map((p) => [p.slug, p]));
  for (const l of allLinks) {
    const target = bySlug.get(l.page);
    if (!target) fail(l.file, l.node, `link to a page that doesn't exist: ${l.page}.md`);
    if (l.anchor && !target.blocks.some((b) => (b.t === 'h2' || b.t === 'h3') && b.id === l.anchor)) {
      fail(l.file, l.node, `link to #${l.anchor}, which ${l.page}.md has no heading for`);
    }
  }
  pages.sort((a, b) => sections.findIndex((s) => s.id === a.section) - sections.findIndex((s) => s.id === b.section) || a.order - b.order || a.slug.localeCompare(b.slug));
  for (const p of pages) known.get(p.section).pages.push(p.slug);
  const synFile = join(dir, 'synonyms.yml');
  const synonyms = existsSync(synFile) ? YAML.parse(readFileSync(synFile, 'utf8')) ?? {} : {};
  return {
    version: DOCS_API_VERSION,
    synonyms,
    sections: [...known.values()].filter((s) => s.pages.length),
    pages: pages.map(({ order, ...p }) => p),
    images: [...images].sort(),
  };
}

/**
 * The manual's translated pages: docs/manual/translations/<lang>/<slug>.md, each a copy of the English page
 * <slug>.md in one of the site's languages (src/i18n's PREFIXED), in the same format and checked the same way.
 * Only a few pages have them (the 3DS install guide); the docs API and the app's HELP stay English. A link
 * to the page itself (#anchor or <slug>.md#anchor) stays on the translation and is checked against its own
 * headings; a link to any other page goes to the English page. Returns { pages: [{ lang, slug, title,
 * summary, section, blocks }], bySlug: Map(slug -> [lang]) }, or throws like loadDocs.
 */
export function loadTranslations(docs, dir = docsDir(), langs) {
  const root = join(dir, 'translations');
  const pages = [];
  const bySlug = new Map();
  if (!existsSync(root)) return { pages, bySlug };
  const english = new Map(docs.pages.map((p) => [p.slug, p]));
  const images = new Set();
  for (const lang of readdirSync(root).sort()) {
    if (langs && !langs.includes(lang)) throw new Error(`translations/${lang}: not one of the site's languages (${langs.join(', ')})`);
    for (const f of readdirSync(join(root, lang)).filter((f) => f.endsWith('.md')).sort()) {
      const rel = `translations/${lang}/${f}`;
      const file = join(root, lang, f);
      const { meta, body } = frontMatter(readFileSync(file, 'utf8'), file);
      const slug = f.slice(0, -3);
      const en = english.get(slug);
      if (!en) throw new Error(`${rel}: translates ${slug}.md, which the manual doesn't have`);
      if (meta.section !== en.section) throw new Error(`${rel}: section "${meta.section}", but ${slug}.md's is "${en.section}"`);
      const ctx = { file: rel, dir, slug, slugger: new GithubSlugger(), links: [], images };
      const tree = fromMarkdown(body, { extensions: [gfm()], mdastExtensions: [gfmFromMarkdown()] });
      const page = { lang, slug, title: String(meta.title), summary: String(meta.summary), section: meta.section, blocks: blocks(tree.children, ctx) };
      for (const l of ctx.links) {
        const target = l.page === slug ? page : english.get(l.page);
        if (!target) fail(rel, l.node, `link to a page that doesn't exist: ${l.page}.md`);
        if (l.anchor && !target.blocks.some((b) => (b.t === 'h2' || b.t === 'h3') && b.id === l.anchor)) {
          fail(rel, l.node, `link to #${l.anchor}, which ${l.page === slug ? 'this page' : `${l.page}.md`} has no heading for`);
        }
      }
      pages.push(page);
      bySlug.set(slug, [...(bySlug.get(slug) ?? []), lang]);
    }
  }
  return { pages, bySlug };
}

/**
 * What /api/docs/v1/docs.json serves: the manual without build-only fields, where its images live (`site` +
 * an image's src) and `rev`, a hash of the content, so a reader can tell a changed manual from the one it has.
 */
export function docsApi(docs, site) {
  const body = { sections: docs.sections, pages: docs.pages, synonyms: docs.synonyms };
  const rev = createHash('sha1').update(JSON.stringify(body)).digest('hex').slice(0, 12);
  return { version: docs.version, rev, site: String(site).replace(/\/$/, ''), ...body };
}
