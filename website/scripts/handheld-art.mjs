// The hero handhelds' drawings (HeroDevice.astro: the AYN Thor, HeroSingleDevice.astro: the Retroid Pocket 6),
// rendered to small PNGs that the pages scale up crisp (image-rendering: pixelated): smooth shapes at the real
// devices' proportions, a little pixelated. The screens are HTML over them, placed in the same units.
//
//   node scripts/handheld-art.mjs     writes public/media/handheld-{thor,rp6}.png
//
// Proportions from the makers' specs: the Thor's halves are 150 x 94 mm, its top screen 6" 16:9 (132.8 x 74.7 mm),
// its bottom one 3.92" at 1240 x 1080 (75.1 x 65.4 mm); the RP6 is 205.5 x 80.5 mm with a 5.5" 16:9 screen
// (121.8 x 68.5 mm). Units: the Thor 160 across (0.9375 mm each), the RP6 200 across (1.0275 mm each). The
// components' THOR / RP6 screen boxes must match the numbers here.
import sharp from 'sharp';
import { fileURLToPath } from 'node:url';

const OUT = fileURLToPath(new URL('../public/media/', import.meta.url));
const SCALE = 1.5; // image pixels per unit: the pixel size the page scales up

export const THOR = { w: 160, h: 206, top: [9.15, 10.15, 141.7, 79.7], bottom: [40, 114, 80, 69.8] };
export const RP6 = { w: 200, h: 78.3, top: 3, screen: [40.75, 5.8, 118.5, 66.7] };

const stickG = (id, cap) => `
  <radialGradient id="${id}" cx="0.42" cy="0.36" r="0.72">
    <stop offset="0" stop-color="${cap[0]}"/><stop offset="0.65" stop-color="${cap[1]}"/><stop offset="1" stop-color="${cap[2]}"/>
  </radialGradient>`;

const stick = (cx, cy, well, ring, ringHi, cap) => `
  <circle cx="${cx}" cy="${cy}" r="11.4" fill="${well}"/>
  <circle cx="${cx}" cy="${cy}" r="9.9" fill="none" stroke="${ring}" stroke-width="2.4" opacity="0.55" filter="url(#glow)"/>
  <circle cx="${cx}" cy="${cy}" r="9.9" fill="none" stroke="${ringHi}" stroke-width="1.1"/>
  <circle cx="${cx}" cy="${cy}" r="7.6" fill="url(#${cap})"/>
  <circle cx="${cx}" cy="${cy}" r="5.3" fill="none" stroke="#ffffff" stroke-opacity="0.09" stroke-width="0.7"/>`;

const dpad = (cx, cy, rim, face, arrow, s = 1) => {
  const a = 10.6 * s, b = 3.5 * s, a2 = 10 * s, b2 = 2.95 * s;
  return `
  <rect x="${cx - b}" y="${cy - a}" width="${2 * b}" height="${2 * a}" rx="1.6" fill="${rim}"/>
  <rect x="${cx - a}" y="${cy - b}" width="${2 * a}" height="${2 * b}" rx="1.6" fill="${rim}"/>
  <rect x="${cx - b2}" y="${cy - a2}" width="${2 * b2}" height="${2 * a2}" rx="1.3" fill="${face}"/>
  <rect x="${cx - a2}" y="${cy - b2}" width="${2 * a2}" height="${2 * b2}" rx="1.3" fill="${face}"/>
  <path d="M${cx} ${cy - 8.2 * s}l1.4 2h-2.8z M${cx} ${cy + 8.2 * s}l1.4 -2h-2.8z M${cx - 8.2 * s} ${cy}l2 1.4v-2.8z M${cx + 8.2 * s} ${cy}l-2 1.4v-2.8z" fill="${arrow}"/>`;
};

const buttons = (cx, cy, d, r, list) => list.map(([dx, dy, c, dark]) => `
  <circle cx="${cx + dx * d}" cy="${cy + dy * d}" r="${r + 0.55}" fill="${dark}"/>
  <circle cx="${cx + dx * d}" cy="${cy + dy * d}" r="${r}" fill="${c}"/>
  <circle cx="${cx + dx * d - r * 0.3}" cy="${cy + dy * d - r * 0.35}" r="${r * 0.45}" fill="#ffffff" opacity="0.28"/>`).join('');

