// A page's own look (Base.astro's `theme`, a game page's `theme` in gamePages.js): the site's colour tokens, its
// pixel frames and backdrop redrawn in a game's colours, all scoped under `html[data-theme=<id>]` so nothing
// leaks into other pages. Colours come from the game itself (sampled from its title screen, headless), never
// from its art: the pages still draw only our own pixels.
import { frameSvg } from './pixel.js';

/** Pixel stars on a 120 px tile, for a night sky: [x, y, size, colour]. */
function starTile(stars) {
  const rects = stars.map(([x, y, s, c]) => `<rect x='${x}' y='${y}' width='${s}' height='${s}' fill='${c}'/>`).join('');
  return `url("data:image/svg+xml,${encodeURIComponent(`<svg xmlns='http://www.w3.org/2000/svg' width='120' height='120' shape-rendering='crispEdges'>${rects}</svg>`)}")`;
}
/** A star field in [a] (most stars), [b] and [c]. */
const stars = (a, b, c) => starTile([
  [7, 11, 2, a], [53, 4, 1, b], [96, 19, 2, c], [28, 41, 1, a], [79, 52, 3, a], [112, 63, 1, b],
  [14, 78, 1, c], [46, 89, 2, b], [88, 101, 1, a], [63, 113, 1, c], [104, 92, 1, a], [38, 66, 1, c],
]);

// The matrix's pills on a dark page: their colours kept, dark inside.
const DARK_PILLS = {
  pillGreen: frameSvg([['#4fc46a', 1]], 1, '#10301c'),
  pillYellow: frameSvg([['#e7a54a', 1]], 1, '#3a2410'),
  pillBlue: frameSvg([['#6a8cf0', 1]], 1, '#121f4a'),
  pillRed: frameSvg([['#f06a5a', 1]], 1, '#40140f'),
  pillGrey: frameSvg([['#8a8698', 1]], 1, '#22202c'),
};

/**
 * One theme from a game's colours. [dark]: a night page (light text, dark windows, dark-text partner buttons).
 * [bg] page colour, [sky] a gradient down the page over it; [ink] / [ink2] / [muted] text; [accent] eyebrows,
 * bullets, the title's colour unless [title] says; [card] / [btn] [outline, inner, fill]; [primary] [outline,
 * highlight, fill, hover fill, text, text shadow]; [chip] [outline, fill, on-fill]; [blobs] three [colour, opacity];
 * [starsOf] a star field's colours; [bar] / [foot] the top bar's and footer's [background, rule]; [titleShadow];
 * [glow] the hero's two glow colours.
 */
