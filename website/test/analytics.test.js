import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanEvent, pageLocation, referrerSite, shouldLoad, linkEvent, hitUrl } from '../src/lib/analytics.js';

test('only listed events and parameters go out', () => {
  assert.equal(cleanEvent('rom_check_file', { name: 'x.gba' }), null);
  assert.equal(cleanEvent('toString'), null);
  assert.deepEqual(cleanEvent('rom_check', { verdict: 'unsupported', reason: 'gb', sha1: 'abc', file: 'x.gba' }),
    { verdict: 'unsupported', reason: 'gb' });
  assert.deepEqual(cleanEvent('feedback_sent', { kind: 'bug', ok: 'yes', title: 'my crash' }), { kind: 'bug', ok: 'yes' });
});

test('values off the list are dropped', () => {
  assert.deepEqual(cleanEvent('rom_check', { verdict: 'Pokemon FireRed.gba' }), {});
  assert.deepEqual(cleanEvent('download_apk', { where: 'hero', version: '1.3.2; me@mail.com' }), { where: 'hero' });
  assert.deepEqual(cleanEvent('download_apk', { where: 'hero', version: '1.3.2' }), { where: 'hero', version: '1.3.2' });
  assert.deepEqual(cleanEvent('link_click', { to: 'https://evil.example', where: 3 }), {});
});

test('pages are reported by path, the referrer by site', () => {
  assert.equal(pageLocation('https://pokedaisy.com/feedback/?x=1#game=Hack&sha1=abc'), 'https://pokedaisy.com/feedback/');
  assert.equal(referrerSite('https://www.reddit.com/r/PokeDaisy/comments/123/my_post/?u=me'), 'https://www.reddit.com/');
  assert.equal(referrerSite('https://pokedaisy.com/docs/', 'https://pokedaisy.com'), '');
  assert.equal(referrerSite(''), '');
  assert.equal(referrerSite('not a url'), '');
});

test('loads only on the live site, with an ID, and without an opt-out', () => {
  assert.equal(shouldLoad({ id: 'G-TEST', host: 'pokedaisy.com' }), true);
  assert.equal(shouldLoad({ id: 'G-TEST', host: 'www.pokedaisy.com' }), true);
  assert.equal(shouldLoad({ id: '', host: 'pokedaisy.com' }), false);
  for (const host of ['localhost', 'pokedaisy--pr-12-abc.web.app', 'fork.web.app', 'pokedaisy.com.evil.example']) {
    assert.equal(shouldLoad({ id: 'G-TEST', host }), false, host);
  }
  assert.equal(shouldLoad({ id: 'G-TEST', host: 'pokedaisy.com', gpc: true }), false);
  assert.equal(shouldLoad({ id: 'G-TEST', host: 'pokedaisy.com', dnt: '1' }), false);
  assert.equal(shouldLoad({ id: 'G-TEST', host: 'pokedaisy.com', dnt: '0' }), true);
});

test('link clicks map to their event', () => {
  assert.deepEqual(linkEvent('https://github.com/lidor30/pokedaisy/releases/download/v1.3.2/PokeDaisy-1.3.2.apk', 'hero'),
    { name: 'download_apk', params: { where: 'hero', version: '1.3.2', platform: 'android' } });
  assert.deepEqual(linkEvent('https://github.com/lidor30/pokedaisy/releases/download/v1.6.0/pokedaisy.3dsx', 'downloads'),
    { name: 'download_apk', params: { where: 'downloads', version: '1.6.0', platform: '3ds' } });
  assert.deepEqual(linkEvent('https://github.com/lidor30/pokedaisy/releases/download/v1.6.0/pokedaisy.cia', 'downloads'),
    { name: 'download_apk', params: { where: 'downloads', version: '1.6.0', platform: '3ds' } });
  assert.deepEqual(linkEvent('https://github.com/lidor30/pokedaisy/releases/download/v1.5.0.1/pokedaisy-linux-x86_64.zip', 'downloads'),
    { name: 'download_apk', params: { where: 'downloads', version: '1.5.0.1', platform: 'linux' } });
  assert.deepEqual(linkEvent('https://github.com/lidor30/pokedaisy/releases/latest', 'footer'),
    { name: 'download_apk', params: { where: 'footer' } });
  assert.deepEqual(linkEvent('https://github.com/lidor30/pokedaisy', 'nav'), { name: 'link_click', params: { to: 'github', where: 'nav' } });
  assert.deepEqual(linkEvent('https://www.reddit.com/r/PokeDaisy/', 'nav'), { name: 'link_click', params: { to: 'reddit', where: 'nav' } });
  assert.deepEqual(linkEvent('https://buymeacoffee.com/lidor30g', 'page'), { name: 'link_click', params: { to: 'coffee', where: 'page' } });
  assert.equal(linkEvent('https://pokedaisy.com/docs/', 'nav'), null);
  assert.equal(linkEvent('https://github.com/someone/else', 'page'), null);
});

test('a hit carries the visit and the event, nothing else', () => {
  const visit = { cid: '123.1700000000', sid: '1700000000', page: '42', hits: 1, location: 'https://pokedaisy.com/', referrer: '', title: 'PokeDaisy' };
  const first = new URL(hitUrl('G-TEST', visit, 'page_view', {}, true));
  assert.equal(first.origin + first.pathname, 'https://www.google-analytics.com/g/collect');
  assert.deepEqual(Object.fromEntries(first.searchParams), {
    v: '2', tid: 'G-TEST', cid: '123.1700000000', sid: '1700000000', sct: '1', seg: '0', _p: '42', _s: '1', npa: '1',
    dl: 'https://pokedaisy.com/', dr: '', dt: 'PokeDaisy', en: 'page_view', _ss: '1', _nsi: '1', _fv: '1',
  });
  const later = new URL(hitUrl('G-TEST', { ...visit, hits: 2 }, 'rom_check', { verdict: 'supported' }, false));
  assert.equal(later.searchParams.get('ep.verdict'), 'supported');
  assert.equal(later.searchParams.get('_s'), '2');
  assert.equal(later.searchParams.has('_fv'), false);
});
