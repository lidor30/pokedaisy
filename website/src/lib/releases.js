// GitHub releases, read once at build time (the deploy workflow passes GITHUB_TOKEN and also
// rebuilds when a release is published). Offline, the site builds without them.
const REPO = 'lidor30/pokedaisy';
export const REPO_URL = `https://github.com/${REPO}`;

let cached;

export async function getReleases() {
  if (cached) return cached;
  try {
    const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'pokedaisy-website' };
    if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=50`, { headers });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const list = await res.json();
    cached = list
      .filter((r) => !r.draft && !r.prerelease)
      .map((r) => ({
        tag: r.tag_name,
        version: r.tag_name.replace(/^v/, ''),
        name: r.name || r.tag_name,
        date: r.published_at,
        url: r.html_url,
        body: r.body ?? '',
        apk: r.assets.find((a) => a.name.endsWith('.apk'))?.browser_download_url,
      }));
  } catch (e) {
    console.warn(`[releases] couldn't read GitHub releases: ${e.message}`);
    cached = [];
  }
  return cached;
}

/** Every APK download across all releases (pre-releases too), summed. The page refreshes it in the browser. */
export function apkDownloads(list) {
  return list
    .filter((r) => !r.draft)
    .flatMap((r) => r.assets ?? [])
    .filter((a) => a.name.endsWith('.apk'))
    .reduce((n, a) => n + (a.download_count ?? 0), 0);
}

let downloads;

/** [apkDownloads] at build time, every page of releases; 0 offline (the page then shows nothing until it reads them). */
export async function getDownloads() {
  if (downloads !== undefined) return downloads;
  try {
    const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'pokedaisy-website' };
    if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
    let all = [];
    for (let page = 1; page <= 10; page++) {
      const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=100&page=${page}`, { headers });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const list = await res.json();
      all = all.concat(list);
      if (list.length < 100) break;
    }
    downloads = apkDownloads(all);
  } catch (e) {
    console.warn(`[releases] couldn't count downloads: ${e.message}`);
    downloads = 0;
  }
  return downloads;
}

/** A release's date in the page's language ([locale]: src/i18n's; English keeps the site's 7 NOV 2026). */
export function formatDate(iso, locale = 'en-GB') {
  return new Date(iso).toLocaleDateString(locale === 'en-US' ? 'en-GB' : locale, { day: 'numeric', month: 'short', year: 'numeric' }).toUpperCase();
}

// Quotes too: a release body is text from GitHub, and a `"` in a link's URL would otherwise end its href.
const escape = (s) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');

function inline(s) {
  return escape(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\[([^\]]+)\]\((https:\/\/[^)\s]+)\)/g, '<a href="$2" rel="noopener nofollow">$1</a>');
}

/** The release notes' markdown: ## headings, - bullets, **bold**, `code`, links. */
export function markdown(md) {
  let html = '';
  let inList = false;
  for (const raw of md.replace(/\r/g, '').split('\n')) {
    const line = raw.trimEnd();
    const bullet = line.match(/^\s*[-*] (.*)/);
    if (!bullet && inList) { html += '</ul>'; inList = false; }
    if (bullet) {
      if (!inList) { html += '<ul>'; inList = true; }
      html += `<li>${inline(bullet[1])}</li>`;
    } else if (/^#{1,6} /.test(line)) {
      html += `<h4>${inline(line.replace(/^#+ /, ''))}</h4>`;
    } else if (line.trim()) {
      html += `<p>${inline(line)}</p>`;
    }
  }
  if (inList) html += '</ul>';
  return html;
}

/** The first bullets under a release's "New" heading (or its first bullets), for the home page. */
export function highlights(md, n = 4) {
  const lines = md.replace(/\r/g, '').split('\n');
  const from = lines.findIndex((l) => /^#+ New/i.test(l));
  const bullets = lines.slice(from + 1).filter((l) => /^\s*[-*] /.test(l)).map((l) => l.replace(/^\s*[-*] /, ''));
  return bullets.slice(0, n).map(inline);
}
