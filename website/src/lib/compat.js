// The ROM compatibility check, in the browser. The same rules as the app's
// CompanionSupport.isSupported (app/src/main/kotlin/com/pokedaisy/app/CompanionSupport.kt),
// over the tables SiteDataExportTest exports into src/data/compat.json. Change one, change both.
//
// Pure functions over bytes: the page reads the file, nothing is uploaded.

const GB_LOGO_START = [0xce, 0xed, 0x66, 0x66];
export const ROM_EXTENSIONS = ['.gba', '.gb', '.gbc', '.nds'];

/**
 * What the cart header says: platform, game code (0xAC) and revision (0xBC) for a GBA ROM, game code (0x0C) and
 * revision (0x1E) for a DS one (its logo's checksum 0xCF56 at 0x15C: RomIdentity.isNds). Checked in the app's order.
 */
export function readHeader(bytes) {
  if (bytes.length >= 0x108 && GB_LOGO_START.every((b, i) => bytes[0x104 + i] === b)) {
    return { platform: 'gb' };
  }
  if (bytes.length >= 0x160 && bytes[0x15c] === 0x56 && bytes[0x15d] === 0xcf) {
    return { platform: 'nds', code: String.fromCharCode(...bytes.subarray(0x0c, 0x10)), rev: bytes[0x1e] };
  }
  if (bytes.length < 0xc0) return { platform: null };
  const code = String.fromCharCode(...bytes.subarray(0xac, 0xb0));
  if (!/^[A-Z0-9]{4}$/.test(code)) return { platform: null };
  return { platform: 'gba', code, rev: bytes[0xbc] };
}

