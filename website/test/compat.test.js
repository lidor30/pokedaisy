import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deflateRawSync } from 'node:zlib';
import { readFileSync } from 'node:fs';
import { checkFile, readHeader, retailDumpOf, sha1Hex, verdict } from '../src/lib/compat.js';

const data = JSON.parse(readFileSync(new URL('../src/data/compat.json', import.meta.url)));
const MB = 1024 * 1024;

function gba(code, rev = 0) {
  const b = new Uint8Array(0x200);
  b.set([...code].map((c) => c.charCodeAt(0)), 0xac);
  b[0xbc] = rev;
  return b;
}

const v = (code, rev, size, sha1 = '0'.repeat(40)) => verdict(data, { platform: 'gba', code, rev, size, sha1 });

test('header', () => {
  assert.deepEqual(readHeader(gba('BPRE', 1)), { platform: 'gba', code: 'BPRE', rev: 1 });
  const gb = new Uint8Array(0x150);
  gb.set([0xce, 0xed, 0x66, 0x66], 0x104);
  assert.deepEqual(readHeader(gb), { platform: 'gb' });
  assert.deepEqual(readHeader(new Uint8Array(0x200)), { platform: null });
});

test('FireRed / Emerald: known dumps, unknown small ROMs, big hacks', () => {
  assert.deepEqual(v('BPRE', 1, 16 * MB, '41cb23d8dccc8ebd7c649cd8fbb58eeace6e2fdc'),
    { status: 'supported', title: 'Pokémon FireRed' });
  // Retail-sized but not a dump we know: a hack like any other.
  assert.deepEqual(v('BPEE', 0, 16 * MB), { status: 'unsupported', reason: 'unknown-hack', base: 'Pokémon Emerald' });
  assert.deepEqual(v('BPRE', 0, 32 * MB, 'b4776b82a4c7915d0fadeaa27e013523f99dfd94'),
    { status: 'supported', title: 'Pokémon Unbound', version: 'v2.1.1.1', base: 'Pokémon FireRed' });
  assert.equal(v('BPEE', 0, 32 * MB).reason, 'unknown-hack');
  // R.O.W.E. was "in progress" until its companion landed (2026-10-09).
  assert.deepEqual(v('BPEE', 0, 32 * MB, '81bd0f4bfa1c04ab2c6faab1bddd10e8a390ea77'),
    { status: 'supported', title: 'Pokémon R.O.W.E.', version: 'v2.1.9.1', base: 'Pokémon Emerald' });
});

test('a hack under a game code of its own: Ultra Violet (PUVV)', () => {
  assert.deepEqual(v('PUVV', 0, 16 * MB, '3b6c51d8fb6b513bdd01b7bda3ed8965bc985d58'),
    { status: 'supported', title: 'Pokémon Ultra Violet', version: 'v1.22', base: 'Pokémon FireRed' });
  // Another build: the app only knows this one.
  assert.deepEqual(v('PUVV', 0, 16 * MB), { status: 'unsupported', reason: 'unknown-hack', base: 'Pokémon FireRed' });
});

test('other retail games: revisions and size', () => {
  assert.equal(v('AXVE', 2, 16 * MB).status, 'supported');
  assert.equal(v('BPES', 1, 16 * MB).reason, 'revision');
  assert.equal(v('BPGE', 1, 32 * MB).reason, 'retail-hack');
  assert.equal(v('AGBJ', 0, 4 * MB).reason, 'other-game');
});

function nds(code, rev = 0) {
  const b = new Uint8Array(0x200);
  b.set([...code].map((c) => c.charCodeAt(0)), 0x0c);
  b[0x1e] = rev;
  b.set([0x56, 0xcf], 0x15c);
  return b;
}

test('DS: Platinum rev 1, HeartGold / SoulSilver, by the header', () => {
  assert.deepEqual(readHeader(nds('CPUE', 1)), { platform: 'nds', code: 'CPUE', rev: 1 });
  const ds = (code, rev) => verdict(data, { ...readHeader(nds(code, rev)), size: 128 * MB, sha1: '0'.repeat(40) });
  assert.deepEqual(ds('CPUE', 1), { status: 'supported', title: 'Pokémon Platinum', family: 'Nintendo DS' });
  assert.deepEqual(ds('CPUE', 0), { status: 'unsupported', reason: 'revision', base: 'Pokémon Platinum' });
  assert.equal(ds('IPKE', 0).title, 'Pokémon HeartGold');
  assert.equal(ds('IPGE', 0).title, 'Pokémon SoulSilver');
  assert.deepEqual(ds('IPKD', 0), { status: 'supported', title: 'Pokémon Goldene Edition HeartGold', family: 'Nintendo DS' });
  assert.deepEqual(ds('IPGD', 0), { status: 'supported', title: 'Pokémon Silberne Edition SoulSilver', family: 'Nintendo DS' });
  for (const code of ['IPKD', 'IPGD']) {
    for (const rev of [1, 255]) assert.equal(ds(code, rev).reason, 'revision');
  }
  assert.equal(ds('ADAE', 0).reason, 'ds');
});

test('Game Boy: Yellow only', () => {
  assert.equal(verdict(data, { platform: 'gb', sha1: 'cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1' }).title, 'Pokémon Yellow');
  assert.equal(verdict(data, { platform: 'gb', sha1: '0'.repeat(40) }).reason, 'gb');
});

test('Game Boy: Red and Blue', () => {
  assert.equal(verdict(data, { platform: 'gb', sha1: 'ea9bcae617fdf159b045185467ae58b2e4a48b9a' }).title, 'Pokémon Red');
  assert.equal(verdict(data, { platform: 'gb', sha1: 'd7037c83e1ae5b39bde3c30787637ba1d4c48ce2' }).title, 'Pokémon Blue');
});

