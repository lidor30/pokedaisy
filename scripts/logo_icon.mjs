// The launcher icon's art, shared by scripts/gen_logo.mjs (the launcher PNGs) and scripts/gen_brand_images.mjs
// (the avatar and GitHub's social preview): the website's mark with the icon's edits, and the lattice behind it.
import { SPRITES } from '../website/src/lib/pixel.js';

/** The side (3 and 9 o'clock) petals' tips rounded: the band's notch at each tip filled in. */
function roundSideTips(rows) {
  const g = rows.map((r) => [...r]);
  const n = g.length;
  for (const col of [0, n - 1]) {
    const inked = g.map((r, y) => (r[col] !== '.' ? y : -1)).filter((y) => y >= 0);
    for (let y = Math.min(...inked); y <= Math.max(...inked); y++) if (g[y][col] === '.') g[y][col] = 'K';
  }
  return g.map((r) => r.join(''));
}

/** Every outline 1 px and the flower mirrored exactly: where a petal met the ball's band the black was 3 px
 * (the band now runs on under the petal to the ring), side petals met the ring with two outlines, and the top
 * and bottom petals were a column off on one row each. [x, y, colour] over the 32x32 mark. */
const ICON_EDITS = [
  [18, 8, 'D'], [19, 8, 'K'],
  [19, 23, 'W'], [18, 23, 'K'], [17, 23, 'S'],
  [11, 11, 'D'], [20, 11, 'D'],
  [8, 12, 'K'], [9, 12, 'K'], [10, 12, 'K'], [8, 13, 'H'], [9, 13, 'H'],
  [21, 12, 'K'], [22, 12, 'K'], [23, 12, 'K'], [22, 13, 'H'], [23, 13, 'H'],
  [9, 18, 'S'], [9, 19, 'K'], [10, 19, 'K'], [11, 20, 'S'],
  [22, 18, 'S'], [23, 18, 'S'], [21, 19, 'K'], [22, 19, 'K'], [23, 19, 'K'], [20, 20, 'W'],
];
function evenOutlines(rows) {
  const g = rows.map((r) => [...r]);
  for (const [x, y, c] of ICON_EDITS) g[y][x] = c;
  return g.map((r) => r.join(''));
}

/** The icon's flower: rows of palette keys ('.' = clear) and the palette. */
export const iconLogo = { pal: SPRITES.logo.pal, rows: roundSideTips(evenOutlines(SPRITES.logo.rows)) };

/** The icon's grid: CELLS cells across, the flower on the middle 32 (cells 20-51), the middle at the corner of
 * cells 35 / 36. */
export const CELLS = 72;

/** The background's cell (x, y) on that grid: diagonal 1-cell lines every LATTICE cells, '/' where x + y and '\'
 * where x - y hit a line; both pass through the middle, so the lattice mirrors exactly both ways round the flower
 * (an even width can't: it would sit half a cell off). The upper half of each diamond is lit, a triangle from its
 * left corner to its right one. */
export const LATTICE = 14;
export const LATTICE_COLOURS = { line: '#226dd5', lit: '#3c94fa', fill: '#2a7fe4' };
export function lattice(x, y) {
  const m = (v) => ((v % LATTICE) + LATTICE) % LATTICE;
  const s = m(x + y - (CELLS - 1)), t = m(x - y);
  if (s === 0 || t === 0) return LATTICE_COLOURS.line;
  return s < t ? LATTICE_COLOURS.lit : LATTICE_COLOURS.fill;
}