export async function sha1Hex(bytes) {
  const digest = await crypto.subtle.digest('SHA-1', bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * A retail FireRed / Emerald dump that isn't the cart byte for byte (its end's padding trimmed, its save code
 * patched to save without the flash chip): the retail SHA-1 it's a dump of, or null. The app's RetailDumps.kt:
 * bytes [0, end) with the patchable ranges zeroed hash to the entry's `sha1`.
 */
export async function retailDumpOf(data, { code, rev }, bytes) {
  const dump = (data.retailDumps ?? []).find((d) => d.code === code && d.rev === rev);
  if (!dump || bytes.length < dump.end || bytes.length > data.maxBaseSize) return null;
  const copy = bytes.slice(0, dump.end);
  for (const [a, z] of dump.ranges) copy.fill(0, a, z);
  return (await sha1Hex(copy)) === dump.sha1 ? dump.retailSha1 : null;
}

/** "start:len[:count:stride]" entries (hex, comma-separated) as [start, end) pairs - RandomizedRetail.kt's format. */
export function maskRanges(spec) {
  const out = [];
  for (const e of spec.split(',')) {
    if (!e) continue;
    const [start, len, count = 1, stride = 0] = e.split(':').map((x) => parseInt(x, 16));
    for (let i = 0; i < count; i++) out.push([start + i * stride, start + i * stride + len]);
  }
  return out;
}

/**
 * Retail FireRed / Emerald run through Universal Pokemon Randomizer ZX: the retail SHA-1 it was made from, or null. The
 * app's RandomizedRetail.kt: bytes [0, end) with the species names upper-cased (`fold`), then every site the randomizer
 * may write zeroed (`mask`, which holds the dump ranges too), hash to the entry's `sha1`.
 */
export async function randomizedRetailOf(data, { code, rev }, bytes) {
  const rom = (data.randomizedRetail ?? []).find((d) => d.code === code && d.rev === rev);
  if (!rom || bytes.length < rom.end || bytes.length > data.maxBaseSize) return null;
  const copy = bytes.slice(0, rom.end);
  for (const [a, z] of maskRanges(rom.fold)) {
    for (let i = a; i < z; i++) if (copy[i] >= 0xd5 && copy[i] <= 0xee) copy[i] -= 0x1a;
  }
  for (const [a, z] of maskRanges(rom.mask)) copy.fill(0, a, z);
  return (await sha1Hex(copy)) === rom.sha1 ? rom.retailSha1 : null;
}

/**
 * The verdict for one ROM: `supported` (the companion knows this exact game) or `unsupported`.
 * `size` is the ROM's own size (not an archive's); `retailOf` the retail dump it is ([retailDumpOf]); `randomizedOf` the
 * retail cart it's a randomized copy of ([randomizedRetailOf]).
 */
export function verdict(data, { platform, code, rev, size, sha1, retailOf, randomizedOf }) {
  if (platform === 'gb') {
    const hit = data.gameBoy.find((g) => g.sha1 === sha1);
    return hit
      ? { status: 'supported', title: hit.title, family: 'Game Boy' }
      : { status: 'unsupported', reason: 'gb' };
  }
  if (platform === 'nds') {
    // By the header's code and revision, as the app takes a DS cart (CompanionSupport.isSupported).
    const hit = (data.ds ?? []).find((g) => g.code === code);
    if (!hit) return { status: 'unsupported', reason: 'ds' };
    return hit.rev === rev
      ? { status: 'supported', title: hit.title, family: 'Nintendo DS' }
      : { status: 'unsupported', reason: 'revision', base: hit.title };
  }
  if (platform !== 'gba') return { status: 'unsupported', reason: 'not-a-rom' };

  const retail = data.retail.find((r) => r.code === code);
  if (retail) {
    if (size > data.maxBaseSize) return { status: 'unsupported', reason: 'retail-hack', base: retail.title };
    if (!retail.revs.includes(rev)) return { status: 'unsupported', reason: 'revision', base: retail.title };
    return { status: 'supported', title: data.titles[sha1] ?? retail.title };
  }

  // A hack may carry a game code of its own (Ultra Violet's PUVV): its base game's title. Only
  // the builds the app knows play with the companion (CompanionSupport.isSupported).
  const ownCode = data.hackCodes?.[code];
  const base = data.baseCodes[code] ?? ownCode;
  if (!base) return { status: 'unsupported', reason: 'other-game' };
  const hack = data.hacks.find((h) => h.sha1 === sha1);
  if (hack) return { status: 'supported', title: hack.title, version: hack.version, base };
  // Detected by the app, but its companion isn't ready yet: the game plays, the companion doesn't read it.
  const soon = (data.inProgress ?? []).find((h) => h.sha1 === sha1);
  if (soon) return { status: 'unsupported', reason: 'in-progress', title: soon.title, version: soon.version, base };
  // Retail FireRed / Emerald by their hash (or as a trimmed / save-patched dump of one, or randomized); any other ROM
  // under those codes is a hack the app doesn't know.
  const randomized = randomizedOf && data.titles[randomizedOf] ? `${data.titles[randomizedOf]} (Randomized)` : undefined;
  const title = !ownCode && size <= data.maxBaseSize ? data.titles[sha1] ?? data.titles[retailOf] ?? randomized : undefined;
  return title ? { status: 'supported', title } : { status: 'unsupported', reason: 'unknown-hack', base };
}

/**
 * The ROM inside a .zip (the first .gba / .gb / .gbc / .nds entry), unpacked with the browser's
 * own DecompressionStream. Returns { name, bytes } or null when there's none.
 */
export async function unzipRom(zip) {
  const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  let eocd = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 0xffff); i--) {
    if (view.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip file');
  const count = view.getUint16(eocd + 10, true);
  let p = view.getUint32(eocd + 16, true);
  const decoder = new TextDecoder();
  for (let n = 0; n < count; n++) {
    if (view.getUint32(p, true) !== 0x02014b50) throw new Error('broken zip directory');
    const method = view.getUint16(p + 10, true);
    const compSize = view.getUint32(p + 20, true);
    const nameLen = view.getUint16(p + 28, true);
    const extraLen = view.getUint16(p + 30, true);
    const commentLen = view.getUint16(p + 32, true);
    const local = view.getUint32(p + 42, true);
    const name = decoder.decode(zip.subarray(p + 46, p + 46 + nameLen));
    p += 46 + nameLen + extraLen + commentLen;
    if (!ROM_EXTENSIONS.some((ext) => name.toLowerCase().endsWith(ext))) continue;
    if (compSize === 0xffffffff) throw new Error('zip64 archives aren\'t supported');
    const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
    const packed = zip.subarray(start, start + compSize);
    if (method === 0) return { name, bytes: packed };
    if (method !== 8) throw new Error(`unsupported zip compression (method ${method})`);
    const stream = new Blob([packed]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    return { name, bytes: new Uint8Array(await new Response(stream).arrayBuffer()) };
  }
  return null;
}

/** Everything the page shows for one dropped file: header, hash, size and the verdict. */
export async function checkFile(data, name, bytes) {
  const lower = name.toLowerCase();
  if (lower.endsWith('.7z')) return { name, verdict: { status: 'unsupported', reason: '7z' } };
  if (lower.endsWith('.zip')) {
    const inner = await unzipRom(bytes);
    if (!inner) return { name, verdict: { status: 'unsupported', reason: 'empty-zip' } };
    ({ name, bytes } = inner);
  }
  const header = readHeader(bytes);
  const sha1 = await sha1Hex(bytes);
  const info = { ...header, size: bytes.length, sha1 };
  if (header.platform === 'gba' && !data.titles[sha1]) {
    const retailOf = await retailDumpOf(data, header, bytes);
    if (retailOf) info.retailOf = retailOf;
    else {
      const randomizedOf = await randomizedRetailOf(data, header, bytes);
      if (randomizedOf) info.randomizedOf = randomizedOf;
    }
  }
  return { name, ...info, verdict: verdict(data, info) };
}
