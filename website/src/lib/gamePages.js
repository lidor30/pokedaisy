// The game pages, pokedaisy.com/g/<slug>/ (src/pages/g/[slug].astro): one per game asked for, made to link from a
// post (a game's subreddit, a device's). Each is an entry here; its pictures are the real app on a player's save,
// made by `python3 scripts/promo_shots.py <slug>` into public/g/<slug>/ (GamePromoShotsTest), and what works comes
// from compat.json (the companion's tabs, the versions) plus `features` below - keep those in step with the game's
// row in docs/ROM_FEATURES.md.

/** Every feature a page can list: [icon (sprites.js), name, what it does]. A page picks by id, or gives its own
 * [icon, name, text] where the game differs. */
export const FEATURES = {
  expand: ['zoom', 'EXPAND', 'The map drawn past the GBA\'s edges to fill a wide screen, rebuilt every frame from the game itself. No stretching.'],
  zoom: ['zoom', 'ZOOM, LOOK AROUND', 'Step the camera out to see more of the area, and look around with the right stick. Text and battles pull back in by themselves.'],
  hud: ['orb', 'HUD', 'Your party, a minimap, your Poké Balls and PC / BAG / WILD buttons on the game as you walk, like a modern Pokémon game. Tap any of it.'],
  quick: ['ring', 'QUICK MENU', 'One button opens a ring of shortcuts: PC, FLY, REPEL, BIKE, ROD, wild encounters, save states and fast-forward. Aim with the stick.'],
  pc: ['pc', 'PC ANYWHERE', 'Open your PC boxes from the bag, the HUD or the quick menu, wherever you stand.'],
  fly: ['feather', 'QUICK FLY', 'Pick a town on the companion\'s map or in the quick menu, and your Pokémon flies you there with the game\'s own animation.'],
  repel: ['spray', 'ITEMS IN ONE PRESS', 'Use potions, Repels and more from the companion\'s bag, give and take held items. The HUD counts a Repel\'s steps down.'],
  wild: ['grassOff', 'WILD ENCOUNTERS OFF', 'One switch stops random encounters in the grass. Fishing and the story\'s battles still happen.'],
  bike: ['run', 'BIKE AND AUTO-RUN', 'Get on the bike from the quick menu, run without holding B and run inside buildings too.'],
  battle: ['battle', 'BATTLE INFO', 'Every matchup at a glance, the foe\'s whole team, its ability, item and stats, and the best pick tagged.'],
  battleButtons: ['battle', 'ONE-TAP BATTLES', 'FIGHT, a move, a switch or RUN in one tap from the companion, straight to the game\'s cursor.'],
  card: ['card', 'TRAINER CARD', 'Your trainer card, drawn from the game\'s own art, front and back.'],
  dex: ['dex', 'POKéDEX', 'Seen and caught marks with full entries: types, stats, abilities, evolutions, where to find it and its moves.'],
  guide: ['guide', 'GUIDE', 'Hints first, answers on a second tap: the wild Pokémon here, the next gym leader\'s team, evolutions.'],
  achievements: ['star', 'ACHIEVEMENTS', 'RetroAchievements while you play, with the game\'s own level-up fanfare.'],
  ff: ['ff', 'SMART FAST-FORWARD', 'Full speed on the road, normal speed in menus. The music keeps its tempo.'],
  tms: ['tmDisc', 'REUSABLE TMS', 'Turn it on and a TM stays in your bag after you teach it, like in newer games.'],
  look: ['walker', 'PLAYER LOOK', 'Play as another Gen 3 game\'s hero, found in your own ROMs, with your rival dressed to match.'],
};

/** The pictures every page can show, by file name in public/g/<slug>/: [label, caption]. */
export const TOP_SHOTS = {
  'top-original': ['ORIGINAL', 'The game as the GBA drew it: 3:2, black bars either side.'],
  'top-expand': ['EXPAND', 'The same moment with EXPAND: the map continues to the screen\'s edges.'],
  'top-hud': ['HUD', 'Your party, the minimap, your Poké Balls and PC / BAG / WILD, over the game.'],
  'top-zoom': ['ZOOM', 'ZOOM steps out to show more of the area around you.'],
  'top-quick': ['QUICK MENU', 'One button: PC, FLY, REPEL, BIKE, ROD, wild encounters, states, fast-forward.'],
};
export const COMPANION_SHOTS = {
  party: ['PARTY', 'Your team in the game\'s own party screen, live.'],
  summary: ['SUMMARY', 'Tap a Pokémon: stats, moves, IVs and EVs, matchups.'],
  bag: ['BAG', 'Every pocket with the game\'s icons and descriptions. USE and GIVE right there.'],
  map: ['MAP', 'The region map with you on it. Tap a place to name it, or FLY.'],
  dex: ['POKéDEX', 'Seen and caught, with full entries.'],
  battle: ['BATTLE', 'Opens by itself in a fight: your moves rated against the foe.'],
  guide: ['GUIDE', 'The next boss and their team, read from the game.'],
};