function make(o) {
  const dark = !!o.dark;
  const shade = o.shade ?? (dark ? '#000000' : o.ink);
  return {
    themeColor: o.themeColor ?? o.bg,
    tokens: {
      ...(dark ? { 'color-scheme': 'dark' } : {}),
      '--bg': o.bg,
      '--ink': o.ink,
      '--ink-2': o.ink2,
      '--muted': o.muted,
      '--red': o.accent,
      '--red-ink': o.accentInk ?? o.accent,
      '--yellow': o.yellow ?? '#f8d030',
      '--hard-shadow': `drop-shadow(calc(var(--px) * 2) calc(var(--px) * 2) 0 ${o.hardShadow ?? (dark ? 'rgba(0, 0, 0, 0.55)' : 'rgba(29, 27, 38, 0.14)')})`,
      '--shade': shade,
    },
    frames: {
      card: frameSvg([[o.card[0], 1], [o.card[1], 1]], 3, o.card[2]),
      btn: frameSvg([[o.btn[0], 1]], 2, o.btn[2]),
      btnHot: frameSvg([[o.btn[1], 1]], 2, o.btnHot ?? o.btn[2]),
      primary: frameSvg([[o.primary[0], 1], [o.primary[1], 1]], 2, o.primary[2]),
      primaryHot: frameSvg([[o.primary[0], 1], ['#ffffff', 1]], 2, o.primary[3]),
      chip: frameSvg([[o.chip[0], 1]], 1, o.chip[1]),
      chipOn: frameSvg([[o.chip[0], 1]], 1, o.chip[2]),
      ...(dark ? DARK_PILLS : {}),
    },
    css: (s) => {
      const [[c1, a1], [c2, a2], [c3, a3]] = o.blobs;
      const titleShadow = o.titleShadow;
      const rules = [
        o.sky && `${s} body{background:${o.sky} no-repeat,var(--bg)}`,
        `${s} .b1{background:${c1};opacity:${a1}}${s} .b2{background:${c2};opacity:${a2}}${s} .b3{background:${c3};opacity:${a3}}`,
        o.starsOf
          ? `${s} .dots{background-image:${stars(...o.starsOf)};background-size:240px 240px;image-rendering:pixelated;mask-image:none;opacity:.8}`
          : `${s} .dots{opacity:.7}`,
        dark && `${s} .petal{opacity:.06}`,
        `${s} .topbar{background:${o.bar[0]};border-bottom-color:${o.bar[1]}}`,
        `${s} .footer{background:${o.foot[0]};border-top-color:${o.foot[1] ?? o.bar[1]}}`,
        // PokeDaisy's own red stays on its name.
        `${s} .brand .red,${s} .foot-name .red{color:#e83028}`,
        `${s} .btn.primary{color:${o.primary[4]};text-shadow:var(--px) var(--px) 0 ${o.primary[5]}}`,
        `${s} .hero-title{color:${o.title ?? o.accent};text-shadow:var(--bold),calc(var(--px)*2) calc(var(--px)*2) 0 ${titleShadow},calc(var(--px)*2 + .0625em) calc(var(--px)*2) 0 ${titleShadow}}`,
        `${s} .glow{background:radial-gradient(closest-side,${o.glow[0]},${o.glow[1]} 60%,transparent)}`,
        ...(dark ? [
          `${s} ::selection{background:var(--yellow);color:var(--shade)}`,
          `${s} .icon-link[aria-label='GitHub'] .pixel{filter:brightness(0) invert(.95)}`,
          `${s} .btn{filter:drop-shadow(0 calc(var(--px)*2) 0 var(--shade))}`,
          `${s} .btn:hover{filter:drop-shadow(0 calc(var(--px)*3) 0 var(--shade))}`,
          `${s} .btn:active{filter:none}`,
          `${s} .btn.f-coffee,${s} .btn.f-discord,${s} .btn.f-reddit,${s} .btn.f-kofi,${s} .btn.f-patreon{color:#1d1b26}`,
          `${s} .heading{text-shadow:var(--bold),var(--px) var(--px) 0 var(--shade),calc(var(--px) + .0625em) var(--px) 0 var(--shade)}`,
        ] : []),
      ];
      return rules.filter(Boolean).join('\n');
    },
  };
}

