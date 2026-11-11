// PokeDaisy's banners and profile pictures (docs/brand/) for Reddit, Discord, Ko-fi, Patreon, Buy Me a Coffee and
// GitHub. The banners: the daisy logo and wordmark on the app's blue over a
// pixel meadow (and a Ko-fi page banner, 1200x400, and a Patreon one, 2500x625), built from the website's own sprites (website/src/lib/pixel.js) and its Pixel Operator
// font. No creatures and no game art: the two handhelds are drawn here with plain screens. Writes the
// PNGs with headless Chrome: Reddit's at 2x (it asks for at least 1072x128 desktop / 1080x128 mobile),
// Ko-fi's at exactly 1200x400 (3:1, so nothing is cropped), Patreon's at exactly 2500x625 (4:1). Also a light
// banner on the website cover's page (1280x640 at 2x), and from the launcher icon's art (scripts/logo_icon.mjs):
// the square profile picture (avatar.png, 1024x1024, and a 512 one) and GitHub's social preview (1280x640, uploaded
// by hand in the repo's Settings > General > Social preview).
//
//     node scripts/gen_brand_images.mjs
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { SPRITES, spriteSvg } from '../website/src/lib/pixel.js';
import { CELLS, LATTICE, LATTICE_COLOURS, iconLogo } from './logo_icon.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = join(root, 'docs/brand');
const CHROME = process.env.CHROME ?? [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].find(existsSync);
if (!CHROME) throw new Error('no Chrome found - set CHROME=/path/to/chrome');

// --- Simple pixel handhelds (plain screens, no game art), drawn on a grid and registered as sprites ---------------
function grid(w, h) {
  const g = Array.from({ length: h }, () => Array(w).fill('.'));
  const api = {
    set: (x, y, c) => { if (g[y]?.[x] !== undefined) g[y][x] = c; },
    fill: (x0, y0, x1, y1, c) => { for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) api.set(x, y, c); },
    body: (x0, y0, x1, y1, r = 2) => {
      api.fill(x0, y0, x1, y1, 'K');
      api.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 'D');
      for (let i = 0; i < r; i++) for (let j = 0; j < r - i; j++) {
        for (const [cx, cy] of [[x0 + i, y0 + j], [x1 - i, y0 + j], [x0 + i, y1 - j], [x1 - i, y1 - j]]) api.set(cx, cy, '.');
      }
      api.fill(x0 + r + 1, y0 + 1, x1 - r - 1, y0 + 1, 'L');
    },
    disc: (cx, cy, r, c) => { for (let y = -r; y <= r; y++) for (let x = -r; x <= r; x++) if (x * x + y * y <= r * r + r / 2) api.set(cx + x, cy + y, c); },
    stick: (cx, cy, r = 4) => { api.disc(cx, cy, r, 'C'); api.disc(cx, cy, r - 1, 'K'); api.disc(cx, cy, r - 2, 'M'); api.set(cx - 1, cy - 1, 'H'); },
    dpad: (cx, cy, a = 3) => { api.fill(cx - 1, cy - a, cx + 1, cy + a, 'H'); api.fill(cx - a, cy - 1, cx + a, cy + 1, 'H'); api.set(cx, cy, 'M'); },
    abxy: (cx, cy, d = 3, sz = 2) => { for (const [dx, dy] of [[0, -d], [-d, 0], [d, 0], [0, d]]) api.fill(cx + dx - sz + 1, cy + dy - sz + 1, cx + dx, cy + dy, 'G'); },
    screen: (x0, y0, x1, y1, c = 'S', glint = true) => {
      api.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 'B');
      api.fill(x0, y0, x1, y1, c);
      if (glint) { api.fill(x0 + 1, y0 + 1, x0 + 4, y0 + 1, 'W'); api.fill(x0 + 1, y0 + 2, x0 + 2, y0 + 2, 'W'); }
    },
    rows: () => g.map((r) => r.join('')),
  };
  return api;
}
const DEVICE_PAL = { K: '#14141c', D: '#24242f', L: '#3c3c4c', B: '#0b0b12', S: '#58a0f0', W: '#d8f0ff', C: '#38c8ff', M: '#33333f', H: '#5a5a70', G: '#a8a8b8', T: '#d6ebe7', U: '#c4e0dc' };
{ // dual screen: two bodies and a hinge, smaller buttons, a proper 4:3 lower screen
  const g = grid(44, 52);
  g.body(0, 0, 43, 22, 2);
  g.screen(6, 4, 37, 19);
  g.fill(2, 23, 41, 26, 'K'); g.fill(3, 24, 40, 25, 'L'); g.fill(4, 24, 39, 24, 'H');
  g.body(0, 27, 43, 51, 2);
  g.screen(12, 31, 31, 45, 'T', false);
  for (let y = 32; y <= 45; y += 2) g.fill(12, y, 31, y, 'U');
  g.fill(14, 48, 18, 49, 'L'); g.fill(25, 48, 29, 49, 'L');
  g.stick(6, 34, 3); g.dpad(6, 43, 2); g.abxy(37, 34, 2, 1); g.stick(37, 43, 3);
  SPRITES.bdual = { pal: DEVICE_PAL, rows: g.rows() };
}
{ // single screen: one wide screen between the sticks
  const g = grid(64, 30);
  g.body(0, 0, 63, 29, 3);
  g.screen(18, 5, 45, 24);
  g.stick(8, 9); g.dpad(8, 21); g.abxy(55, 9); g.stick(55, 21);
  SPRITES.bsingle = { pal: DEVICE_PAL, rows: g.rows() };
}

