// Anonymous visit counts in Google Analytics 4 (the project's Firebase Analytics), started by Base.astro.
// Nothing about the visitor is kept: no cookies or stored ids (every page load is a new visit), no ads
// features, page addresses cut to their path (the feedback form's #hash carries a ROM's name and SHA-1), the
// referrer cut to its site. Only the events below go out, each parameter checked
// against its allowed values, and only on the live site, never with Global Privacy Control / Do Not Track.

// The GA4 web stream's measurement ID (G-...; public). Empty = analytics off everywhere.
export const MEASUREMENT_ID = 'G-CMT9D1FJFZ';
export const LIVE_HOSTS = ['pokedaisy.com', 'www.pokedaisy.com'];

const ROM_REASONS = ['unknown-hack', 'retail-hack', 'revision', 'other-game', 'gb', 'ds', '7z', 'empty-zip', 'not-a-rom'];

// event -> parameter -> the values it may take (a list, or a pattern the whole value must match).
export const EVENTS = {
  download_apk: { where: ['hero', 'latest', 'releases', 'nav', 'footer', 'page', 'downloads'], version: /^\d{1,3}(\.\d{1,3}){2,3}$/, platform: ['android', 'linux', '3ds'] },
  rom_check: { verdict: ['supported', 'in-progress', 'unsupported', 'error'], reason: ROM_REASONS },
  feedback_sent: { kind: ['game', 'bug', 'feature'], ok: ['yes', 'no'] },
  link_click: { to: ['github', 'reddit', 'discord', 'kofi', 'patreon', 'coffee'], where: ['hero', 'latest', 'releases', 'nav', 'footer', 'page'] },
};

const allowed = (rule, value) =>
  typeof value === 'string' && (Array.isArray(rule) ? rule.includes(value) : rule.test(value));

// The event as it may be sent, or null for one that isn't on the list. Unknown parameters and values that
// aren't allowed are dropped.
export function cleanEvent(name, params = {}) {
  const spec = Object.hasOwn(EVENTS, name) ? EVENTS[name] : null;
  if (!spec) return null;
  const out = {};
  for (const [k, v] of Object.entries(params)) {
    if (Object.hasOwn(spec, k) && allowed(spec[k], v)) out[k] = v;
  }
  return out;
}

// What a page is reported as: its address without query or #hash, the referrer as its site only.
export const pageLocation = (href) => {
  const u = new URL(href);
  return u.origin + u.pathname;
};
export const referrerSite = (ref, self) => {
  try {
    const origin = ref ? new URL(ref).origin : '';
    return origin && origin !== self ? origin + '/' : '';   // a page of this site isn't a referrer
  } catch { return ''; }
};

// Whether to load at all: an ID set, the live site, and no opt-out signal from the browser.
export function shouldLoad({ id = MEASUREMENT_ID, host, gpc, dnt }) {
  if (!id || !LIVE_HOSTS.includes(host)) return false;
  return !(gpc === true || dnt === '1' || dnt === 'yes');
}

// Which event a link click is, from where it points: { name, params } or null.
export function linkEvent(href, where) {
  let u;
  try { u = new URL(href); } catch { return null; }
  const host = u.hostname.replace(/^www\./, '');
  const path = u.pathname;
  if (host === 'github.com' && /^\/lidor30\/pokedaisy\/releases\/(latest|download\/)/i.test(path) || path.endsWith('.apk')) {
    const version = path.match(/\/download\/v([\d.]+)\//)?.[1];
    // Which platform's file (the DOWNLOAD cards): the APK, a Linux package or the 3DS app; a release page link has none.
    const platform = path.endsWith('.apk') ? 'android' : path.endsWith('.3dsx') ? '3ds' : path.endsWith('.zip') ? 'linux' : null;
    return { name: 'download_apk', params: { where, ...(version && { version }), ...(platform && { platform }) } };
  }
  const to = host === 'github.com' && /^\/lidor30\/pokedaisy/i.test(path) ? 'github'
    : host === 'reddit.com' ? 'reddit'
    : host === 'discord.gg' || host === 'discord.com' ? 'discord'
    : host === 'ko-fi.com' ? 'kofi'
    : host === 'patreon.com' ? 'patreon'
    : host === 'buymeacoffee.com' ? 'coffee'
    : null;
  return to ? { name: 'link_click', params: { to, where } } : null;
}

// One hit to GA4's collection endpoint, the request gtag.js itself makes. gtag.js isn't used: it sends nothing
// when it can't keep its _ga cookie (GA4 ignores client_storage 'none'), and its cookieless mode (consent
// denied) keeps those visits out of the reports. `visit` is one page load: a random client id and a session
// that both end with it; its first hit starts the session (_ss / _nsi) and counts a new visitor (_fv).
export function hitUrl(id, visit, name, params, first) {
  const q = new URLSearchParams({
    v: '2', tid: id, cid: visit.cid, sid: visit.sid, sct: '1', seg: '0', _p: visit.page, _s: String(visit.hits),
    npa: '1', dl: visit.location, dr: visit.referrer, dt: visit.title, en: name,
  });
  if (first) { q.set('_ss', '1'); q.set('_nsi', '1'); q.set('_fv', '1'); }
  for (const [k, v] of Object.entries(params)) q.set(`ep.${k}`, v);
  return `https://www.google-analytics.com/g/collect?${q}`;
}

let visit = null;

const send = (name, params) => {
  visit.hits += 1;
  const url = hitUrl(MEASUREMENT_ID, visit, name, params, visit.hits === 1);
  // A beacon still goes out when the click that sent it leaves the page.
  if (!navigator.sendBeacon?.(url)) fetch(url, { method: 'POST', keepalive: true, mode: 'no-cors' }).catch(() => {});
};

// Sends an event if analytics is on (a no-op otherwise, and for anything not on the list).
export function track(name, params) {
  const clean = cleanEvent(name, params);
  if (clean && visit) send(name, clean);
}

// Counts this page and starts listening for link clicks. Called once per page from Base.astro.
export function startAnalytics() {
  const dnt = navigator.doNotTrack ?? window.doNotTrack;
  if (!shouldLoad({ host: location.hostname, gpc: navigator.globalPrivacyControl, dnt })) return;
  const now = Math.floor(Date.now() / 1000);
  visit = {
    cid: `${Math.floor(Math.random() * 2 ** 31)}.${now}`,
    sid: String(now),
    page: String(Math.floor(Math.random() * 2 ** 31)),
    hits: 0,
    location: pageLocation(location.href),
    referrer: referrerSite(document.referrer, location.origin),
    title: document.title,
  };
  send('page_view', {});

  document.addEventListener('click', (e) => {
    const a = e.target instanceof Element ? e.target.closest('a[href]') : null;
    if (!a) return;
    const where = a.closest('[data-track-where]')?.getAttribute('data-track-where')
      ?? (a.closest('header') ? 'nav' : a.closest('footer') ? 'footer' : location.pathname.startsWith('/releases/') ? 'releases' : 'page');
    const ev = linkEvent(a.href, where);
    if (ev) track(ev.name, ev.params);
  }, { capture: true });
}