// The shots and the lead most pages share.
const TOP = ['top-original', 'top-expand', 'top-hud', 'top-zoom', 'top-quick'];
const COMPANION = ['party', 'summary', 'bag', 'map', 'dex', 'battle', 'guide'];
const playLead = (region) => `${region} on any Android handheld or phone: the map drawn to your screen's edges, ZOOM, a HUD and a quick menu on the game, and your team, bag, map and dex live in the companion.`;
const description = (title) => `Play ${title} on Android handhelds and phones with PokeDaisy: EXPAND widescreen, ZOOM, a HUD and quick menu on the game, and a live companion with your party, bag, map, dex, battle info and guide, over the game or on a second screen. Free and open source.`;

export const GAME_PAGES = [
  {
    slug: 'unbound',
    // The names compat.json uses: its matrix row(s) and its hacks list.
    matrixGame: 'Unbound',
    hackTitle: 'Pokémon Unbound',
    // Its own look (themes.js): the title screen's night sky and gold.
    theme: 'unbound',
    title: 'Pokémon Unbound',
    eyebrow: 'POKéMON UNBOUND',
    headline: 'Unbound, played better',
    lead: 'Borrius on any Android handheld or phone: the map drawn to your screen\'s edges, ZOOM, a HUD and a quick menu on the game, and your team, bag, map and dex live in the companion.',
    description: 'Play Pokémon Unbound on Android handhelds and phones with PokeDaisy: EXPAND widescreen, ZOOM, a HUD and quick menu on the game, and a live companion with your party, bag, map, dex, battle info and guide, over the game or on a second screen. Free and open source.',
    // What the companion shows for the save the pictures came from (the player's 271-hour one).
    top: ['top-original', 'top-expand', 'top-hud', 'top-zoom', 'top-quick'],
    companion: ['party', 'summary', 'bag', 'map', 'dex', 'battle', 'guide'],
    // The game's own words for a tab, where it has one (companionTabLabel).
    labels: { bag: 'CUBE' },
    features: ['expand', 'zoom', 'hud', 'quick', 'pc', 'fly', 'repel', 'wild', 'bike', 'battle', 'battleButtons', 'card', 'dex', 'guide', 'achievements', 'ff'],
    notes: [
      'Its TMs never run out by themselves, so REUSABLE TMS shows ON.',
      'Works with English and French v2.1.1.1, recognised by the exact file.',
    ],
  },
  {
    slug: 'soulgold',
    matrixGame: 'SoulGold',
    hackTitle: 'Pokémon SoulGold',
    // The title screen's dawn sky over the sea (themes.js).
    theme: 'soulgold',
    title: 'Pokémon SoulGold',
    eyebrow: 'POKéMON SOULGOLD',
    headline: 'SoulGold, played better',
    lead: 'Johto on any Android handheld or phone: the map drawn to your screen\'s edges, ZOOM, a HUD and a quick menu on the game, and your team, bag, map and dex live in the companion.',
    description: 'Play Pokémon SoulGold on Android handhelds and phones with PokeDaisy: EXPAND widescreen, ZOOM, a HUD and quick menu on the game, and a live companion with your party, bag, map, dex, battle info and guide, over the game or on a second screen. Free and open source.',
    // compat.json's names for the three v1.2 builds are ours; players know them as one release.
    versions: ['v1.1.4', 'v1.2 (all three builds)'],
    top: ['top-original', 'top-expand', 'top-hud', 'top-zoom', 'top-quick'],
    companion: ['party', 'summary', 'bag', 'map', 'dex', 'battle', 'guide'],
    // Its row in docs/ROM_FEATURES.md: field items are the Repels alone, no AUTO-RUN / bike reads, no achievements set checked.
    features: ['expand', 'zoom', 'hud', 'quick', 'pc', 'fly',
      ['spray', 'REPELS IN ONE PRESS', 'Use a Repel from the companion\'s bag or the quick menu, and the HUD counts its steps down.'],
      'wild', 'battle', 'battleButtons', 'card', 'dex',
      ['guide', 'GUIDE', 'The wild Pokémon where you stand and the next gym leader\'s team, read from the game.'],
      'ff'],
    notes: [
      'Its TMs never run out by themselves, so REUSABLE TMS shows ON.',
      'The quick menu\'s BIKE and ROD use the game\'s own SELECT wheel.',
      'Works with v1.1.4 and every v1.2 build, recognised by the exact file.',
    ],
  },
  {
    slug: 'firered',
    matrixGame: 'FireRed',
    theme: 'firered',
    title: 'Pokémon FireRed',
    eyebrow: 'POKéMON FIRERED',
    headline: 'FireRed, played better',
    lead: playLead('Kanto'),
    description: description('Pokémon FireRed'),
    versions: ['English', 'ES / DE / FR / IT / JP'],
    top: TOP, companion: COMPANION,
    features: ['expand', 'zoom', 'hud', 'quick', 'pc', 'fly', 'repel', 'wild', 'bike', 'battle', 'battleButtons', 'card', 'dex', 'guide', 'tms', 'look', 'achievements', 'ff'],
    notes: [
      'Every release in every language, recognised by the exact file. The first English release (rev 0) and the other languages have no trainer card yet.',
      'On English rev 1 a move is picked in one press; elsewhere the companion steers the game\'s own cursor to it.',
    ],
  },
  {
    slug: 'leafgreen',
    matrixGame: 'LeafGreen',
    theme: 'leafgreen',
    title: 'Pokémon LeafGreen',
    eyebrow: 'POKéMON LEAFGREEN',
    headline: 'LeafGreen, played better',
    lead: playLead('Kanto'),
    description: description('Pokémon LeafGreen'),
    versions: ['English', 'ES / DE / FR / IT / JP'],
    top: TOP, companion: COMPANION,
    features: ['expand', 'zoom', 'hud', 'quick', 'pc', 'fly', 'repel', 'wild', 'bike', 'battle',
      ['battle', 'ONE-TAP BATTLES', 'FIGHT, a move or RUN in one tap from the companion, steered through the game\'s own menus.'],
      'card', 'dex', 'guide', 'tms', 'look', 'achievements', 'ff'],
    notes: [
      'Every release in every language, recognised by the exact file. The trainer card is on the English releases.',
      'Its own guide: the Game Corner prizes and trades are LeafGreen\'s.',
    ],
  },
  {
    slug: 'emerald',
    matrixGame: 'Emerald',
    theme: 'emerald',
    title: 'Pokémon Emerald',
    eyebrow: 'POKéMON EMERALD',
    headline: 'Emerald, played better',
    lead: playLead('Hoenn'),
    description: description('Pokémon Emerald'),
    versions: ['English', 'ES / DE / FR / IT / JP'],
    top: TOP, companion: COMPANION,
    features: ['expand', 'zoom', 'hud', 'quick', 'pc', 'fly', 'repel', 'wild', 'bike', 'battle', 'battleButtons', 'card', 'dex', 'guide', 'tms', 'look', 'achievements', 'ff'],
    notes: [
      'Every release in every language, recognised by the exact file. The Spanish, German, French and Italian ones show names in their own language; the trainer card is on the English one.',
    ],
  },
  {
    slug: 'lazarus',
    matrixGame: 'Lazarus',
    hackTitle: 'Pokémon Lazarus',
    theme: 'lazarus',
    title: 'Pokémon Lazarus',
    eyebrow: 'POKéMON LAZARUS',
    headline: 'Lazarus, played better',
    lead: 'Lazarus on any Android handheld or phone: the map drawn to your screen\'s edges, ZOOM, a HUD and a quick menu on the game, and your team, bag, map and dex live in the companion.',
    description: description('Pokémon Lazarus'),
    top: TOP, companion: COMPANION,
    // Its row in docs/ROM_FEATURES.md: no field items (so no REPEL / BIKE / ROD tiles), no reusable TMs or player look.
    features: ['expand', 'zoom', 'hud',
      ['ring', 'QUICK MENU', 'One button opens a ring of shortcuts: PC, FLY, wild encounters, save states and fast-forward. Aim with the stick.'],
      'pc', 'fly', 'wild',
      ['run', 'AUTO-RUN', 'Run without holding B, and inside buildings too.'],
      'battle', 'battleButtons', 'card', 'dex',
      ['guide', 'GUIDE', 'The wild Pokémon where you stand, by its clock (night Pokémon at night), and the next boss\'s team.'],
      'ff'],
    notes: ['Works with v2.0, recognised by the exact file. Its own trainer card and region map, read from your ROM.'],
  },
  {
    slug: 'emerald-rogue',
    matrixGame: 'Emerald Rogue',
    hackTitle: 'Pokémon Emerald Rogue',
    theme: 'emerald-rogue',
    title: 'Pokémon Emerald Rogue',
    eyebrow: 'POKéMON EMERALD ROGUE',
    headline: 'Emerald Rogue, played better',
    lead: 'Every run on any Android handheld or phone: the map drawn to your screen\'s edges, ZOOM and a HUD on the game, and your team, bag, map and dex live in the companion.',
    description: description('Pokémon Emerald Rogue'),
    top: TOP, companion: ['party', 'summary', 'bag', 'map', 'dex', 'battle'],
    // Its row in docs/ROM_FEATURES.md: EXPAND and the companion; the field shortcuts aren't mapped for it.
    features: ['expand', 'zoom',
      ['orb', 'HUD', 'Your party, a minimap and your Poké Balls on the game as you walk. Tap any of it.'],
      ['ring', 'QUICK MENU', 'One button: save states, fast-forward and the companion, over the game.'],
      ['battle', 'BATTLE INFO', 'Every matchup at a glance: your moves rated against the foe, its weaknesses and resistances.'],
      'dex', 'ff'],
    notes: [
      'Works with v2.2.1-EX, recognised by the exact file. The hub is named by your save.',
      'Its battles are its own, so picking moves from the companion is off; the INFO pane works.',
    ],
  },
];

export const gamePage = (slug) => GAME_PAGES.find((p) => p.slug === slug);