const FONT = pathToFileURL(join(root, 'website/public/fonts/PixelOperator.ttf')).href;

/** A sprite placed at (x, bottom) at a whole scale. */
function sprite(name, x, bottom, scale, flip = false) {
  const { w, h, svg } = spriteSvg(name);
  const shadow = name.startsWith('b') && SPRITES[name].pal === DEVICE_PAL ? 'filter:drop-shadow(3px 3px 0 rgba(5,30,100,.35));' : '';
  return `<span class="sp" style="left:${x}px;bottom:${bottom}px;width:${w * scale}px;height:${h * scale}px;${shadow}${flip ? 'transform:scaleX(-1);' : ''}">${svg}</span>`;
}

// A pixel grass strip, [unit] CSS px per pixel, a fixed pseudo-random tuft pattern.
function grass(width, unit, rows) {
  let seed = 7;
  const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  const cols = Math.ceil(width / unit);
  let r = '';
  for (let x = 0; x < cols; x++) {
    const tuft = rnd() < 0.45 ? 1 : 0;
    if (tuft) r += `<rect x="${x}" y="0" width="1" height="1" fill="#78d068"/>`;
    for (let y = 1; y < rows; y++) {
      const c = y === 1 ? '#78d068' : y < rows - 1 ? (rnd() < 0.12 ? '#78d068' : '#58a838') : '#3c8038';
      r += `<rect x="${x}" y="${y}" width="1" height="1" fill="${c}"/>`;
    }
  }
  return `<svg class="grass" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${cols} ${rows}" width="${cols * unit}" height="${rows * unit}" shape-rendering="crispEdges">${r}</svg>`;
}