export const THEMES = {
  // Pokémon Unbound's title screen: a near-black indigo sky full of stars and purple nebulae, the POKéMON
  // wordmark white-lilac outlined in purple, UNBOUND in gold (#fff763 -> #f7d65a -> #e7a54a -> #ce7339, its
  // shadow #a54a29). Dark, the user's pick for it.
  unbound: make({
    dark: true, bg: '#0b0a22', shade: '#000010', ink: '#f7efff', ink2: '#d8cce8', muted: '#a493c4',
    accent: '#f7d65a', accentInk: '#e7a54a', yellow: '#fff763', hardShadow: 'rgba(0, 0, 16, 0.6)',
    card: ['#7b29a5', '#2a1a5c', '#14112f'], btn: ['#b54ab5', '#deadf7', '#1b1640'], btnHot: '#2c1f63',
    primary: ['#000010', '#fff763', '#f7d65a', '#fff763', '#311863', 'rgba(255,255,214,.7)'],
    chip: ['#7b29a5', '#1b1640', '#4a187b'],
    blobs: [['#7b29a5', 0.35], ['#311863', 0.6], ['#b54ab5', 0.25]], starsOf: ['#ffffff', '#deadf7', '#f7c6e7'],
    bar: ['rgba(11,10,34,.82)', '#4a187b'], foot: ['rgba(0,0,16,.5)'],
    titleShadow: '#a54a29', glow: ['rgba(123,41,165,.45)', 'rgba(49,24,99,.3)'],
  }),

  // Pokémon SoulGold's title screen: dawn over the sea - a blue sky (#5284d6 -> #b5c6ef) fading to lavender
  // #d6ceef, pink #f7d6d6 and cream #fff7de at the horizon, the logo's yellow #fff729 outlined in blue #1810e7,
  // the red bird #940800 and the navy one #000852. Light, like the screen.
  soulgold: make({
    bg: '#fff7de', themeColor: '#b5c6ef', ink: '#000852', ink2: '#29316b', muted: '#5a6294',
    accent: '#940800', accentInk: '#6b0600', yellow: '#fff729', hardShadow: 'rgba(0, 8, 82, 0.16)',
    sky: 'linear-gradient(180deg,#94bde7 0,#b5c6ef 30vh,#d6ceef 75vh,#f7d6d6 120vh,#fff7de 170vh)',
    card: ['#29318c', '#d6ceef', '#ffffff'], btn: ['#29318c', '#29318c', '#ffffff'], btnHot: '#fff7de',
    primary: ['#000852', '#ffffc6', '#fff729', '#ffff73', '#000852', '#ffffc6'],
    chip: ['#29318c', '#ffffff', '#29318c'],
    blobs: [['#fff729', 0.45], ['#f7d6d6', 0.7], ['#73a5e7', 0.4]],
    bar: ['rgba(214,206,239,.78)', '#000852'], foot: ['rgba(255,247,222,.7)'],
    title: '#1810e7', titleShadow: '#fff729', glow: ['rgba(255,247,41,.4)', 'rgba(247,214,214,.45)'],
  }),

  // Pokémon FireRed's title screen: teal #42b5a5 between an orange band #ff5a00 and the dark red one (#8c0000 ->
  // #310000), the dragon's flames in amber #ffad00 and cream #ffffbd. Dark, embers on a night of dark red.
  firered: make({
    dark: true, bg: '#1a0705', shade: '#0a0000', ink: '#ffffbd', ink2: '#f2dcc0', muted: '#c79a80',
    accent: '#ffad00', accentInk: '#ff5a00', yellow: '#ffad00',
    sky: 'linear-gradient(180deg,#310000 0,#1a0705 60vh)',
    card: ['#ce4200', '#5a0000', '#2a0b07'], btn: ['#ff5a00', '#ffad00', '#2a0b07'], btnHot: '#420000',
    primary: ['#0a0000', '#ffad00', '#ff5a00', '#ff7a1a', '#ffffbd', '#7b2100'],
    chip: ['#ce4200', '#2a0b07', '#8c0000'],
    blobs: [['#ff5a00', 0.25], ['#42b5a5', 0.18], ['#8c0000', 0.5]], starsOf: ['#ffad00', '#ff5a00', '#ffffbd'],
    bar: ['rgba(26,7,5,.85)', '#8c0000'], foot: ['rgba(10,0,0,.5)'],
    titleShadow: '#8c0000', glow: ['rgba(255,90,0,.35)', 'rgba(66,181,165,.18)'],
  }),

  // Pokémon LeafGreen's title screen: coral #e77b5a between a bright green band #42d663 and a dark green one
  // #007b00, the plant's greens (#4ab58c, #299473, #18735a, #004231) and peach #ffce94. Light and green, a meadow
  // page, the coral kept for the headline's shadow and a glow.
  leafgreen: make({
    bg: '#effbe9', themeColor: '#9fe6b2', ink: '#00321f', ink2: '#1c4a38', muted: '#4f7a66',
    accent: '#007b00', accentInk: '#005239', yellow: '#ffce94', hardShadow: 'rgba(0, 66, 49, 0.18)',
    sky: 'linear-gradient(180deg,#9fe6b2 0,#c4f0cf 45vh,#e2f8e4 110vh,#effbe9 160vh)',
    card: ['#18735a', '#c9eedb', '#ffffff'], btn: ['#18735a', '#007b00', '#ffffff'], btnHot: '#eafbef',
    primary: ['#004231', '#84e7ad', '#42d663', '#63e07f', '#00321f', '#b9f5cf'],
    chip: ['#18735a', '#ffffff', '#18735a'],
    blobs: [['#42d663', 0.4], ['#e77b5a', 0.25], ['#4ab58c', 0.35]],
    bar: ['rgba(196,240,207,.85)', '#004231'], foot: ['rgba(226,248,228,.75)'],
    titleShadow: '#e77b5a', glow: ['rgba(66,214,99,.4)', 'rgba(231,123,90,.2)'],
  }),

  // Pokémon Emerald's title screen: a cloud sky of teals (#087b7b, #089c6b, #088c73) over deep blue (#004a63,
  // #004aa5) and cyan #5fcbdb, the great dragon's silhouette with its rings glowing yellow #e7ef63 / #fff729. Dark.
  emerald: make({
    dark: true, bg: '#022630', shade: '#001018', ink: '#e8fff6', ink2: '#bfe6dc', muted: '#7fb3aa',
    accent: '#e7ef63', accentInk: '#fff729', yellow: '#fff729',
    sky: 'linear-gradient(180deg,#003a5c 0,#022630 70vh)',
    card: ['#089c6b', '#004a63', '#06323c'], btn: ['#43c2bc', '#67f1c3', '#06323c'], btnHot: '#0b4a52',
    primary: ['#001018', '#fffbb0', '#e7ef63', '#fff729', '#004a63', '#fffbb0'],
    chip: ['#089c6b', '#06323c', '#087b7b'],
    blobs: [['#089c6b', 0.35], ['#004aa5', 0.4], ['#5fcbdb', 0.2]], starsOf: ['#e7ef63', '#67f1c3', '#5fcbdb'],
    bar: ['rgba(2,38,48,.85)', '#087b7b'], foot: ['rgba(0,16,24,.5)'],
    titleShadow: '#004aa5', glow: ['rgba(8,156,107,.4)', 'rgba(0,74,165,.3)'],
  }),

  // Pokémon Lazarus's title screen: sunset over the sea - an orange sky (#de5200, #ff7b00, #ff9c00) with dark red
  // streaks #941800 / #631800, a white sun, the sea #318cad / #42add6, sand #dead84 / #efe7c6. Light, warm.
  lazarus: make({
    bg: '#fdf3e0', themeColor: '#ffb066', ink: '#3d1404', ink2: '#5a2108', muted: '#8c5a3a',
    accent: '#941800', accentInk: '#631800', yellow: '#ff9c00', hardShadow: 'rgba(90, 33, 8, 0.18)',
    sky: 'linear-gradient(180deg,#ffb066 0,#ffd3a0 40vh,#efe7c6 110vh,#fdf3e0 160vh)',
    card: ['#845221', '#efe7c6', '#fffaf0'], btn: ['#845221', '#318cad', '#fffaf0'], btnHot: '#e8f5fb',
    primary: ['#631800', '#ffc46b', '#ff7b00', '#ff9c00', '#ffffff', '#941800'],
    chip: ['#845221', '#fffaf0', '#318cad'],
    blobs: [['#ff9c00', 0.45], ['#42add6', 0.3], ['#de5200', 0.3]],
    bar: ['rgba(255,211,160,.82)', '#631800'], foot: ['rgba(253,243,224,.7)'],
    titleShadow: '#ff9c00', glow: ['rgba(255,255,255,.55)', 'rgba(255,156,0,.35)'],
  }),

  // Pokémon Emerald Rogue's title screen: a deep navy night #080042 lit in blues (#08107b -> #1010ad -> #2910ce
  // -> #3110ef), white stars, the yellow logo #fff729 outlined in blue #1810e7. Dark.
  'emerald-rogue': make({
    dark: true, bg: '#080042', shade: '#02001a', ink: '#eef0ff', ink2: '#c9cdf5', muted: '#8f93d0',
    accent: '#fff729', accentInk: '#ffff21', yellow: '#fff729',
    card: ['#3110ef', '#18088c', '#0d0858'], btn: ['#5a73ff', '#c0afff', '#0d0858'], btnHot: '#18088c',
    primary: ['#02001a', '#ffffc6', '#fff729', '#ffff73', '#1810e7', '#ffffc6'],
    chip: ['#2910ce', '#0d0858', '#1810e7'],
    blobs: [['#3110ef', 0.45], ['#08107b', 0.6], ['#1010ad', 0.4]], starsOf: ['#ffffff', '#bdbdbd', '#fff729'],
    bar: ['rgba(8,0,66,.85)', '#2910ce'], foot: ['rgba(2,0,26,.5)'],
    titleShadow: '#1810e7', glow: ['rgba(49,16,239,.45)', 'rgba(255,247,41,.12)'],
  }),
};

/** The CSS a page with [id] adds after the site's own, or '' (no theme, or an unknown one). */
export function themeCss(id) {
  const t = id && THEMES[id];
  if (!t) return '';
  const s = `html[data-theme='${id}']`;
  const tokens = Object.entries(t.tokens).map(([k, v]) => `${k}:${v}`).join(';');
  const frames = Object.entries(t.frames).map(([name, { slice, url }]) =>
    `${s} .f-${name}{border-width:calc(var(--px)*${slice});border-image:${url} ${slice} fill/calc(var(--px)*${slice}) stretch}` +
    `${s} .h-${name}:hover,${s} .h-${name}:focus-visible{border-image-source:${url}}`).join('\n');
  return `${s}{${tokens}}\n${frames}\n${t.css(s)}`;
}