// [top]: units of room over the shell (the RP6's shoulder buttons poke out there).
const svg = (w, h, defs, body, top = 0) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h + top}" viewBox="0 ${-top} ${w} ${h + top}" shape-rendering="crispEdges">
<defs>
  <filter id="glow" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="1"/></filter>
  ${defs}
</defs>${body}</svg>`;

// The AYN Thor (black): the lid with the top screen, the hinge, the base with the bottom screen between the left
// stick over the d-pad and ABXY over the right stick, Select / Home / Start under the screen, the speakers.
function thor() {
  const [tx, ty, tw, th] = THOR.top, [bx, by, bw, bh] = THOR.bottom;
  const shell = `<linearGradient id="shell" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#3c3c45"/><stop offset="0.07" stop-color="#2b2b32"/><stop offset="1" stop-color="#17171c"/></linearGradient>
    <linearGradient id="hinge" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#5a5a64"/><stop offset="0.4" stop-color="#2c2c33"/><stop offset="1" stop-color="#0e0e12"/></linearGradient>
    <radialGradient id="btn" cx="0.4" cy="0.35" r="0.75"><stop offset="0" stop-color="#43434c"/><stop offset="1" stop-color="#17171c"/></radialGradient>
    ${stickG('cap', ['#50505a', '#28282f', '#18181d'])}`;
  const ring = '#8a7cff', ringHi = '#a8b8ff';
  const body = `
  <rect x="0" y="0" width="160" height="100" rx="8" fill="url(#shell)"/>
  <rect x="0.5" y="0.5" width="159" height="99" rx="7.5" fill="none" stroke="#ffffff" stroke-opacity="0.1" stroke-width="1"/>
  <rect x="${tx - 1.6}" y="${ty - 1.6}" width="${tw + 3.2}" height="${th + 3.2}" rx="2.5" fill="#050507"/>
  <rect x="6" y="99" width="148" height="8" rx="3" fill="url(#hinge)"/>
  <rect x="6" y="99" width="18" height="8" rx="3" fill="#1d1d23"/><rect x="136" y="99" width="18" height="8" rx="3" fill="#1d1d23"/>
  <rect x="0" y="106" width="160" height="100" rx="8" fill="url(#shell)"/>
  <rect x="0.5" y="106.5" width="159" height="99" rx="7.5" fill="none" stroke="#ffffff" stroke-opacity="0.1" stroke-width="1"/>
  <rect x="${bx - 1.8}" y="${by - 1.8}" width="${bw + 3.6}" height="${bh + 3.6}" rx="2.5" fill="#050507"/>
  ${stick(20, 127, '#0b0b0e', ring, ringHi, 'cap')}
  ${dpad(20.5, 163, '#0c0c10', 'url(#btn)', '#4a4a54')}
  ${buttons(140, 127, 8.4, 4.4, [[0, -1, 'url(#btn)', '#0b0b0e'], [-1, 0, 'url(#btn)', '#0b0b0e'], [1, 0, 'url(#btn)', '#0b0b0e'], [0, 1, 'url(#btn)', '#0b0b0e']])}
  ${stick(140, 163, '#0b0b0e', ring, ringHi, 'cap')}
  <rect x="62" y="193" width="9" height="3.2" rx="1.6" fill="#0f0f13" stroke="#ffffff" stroke-opacity="0.12" stroke-width="0.5"/>
  <circle cx="80" cy="194.6" r="2.7" fill="#0f0f13" stroke="#ffffff" stroke-opacity="0.12" stroke-width="0.5"/>
  <rect x="89" y="193" width="9" height="3.2" rx="1.6" fill="#0f0f13" stroke="#ffffff" stroke-opacity="0.12" stroke-width="0.5"/>
  ${[0, 1, 2].map((r) => [0, 1, 2, 3, 4, 5].map((c) => `<rect x="${10 + c * 3}" y="${190 + r * 3}" width="1.5" height="1.5" fill="#08080a"/><rect x="${134 + c * 3}" y="${190 + r * 3}" width="1.5" height="1.5" fill="#08080a"/>`).join('')).join('')}
  <circle cx="151" cy="112" r="1" fill="#6ef08a"/>`;
  return svg(THOR.w, THOR.h, shell, body);
}

// The Retroid Pocket 6 (white): the shell with rounded grips, the screen's black glass with thin bezels, the d-pad
// over the left stick, ABXY (X blue, Y green, A red, B yellow) over the right stick, both sticks in a blue light
// ring, the small round keys by the screen's top corners, the pills under the sticks, the shoulder buttons.
function rp6() {
  const [sx, sy, sw, sh] = RP6.screen;
  const defs = `<linearGradient id="shell" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#f7f6f3"/><stop offset="0.55" stop-color="#e9e7e2"/><stop offset="1" stop-color="#d4d0c9"/></linearGradient>
    <linearGradient id="shoulder" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#dcd8d1"/><stop offset="1" stop-color="#b9b3aa"/></linearGradient>
    <linearGradient id="dpad" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#eeebe6"/><stop offset="1" stop-color="#cbc6be"/></linearGradient>
    ${stickG('cap', ['#6c6f7a', '#3b3e48', '#26282f'])}`;
  const body = `
  <rect x="18" y="-2.4" width="30" height="8" rx="3" fill="url(#shoulder)"/>
  <rect x="152" y="-2.4" width="30" height="8" rx="3" fill="url(#shoulder)"/>
  <rect x="0" y="0" width="200" height="${RP6.h}" rx="24" fill="url(#shell)"/>
  <rect x="0.5" y="0.5" width="199" height="${RP6.h - 1}" rx="23.5" fill="none" stroke="#ffffff" stroke-opacity="0.8" stroke-width="0.8"/>
  <rect x="0" y="0" width="200" height="${RP6.h}" rx="24" fill="none" stroke="#a49d93" stroke-opacity="0.6" stroke-width="0.6"/>
  <rect x="${sx - 1.6}" y="${sy - 1.6}" width="${sw + 3.2}" height="${sh + 3.2}" rx="3" fill="#0a0a0d"/>
  ${dpad(20, 23, '#aba499', 'url(#dpad)', '#9b948a', 0.92)}
  ${stick(20, 54.5, '#2a2c33', '#4f8dff', '#93c0ff', 'cap')}
  ${buttons(180, 23, 7.6, 3.9, [[0, -1, '#4a7de8', '#2a54b4'], [-1, 0, '#3fb85a', '#22873a'], [1, 0, '#e8453f', '#b02522'], [0, 1, '#f3c838', '#c49612']])}
  ${stick(180, 54.5, '#2a2c33', '#4f8dff', '#93c0ff', 'cap')}
  <circle cx="33.5" cy="7.5" r="2" fill="#dcd7cf" stroke="#a49d93" stroke-width="0.5"/>
  <circle cx="166.5" cy="7.5" r="2" fill="#dcd7cf" stroke="#a49d93" stroke-width="0.5"/>
  <rect x="29" y="69.5" width="8" height="3" rx="1.5" fill="#dcd7cf" stroke="#a49d93" stroke-width="0.5"/>
  <rect x="163" y="69.5" width="8" height="3" rx="1.5" fill="#dcd7cf" stroke="#a49d93" stroke-width="0.5"/>`;
  return svg(RP6.w, RP6.h, defs, body, RP6.top);
}

async function render(name, src, w, h) {
  const out = `${OUT}handheld-${name}.png`;
  // Drawn larger then cut to the unit grid's size, so its edges stay hard pixels.
  await sharp(Buffer.from(src), { density: 72 * SCALE * 4 })
    .resize(Math.round(w * SCALE), Math.round(h * SCALE), { kernel: 'nearest' })
    .png({ palette: true, colours: 96, effort: 10 })
    .toFile(out);
  console.log(out);
}

await render('thor', thor(), THOR.w, THOR.h);
await render('rp6', rp6(), RP6.w, RP6.h + RP6.top);