function page({ w, h, logoScale, font, scale, flank, kicker, grassRows, flowerX, sky }) {
  const unit = scale;
  const gh = grassRows * unit;
  const wordmark = 'POKEDAISY'.split('').map((c, i) => `<span class="${i < 4 ? 'red' : 'ink'}">${c}</span>`).join('');
  const sprites = flank.map(([n, x, s = scale, f = false, lift = 0]) => sprite(n, x, gh - unit * 2 + lift, s, f)).join('');
  const flowers = flowerX.map((fx, i) => sprite('daisy', fx, unit * (i % 2 ? 1 : 2), unit)).join('');
  const clouds = sky.map(([x, top]) => sprite('cloud', x, h - top - 8 * unit, unit)).join('');
  const { w: lw, h: lh, svg } = spriteSvg('logo');
  return `<!doctype html><meta charset="utf-8"><style>
@font-face { font-family: PX; src: url('${FONT}'); }
* { margin: 0; box-sizing: border-box; }
html, body { width: ${w}px; height: ${h}px; overflow: hidden; }
body { position: relative; font-family: PX; background:
  radial-gradient(circle at 50% 0%, rgba(255,255,255,.20), transparent 55%),
  radial-gradient(circle at 8% 100%, rgba(7,40,120,.25), transparent 45%),
  radial-gradient(circle at 92% 100%, rgba(7,40,120,.25), transparent 45%),
  linear-gradient(180deg, #0E65F1 0%, #2f84fa 100%); }
body::before { content: ""; position: absolute; inset: 0; background-image: radial-gradient(rgba(255,255,255,.16) 1px, transparent 1.4px); background-size: ${unit * 6}px ${unit * 6}px; }
.grass { position: absolute; left: 0; bottom: 0; }
.sp { position: absolute; line-height: 0; }
.sp svg { width: 100%; height: 100%; display: block; }
.center { position: absolute; left: 0; right: 0; top: 0; bottom: ${gh - unit * 2}px; display: flex; align-items: center; justify-content: center; gap: ${unit * 5}px; }
.logo { width: ${lw * logoScale}px; height: ${lh * logoScale}px; line-height: 0; }
.logo svg { width: 100%; height: 100%; display: block; }
.text { display: flex; flex-direction: column; align-items: flex-start; gap: ${unit * 2}px; }
.word { font-size: ${font}px; line-height: 1; letter-spacing: ${font / 16}px; white-space: nowrap;
  text-shadow: ${font / 16}px 0 0 currentColor, ${font / 16}px ${font / 16}px 0 rgba(5,30,100,.35), ${font / 16 * 2}px ${font / 16}px 0 rgba(5,30,100,.35); }
.red { color: #ffffff; } .ink { color: #f8d030; }
.kicker { font-size: ${kicker}px; line-height: 1; letter-spacing: ${kicker / 16}px; color: #ffffff; white-space: nowrap; text-shadow: ${kicker / 16}px ${kicker / 16}px 0 rgba(5,30,100,.45); }
</style><body>
${grass(w, unit, grassRows)}
${clouds}
${flowers}
${sprites}
<div class="center"><span class="logo">${svg}</span><div class="text"><div class="word">${wordmark}</div>${kicker ? '<div class="kicker">FREE &amp; OPEN SOURCE POKÉMON COMPANION</div>' : ''}</div></div>
</body>`;
}

// The launcher icon's art on <canvas>es, whole screen pixels a cell (crisp): `<canvas data-cell data-flower>`,
// the flower and the lattice centred on it (the lattice's lines cross at the flower's middle, as on the icon).
const ICON_SCRIPT = `<script>
const ROWS = ${JSON.stringify(iconLogo.rows)}, PAL = ${JSON.stringify(iconLogo.pal)};
const L = ${LATTICE}, COL = ${JSON.stringify(LATTICE_COLOURS)}, MID = ${CELLS / 2};
const lattice = (x, y) => { const m = (v) => ((v % L) + L) % L; const s = m(x + y - (2 * MID - 1)), t = m(x - y);
  return s === 0 || t === 0 ? COL.line : s < t ? COL.lit : COL.fill; };
for (const cv of document.querySelectorAll('canvas[data-cell]')) {
  const c = +cv.dataset.cell, flower = cv.dataset.flower === '1', ctx = cv.getContext('2d');
  const n = ROWS.length, ox = cv.width / 2 - MID * c, oy = cv.height / 2 - MID * c; // cell (0, 0) of the icon grid
  for (let gy = Math.floor(-oy / c); gy * c + oy < cv.height; gy++) for (let gx = Math.floor(-ox / c); gx * c + ox < cv.width; gx++) {
    const k = flower ? ROWS[gy - (MID - n / 2)]?.[gx - (MID - n / 2)] : undefined;
    ctx.fillStyle = k && k !== '.' ? PAL[k] : lattice(gx, gy);
    ctx.fillRect(gx * c + ox, gy * c + oy, c, c);
  }
}
</script>`;

// The square profile picture: the launcher icon's flower and lattice filling the square, the flower as wide a share
// of it as on the launcher (69%), so the circle Discord / Reddit / Patreon / Ko-fi crop it to keeps it whole.
function avatarPage(size) {
  return `<!doctype html><meta charset="utf-8"><style>
* { margin: 0; } html, body { width: ${size}px; height: ${size}px; overflow: hidden; } canvas { display: block; }
</style><body><canvas width="${size}" height="${size}" data-cell="${(size / 1024) * 22}" data-flower="1"></canvas>${ICON_SCRIPT}</body>`;
}