test('Game Boy Color: Gold and Silver', () => {
  assert.equal(verdict(data, { platform: 'gb', sha1: 'd8b8a3600a465308c9953dfa04f0081c05bdcb94' }).title, 'Pokémon Gold');
  assert.equal(verdict(data, { platform: 'gb', sha1: '49b163f7e57702bc939d642a18f591de55d92dae' }).title, 'Pokémon Silver');
});

test('Game Boy Color: Polished Crystal, every 3.2.x release build', () => {
  // 3.2.3, faithful 3.2.3, 3.2.0's debug build
  for (const sha1 of ['6930b48af5844d373e3c9130f26d6dd1084cf4ed', '3ff091a8e94a0c031372b6f0275e12620fe3d766', '11d461629363402fc15b5005ef51b8f44e726140']) {
    assert.equal(verdict(data, { platform: 'gb', sha1 }).title, 'Pokémon Polished Crystal');
  }
});

test('Game Boy Color: Crystal, all three English releases', () => {
  for (const sha1 of ['f4cd194bdee0d04ca4eac29e09b8e4e9d818c133', 'f2f52230b536214ef7c9924f483392993e226cfb', 'a0fc810f1d4e124434f7be2c989ab5b5892ddf36']) {
    assert.equal(verdict(data, { platform: 'gb', sha1 }).title, 'Pokémon Crystal');
  }
});

test('Game Boy Color: Crystal Clear, both versions in both colour builds', () => {
  for (const sha1 of ['a1c4da6b0057c98fe4ebea45d6812a484fc69abd', '6fc677bf7a9db103cb9ee11ac08d26782e69ae41',
    '84d5c4924c3f1f3fc690669148b7e392948fb23d', '534876e4e4d0d79a82fef3bbe5bea11c40451727']) {
    assert.equal(verdict(data, { platform: 'gb', sha1 }).title, 'Pokémon Crystal Clear');
  }
});

test('Game Boy Color: Prism v0.95 build 254, and not the "LOL PRISM" file', () => {
  assert.equal(verdict(data, { platform: 'gb', sha1: '752076692ae3387cf426ce5f51a98c6b60e8df6a' }).title, 'Pokémon Prism');
  assert.notEqual(verdict(data, { platform: 'gb', sha1: '3569e15d76bcb0c1062f1c242e1d263ca6b6411d' }).title, 'Pokémon Prism');
});

test('a zipped ROM is judged by the ROM inside', async () => {
  const rom = gba('AXPE', 1);
  const name = new TextEncoder().encode('Sapphire.gba');
  const packed = deflateRawSync(rom);
  const local = new Uint8Array(30 + name.length);
  const lv = new DataView(local.buffer);
  lv.setUint32(0, 0x04034b50, true); lv.setUint16(8, 8, true);
  lv.setUint32(18, packed.length, true); lv.setUint32(22, rom.length, true); lv.setUint16(26, name.length, true);
  local.set(name, 30);
  const central = new Uint8Array(46 + name.length);
  const cv = new DataView(central.buffer);
  cv.setUint32(0, 0x02014b50, true); cv.setUint16(10, 8, true);
  cv.setUint32(20, packed.length, true); cv.setUint32(24, rom.length, true); cv.setUint16(28, name.length, true);
  central.set(name, 46);
  const eocd = new Uint8Array(22);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, 1, true); ev.setUint16(10, 1, true);
  ev.setUint32(12, central.length, true); ev.setUint32(16, local.length + packed.length, true);
  const zip = new Uint8Array([...local, ...packed, ...central, ...eocd]);

  const r = await checkFile(data, 'Sapphire.zip', zip);
  assert.equal(r.name, 'Sapphire.gba');
  assert.equal(r.code, 'AXPE');
  assert.equal(r.size, rom.length);
  assert.equal(r.verdict.status, 'supported');
});

test('a retail dump with its end trimmed and its save code patched reads as retail', async () => {
  // A stand-in "retail" ROM and its table entry, built the way gen_retail_dumps.py builds the real ones.
  const rom = gba('BPEE', 0);
  const full = new Uint8Array(0x1000).fill(0xff);
  full.set(rom);
  for (let i = 0x200; i < 0x800; i++) full[i] = i & 0x7f;
  const end = 0x800;
  const ranges = [[0xc4, 0xca], [0x300, 0x340]];
  const zeroed = full.slice(0, end);
  for (const [a, z] of ranges) zeroed.fill(0, a, z);
  const retailSha1 = await sha1Hex(full);
  const fake = { ...data, retailDumps: [{ code: 'BPEE', rev: 0, end, ranges, sha1: await sha1Hex(zeroed), retailSha1 }],
    titles: { ...data.titles, [retailSha1]: 'Pokémon Emerald' } };

  // Trimmed to its data and patched inside a range: still retail Emerald.
  const patched = full.slice(0, end);
  patched[0x310] ^= 0xff;
  assert.equal(await retailDumpOf(fake, readHeader(patched), patched), retailSha1);
  assert.deepEqual((await checkFile(fake, 'emerald.gba', patched)).verdict, { status: 'supported', title: 'Pokémon Emerald' });
  // Changed anywhere else (a mod), or cut into its data: not.
  const modded = patched.slice();
  modded[0x400] ^= 0xff;
  assert.equal(await retailDumpOf(fake, readHeader(modded), modded), null);
  assert.equal((await checkFile(fake, 'mod.gba', modded)).verdict.reason, 'unknown-hack');
  assert.equal(await retailDumpOf(fake, readHeader(patched), patched.slice(0, end - 1)), null);
});