// GitHub's social preview (1280x640, what a shared repo link shows): the lattice, the icon in a white frame and
// the wordmark with a line about the app.
function socialPage() {
  const icon = 368; // 46 cells of 8 px, the flower 256 px
  return `<!doctype html><meta charset="utf-8"><style>
@font-face { font-family: PX; src: url('${FONT}'); }
* { margin: 0; } html, body { width: 1280px; height: 640px; overflow: hidden; }
body { position: relative; font-family: PX, monospace; }
canvas { display: block; }
.bg { position: absolute; inset: 0; }
.card { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: 64px; }
.icon { border-radius: 80px; box-shadow: 0 0 0 8px #fff, 12px 12px 0 8px #123f8c; }
.copy { color: #fff; }
.name { font-size: 132px; line-height: 1; letter-spacing: 2px; text-shadow: 8px 8px 0 #123f8c; }
.name b { color: #ff5a4a; font-weight: normal; }
.tag { font-size: 44px; margin-top: 28px; line-height: 1.25; text-shadow: 4px 4px 0 #123f8c; }
</style><body>
<canvas class="bg" width="1280" height="640" data-cell="10" data-flower="0"></canvas>
<div class="card"><canvas class="icon" width="${icon}" height="${icon}" data-cell="8" data-flower="1"></canvas>
<div class="copy"><div class="name"><b>POKE</b>DAISY</div><div class="tag">Pokémon, played better.<br>Game Boy to DS, free and open source.</div></div></div>
${ICON_SCRIPT}</body>`;
}

// The website cover's light page (blobs, dot grid, faded logos) with the wordmark and a meadow along the bottom.
SPRITES.cloudLight = { ...SPRITES.cloud, pal: { ...SPRITES.cloud.pal, L: '#cfe0f4' } };
function lightPage({ w, h, unit, logoScale, font, kicker, grassRows, sky, ghosts, ground, flowerX }) {
  const gh = grassRows * unit;
  const place = (name, x, bottom, s, flip = false, cls = '') => {
    const { w: sw, h: sh, svg } = spriteSvg(name);
    return `<span class="sp ${cls}" style="left:${x}px;bottom:${bottom}px;width:${sw * s}px;height:${sh * s}px;${flip ? 'transform:scaleX(-1);' : ''}">${svg}</span>`;
  };
  const clouds = sky.map(([x, top, s = unit]) => place('cloudLight', x, h - top - 8 * s, s, false, 'cloud')).join('');
  const faded = ghosts.map(([x, top, s, rot = 0]) => {
    const { svg } = spriteSvg('logo');
    return `<span class="ghost" style="left:${x}px;top:${top}px;width:${32 * s}px;height:${32 * s}px;transform:rotate(${rot}deg)">${svg}</span>`;
  }).join('');
  const props = ground.map(([n, x, s, f = false]) => place(n, x, gh - unit * 2, s, f, 'prop')).join('');
  const flowers = flowerX.map((fx, i) => place('daisy', fx, unit * (i % 2 ? 1 : 3), unit)).join('');
  const { w: lw, h: lh, svg } = spriteSvg('logo');
  const sh = font / 16;
  return `<!doctype html><meta charset="utf-8"><style>
@font-face { font-family: PX; src: url('${FONT}'); }
* { margin: 0; box-sizing: border-box; }
html, body { width: ${w}px; height: ${h}px; overflow: hidden; }
body { position: relative; font-family: PX; color: #1d1b26; background: #f7f6fb; -webkit-font-smoothing: none; }
.blob { position: absolute; border-radius: 50%; filter: blur(${w * 0.055}px); opacity: .7; }
.b1 { width: ${w * 0.48}px; height: ${w * 0.48}px; left: ${-w * 0.16}px; top: ${-w * 0.2}px; background: #ffd2cc; }
.b2 { width: ${w * 0.41}px; height: ${w * 0.41}px; right: ${-w * 0.12}px; top: ${-w * 0.09}px; background: #fff0b0; }
.b3 { width: ${w * 0.55}px; height: ${w * 0.47}px; left: ${w * 0.28}px; bottom: ${-w * 0.3}px; background: #d6e2ff; }
.dots { position: absolute; inset: 0; background-image: radial-gradient(rgba(29,27,38,.09) ${unit / 2}px, transparent ${unit / 2 + 0.5}px); background-size: ${unit * 6}px ${unit * 6}px; }
.ghost { position: absolute; opacity: .16; line-height: 0; }
.ghost svg, .sp svg { width: 100%; height: 100%; display: block; }
.sp { position: absolute; line-height: 0; }
.cloud { filter: drop-shadow(${unit}px ${unit}px 0 rgba(29,27,38,.07)); }
.prop { filter: drop-shadow(${unit}px ${unit}px 0 rgba(29,27,38,.12)); }
.grass { position: absolute; left: 0; bottom: 0; }
.center { position: absolute; left: 0; right: 0; top: 0; bottom: ${gh}px; display: flex; align-items: center; justify-content: center; gap: ${unit * 6}px; }
.logo { width: ${lw * logoScale}px; height: ${lh * logoScale}px; line-height: 0; filter: drop-shadow(${unit * 2}px ${unit * 2}px 0 rgba(29,27,38,.13)); }
.logo svg { width: 100%; height: 100%; display: block; }
.text { display: flex; flex-direction: column; align-items: flex-start; gap: ${unit * 4}px; }
.word { font-size: ${font}px; line-height: 1; letter-spacing: ${sh}px; white-space: nowrap;
  text-shadow: ${sh}px 0 0 currentColor, ${sh * 1.6}px ${sh * 1.6}px 0 rgba(29,27,38,.13), ${sh * 2.6}px ${sh * 1.6}px 0 rgba(29,27,38,.13); }
.red { color: #e83028; }
.kicker { font-size: ${kicker}px; line-height: 1; letter-spacing: ${kicker / 16}px; color: #404048; white-space: nowrap; text-shadow: ${kicker / 8}px ${kicker / 8}px 0 #d8d8d0; }
</style><body>
<div class="blob b1"></div><div class="blob b2"></div><div class="blob b3"></div><div class="dots"></div>
${faded}
${clouds}
${grass(w, unit, grassRows)}
${flowers}
${props}
<div class="center"><span class="logo">${svg}</span><div class="text"><div class="word"><span class="red">POKE</span>DAISY</div><div class="kicker">FREE &amp; OPEN SOURCE POKÉMON COMPANION</div></div></div>
</body>`;
}

// Desktop: 1072 x 128, scenery flanking the wordmark.
const BANNERS = {
  'banner-desktop': [2, page({
  w: 1072, h: 128, logoScale: 3, font: 64, scale: 3, kicker: 16, grassRows: 7, flowerX: [340, 672], sky: [[205, 16], [756, 10], [20, 44]],
  flank: [
    ['tree', 28], ['bdual', 86, 2], ['fence', 200], ['fence', 224], ['fence', 248], ['tallgrass', 292],
    ['tree', 1072 - 28 - 48, 3, true], ['bsingle', 852, 2], ['fence', 1072 - 250 - 24], ['fence', 1072 - 274 - 24], ['fence', 1072 - 298 - 24], ['tallgrass', 1072 - 340 - 24],
  ],
})],
  // Mobile: 1080 x 128, the wordmark bigger (the app shows it about a third of that width).
  'banner-mobile': [2, page({
  w: 1080, h: 128, logoScale: 3, font: 96, scale: 3, kicker: 0, grassRows: 7, flowerX: [270, 730], sky: [[210, 16]],
  flank: [
    ['tree', 40], ['bdual', 102, 2], ['tallgrass', 224],
    ['tree', 1080 - 40 - 48, 3, true], ['bsingle', 842, 2], ['tallgrass', 770],
  ],
})],
  // Ko-fi: 1200 x 400 at 1x, so the pixel art is drawn at whole 3 / 4 / 5 px steps.
  'kofi-banner': [1, page({
    w: 1200, h: 400, logoScale: 5, font: 112, scale: 4, kicker: 32, grassRows: 9, flowerX: [300, 880, 560, 20],
    sky: [[40, 56], [560, 14], [940, 36], [232, 150]],
    flank: [
      ['bdual', 30, 4], ['coffee', 222, 4], ['coffee', 906, 4, true],
      ['bsingle', 968, 3],
    ],
  })],
  // Patreon: 2500 x 625 at 1x (4:1), drawn at whole 6 / 8 / 10 px steps.
  'patreon-banner': [1, page({
    w: 2500, h: 625, logoScale: 10, font: 176, scale: 8, kicker: 48, grassRows: 9, flowerX: [690, 1120, 1390, 1740],
    sky: [[260, 40], [1180, 20], [1960, 70], [2230, 190]],
    flank: [
      ['tree', 24, 8], ['bdual', 170, 8], ['coffee', 546, 6],
      ['coffee', 1858, 6, true], ['bsingle', 1976, 6], ['tree', 2360, 8, true],
    ],
  })],
  // Buy Me a Coffee: 1600 x 400 at 1x (4:1), whole 3 / 4 / 5 / 6 px steps.
  'bmac-banner': [1, page({
    w: 1600, h: 400, logoScale: 6, font: 128, scale: 4, kicker: 32, grassRows: 9, flowerX: [410, 760, 960, 1170],
    sky: [[200, 56], [640, 14], [980, 30], [1400, 56]],
    flank: [
      ['tree', 16, 4], ['bdual', 96, 4], ['coffee', 296, 5],
      ['coffee', 1224, 5, true], ['bsingle', 1330, 3], ['tree', 1536, 4, true],
    ],
  })],
  // Square profile pictures, 1024 x 1024 and 512 x 512 at 1x.
  'avatar': [1, avatarPage(1024)],
  'avatar-512': [1, avatarPage(512)],
  // GitHub's social preview, 1280 x 640 at 1x.
  'github-social': [1, socialPage()],
  // The light cover, 1280 x 640 (the website's og.png size) drawn at 2x.
  'light-banner': [2, lightPage({
    w: 1280, h: 640, unit: 4, logoScale: 6, font: 120, kicker: 30, grassRows: 12, flowerX: [250, 470, 760, 1010, 1180],
    sky: [[90, 70, 5], [540, 34, 4], [1010, 96, 5], [720, 150, 3], [1120, 250, 3]],
    ghosts: [[1150, -40, 5, 15], [-50, 300, 4, -12], [540, 440, 3, 25]],
    ground: [
      ['tree', 20, 7], ['tree', 112, 5], ['fence', 200, 4], ['fence', 232, 4], ['tallgrass', 300, 5], ['tallgrass', 342, 5],
      ['tallgrass', 900, 5], ['fence', 1016, 4], ['fence', 1048, 4], ['tree', 1092, 5, true], ['tree', 1148, 7, true],
    ],
  })],
};

// Each page is rendered at its own device scale by headless Chrome (a throwaway profile, so an open Chrome doesn't take the job).
const tmp = mkdtempSync(join(tmpdir(), 'pokedaisy-banners-'));
try {
  for (const [name, [dsf, html]] of Object.entries(BANNERS)) {
    const [, w, h] = /html, body \{ width: (\d+)px; height: (\d+)px/.exec(html);
    const file = join(tmp, `${name}.html`);
    const png = join(outDir, `${name}.png`);
    writeFileSync(file, html);
    rmSync(png, { force: true });
    const chrome = spawn(CHROME, [
      '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run', `--user-data-dir=${join(tmp, 'profile')}`,
      `--force-device-scale-factor=${dsf}`, `--window-size=${w},${h}`, `--screenshot=${png}`, pathToFileURL(file).href,
    ], { stdio: 'ignore' });
    const started = Date.now();
    while (!existsSync(png)) {
      if (Date.now() - started > 60000) { chrome.kill(); throw new Error('Chrome timed out'); }
      await new Promise((r) => setTimeout(r, 250));
    }
    await new Promise((r) => setTimeout(r, 500));
    chrome.kill();
    console.log(`wrote ${png}`);
  }
} finally {
  try {
    rmSync(tmp, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  } catch {
    // Chrome may still be closing; the OS clears its temp dir.
  }
}
