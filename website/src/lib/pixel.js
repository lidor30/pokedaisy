// Pixel art for the site, the app's way (companion/ui/PixelIcons.kt): one string per row, one
// character per pixel, '.' transparent, drawn as flat SVG rects so every pixel stays a crisp
// block at any whole scale. The Poké Ball, coffee cup, GitHub mark and cursors are the app's own
// bitmaps; the rest are drawn in the same style.

import { CREATURES } from './creatures.js';
import { HANDHELD_SPRITES } from './handheld.js';
import { regionMapSprite, seaSprite } from './regionmap.js';

const INK = '#202020';

export const SPRITES = {
  pokeBall: {
    pal: { K: INK, R: '#e83028', W: '#ffffff', H: '#f8a8a0' },
    rows: [
      '....KKKK....',
      '..KKRRRRKK..',
      '.KRRRRRRHHK.',
      '.KRRRRRRRHK.',
      'KRRRRKKRRRRK',
      'KKKKKWWKKKKK',
      'KKKKKWWKKKKK',
      'KWWWWKKWWWWK',
      'KWWWWWWWWWWK',
      '.KWWWWWWWWK.',
      '..KKWWWWKK..',
      '....KKKK....',
    ],
  },
  daisy: {
    pal: { K: INK, W: '#ffffff', Y: '#f8d030', O: '#d89800' },
    rows: [
      '.KK...KK.',
      'KWWK.KWWK',
      'KWWWKWWWK',
      '.KWYYYWK.',
      '..KYOYK..',
      '.KWYYYWK.',
      'KWWWKWWWK',
      'KWWK.KWWK',
      '.KK...KK.',
    ],
  },
  coffee: {
    pal: { K: INK, B: '#784020', C: '#ffdd00', S: '#b0b0b0' },
    rows: [
      '....S..S........',
      '...S..S.........',
      '....S..S........',
      '...S..S.........',
      '................',
      '.KKKKKKKKKKKK...',
      '.KBBBBBBBBBBKKK.',
      '.KCCCCCCCCCCK..K',
      '.KCCCCCCCCCCK..K',
      '.KCCCCCCCCCCK..K',
      '.KCCCCCCCCCCKKK.',
      '.KCCCCCCCCCCK...',
      '..KCCCCCCCCK....',
      '...KKKKKKKK.....',
      'KKKKKKKKKKKKKK..',
      '................',
    ],
  },
  github: {
    pal: { 1: INK },
    rows: [
      '0000011111100000',
      '0001111111111000',
      '0011111111111100',
      '0111011111101110',
      '0111001111001110',
      '1111000000001111',
      '1110000000000111',
      '1110000000000111',
      '1110000000000111',
      '1110000000000111',
      '1111000000001111',
      '0101110000111110',
      '0110110000111110',
      '0011000000111100',
      '0001110000111000',
      '0000010000100000',
    ].map((r) => r.replaceAll('0', '.')),
  },
  cursor: {
    pal: { K: '#cb0707' },
    rows: ['K....', 'KK...', 'KKK..', 'KKKK.', 'KKK..', 'KK...', 'K....'],
  },
  // FireRed's "more text" arrow under a message.
  more: {
    pal: { R: '#e83028', D: '#a01810' },
    rows: ['RRRRRRR', 'DRRRRRD', '.DRRRD.', '..DRD..', '...D...'],
  },
  download: {
    pal: { K: INK, W: '#ffffff' },
    rows: [
      '...KKKK...',
      '...KWWK...',
      '...KWWK...',
      '...KWWK...',
      'KKKKWWKKKK',
      '.KWWWWWWK.',
      '..KWWWWK..',
      '...KWWK...',
      '....KK....',
      'KKKKKKKKKK',
    ],
  },
  bag: {
    pal: { K: INK, B: '#e09040', D: '#a05818', Y: '#f8d030' },
    rows: [
      '....KKKKKK....',
      '...KK....KK...',
      '...K......K...',
      '.KKKKKKKKKKKK.',
      'KBBBBBBBBBBBBK',
      'KBBBBBBBBBBBDK',
      'KDDDDDDDDDDDDK',
      'KBBBBKKKKBBBDK',
      'KBBBBKYYKBBBDK',
      'KBBBBKKKKBBBDK',
      'KBBBBBBBBBBBDK',
      'KBBBBBBBBBBBDK',
      'KDDDDDDDDDDDDK',
      '.KKKKKKKKKKKK.',
    ],
  },
  battle: {
    pal: { K: INK, Y: '#f8d030', O: '#d89800' },
    rows: [
      '.......KKKKK',
      '......KYYYOK',
      '.....KYYYOK.',
      '....KYYYOK..',
      '...KYYYYKKKK',
      '..KYYYYYYYOK',
      '.KKKKKYYYOK.',
      '....KYYYOK..',
      '...KYYYOK...',
      '..KYYOK.....',
      '..KYOK......',
      '.KYK........',
      '.KK.........',
    ],
  },
  map: {
    pal: { K: INK, G: '#58c058', D: '#389038', U: '#58a0f0', R: '#e83028', W: '#ffffff' },
    rows: [
      'KKKKKKKKKKKKKK',
      'KGGGGKUUUUKGGK',
      'KGGRRKUUUUKGGK',
      'KGRWRKUUUGKGGK',
      'KGGRRKUUGGKGDK',
      'KGGGRKUGGGKGGK',
      'KGDGGKGGGDKGUK',
      'KGGGGKGGGGKUUK',
      'KUGGGKGGDGKUUK',
      'KUUGGKGGGGKUUK',
      'KKKKKKKKKKKKKK',
    ],
  },
  dex: {
    pal: { K: INK, R: '#e83028', D: '#a01810', U: '#88d8f8', W: '#ffffff', G: '#58c058' },
    rows: [
      '.KKKKKKKKKK.',
      'KRRRRRRRRRDK',
      'KRKKKKKKKKDK',
      'KRKUUUUUWKDK',
      'KRKUUUUUUKDK',
      'KRKUUUUUUKDK',
      'KRKKKKKKKKDK',
      'KRRRRRRRRRDK',
      'KRKKRRRRRRDK',
      'KRKKRRKGKRDK',
      'KRRRRRRRRRDK',
      '.KKKKKKKKKK.',
    ],
  },
  guide: {
    pal: { K: INK, W: '#ffffff', L: '#a8a8b0', R: '#e83028' },
    rows: [
      '.KKKKK..KKKKK.',
      'KWWWWWKKWWWWWK',
      'KWLLLWKKWLLLWK',
      'KWWWWWKKWWWWWK',
      'KWLLLWKKWLLRWK',
      'KWWWWWKKWWWRWK',
      'KWLLLWKKWLLRWK',
      'KWWWWWKKWWWWWK',
      'KWWWWWKKWWWWWK',
      '.KKKKKKKKKKKK.',
    ],
  },
  card: {
    pal: { K: INK, U: '#5890e8', W: '#ffffff', S: '#f8c8a0', N: '#283878', Y: '#f8d030' },
    rows: [
      'KKKKKKKKKKKKKK',
      'KUUUUUUUUUUUUK',
      'KUKKKKUUWWWWUK',
      'KUKSSKUUUUUUUK',
      'KUKSSKUUWWWUUK',
      'KUKNNKUUUUUUUK',
      'KUKKKKUUWWWWUK',
      'KUUUUUUUUUUUUK',
      'KUYUYUYUYUUUUK',
      'KKKKKKKKKKKKKK',
    ],
  },
  states: {
    pal: { K: INK, U: '#5890e8', W: '#ffffff', L: '#c8c8d0' },
    rows: [
      'KKKKKKKKKKKK',
      'KUUWWWWWWUUK',
      'KUUWWWWKWUUK',
      'KUUWWWWKWUUK',
      'KUUWWWWWWUUK',
      'KUUUUUUUUUUK',
      'KUULLLLLLUUK',
      'KUULKKKKLUUK',
      'KUULLLLLLUUK',
      'KUULKKKKLUUK',
      'KKKKKKKKKKKK',
    ],
  },
  ff: {
    pal: { K: INK, Y: '#f8d030' },
    rows: [
      'K.....K.....',
      'KK....KK....',
      'KYK...KYK...',
      'KYYK..KYYK..',
      'KYYYK.KYYYK.',
      'KYYYYKKYYYYK',
      'KYYYK.KYYYK.',
      'KYYK..KYYK..',
      'KYK...KYK...',
      'KK....KK....',
      'K.....K.....',
    ],
  },
  music: {
    pal: { K: INK, U: '#5890e8' },
    rows: [
      '....KKKKKKK',
      '....KUUUUUK',
      '....KKKKKKK',
      '....K.....K',
      '....K.....K',
      '..KKK...KKK',
      '.KUUK..KUUK',
      'KUUUK.KUUUK',
      '.KKK...KKK.',
    ],
  },
  tv: {
    pal: { K: INK, S: '#909098', U: '#58a0f0', W: '#d8f0ff', D: '#3060b0' },
    rows: [
      '...K....K...',
      '....K..K....',
      'KKKKKKKKKKKK',
      'KSSSSSSSSSSK',
      'KSUUUUUUUUSK',
      'KSUWDUUUUUSK',
      'KSUUUUUUUUSK',
      'KSUUUUUUDUSK',
      'KSSSSSSSSSSK',
      'KKKKKKKKKKKK',
      '.KK......KK.',
    ],
  },

  clock: {
    pal: { K: INK, W: '#ffffff', R: '#e83028' },
    rows: [
      '...KKKKK...',
      '.KKWWWWWKK.',
      '.KWWWKWWWK.',
      'KWWWWKWWWWK',
      'KWWWWKWWWWK',
      'KWWWWKKKRWK',
      'KWWWWWWWWWK',
      'KWWWWWWWWWK',
      '.KWWWWWWWK.',
      '.KKWWWWWKK.',
      '...KKKKK...',
    ],
  },
  heart: {
    pal: { K: INK, R: '#e83028', H: '#f8a8a0' },
    rows: ['.KK.KK.', 'KRRKRRK', 'KHRRRRK', 'KRRRRRK', '.KRRRK.', '..KRK..', '...K...'],
  },
  star: {
    pal: { K: INK, Y: '#f8d030' },
    rows: ['....K....', '...KYK...', 'KKKKYKKKK', 'KYYYYYYYK', '.KYYYYYK.', '..KYYYK..', '.KYYKYYK.', '.KYK.KYK.', '.KK...KK.'],
  },
  bug: {
    pal: { K: INK, G: '#58c058', D: '#389038' },
    rows: ['.K.....K.', '..K...K..', '...KKK...', '..KGGGK..', 'KKGDGDGKK', '.KGGDGGK.', 'KKGDGDGKK', '.KGGDGGK.', '..KKKKK..'],
  },
  sparkle: {
    pal: { W: '#ffffff' },
    rows: ['..W..', '..W..', 'WWWWW', '..W..', '..W..'],
  },
  yes: {
    pal: { K: INK, G: '#38a848', W: '#ffffff' },
    rows: ['..KKKKK..', '.KGGGGGK.', 'KGGGGGWGK', 'KGGGGWGGK', 'KGWGWGGGK', 'KGGWGGGGK', 'KGGGGGGGK', '.KGGGGGK.', '..KKKKK..'],
  },
  part: {
    pal: { K: INK, Y: '#f8c018', L: '#e8e8e8' },
    rows: ['..KKKKK..', '.KYYYLLK.', 'KYYYYLLLK', 'KYYYYLLLK', 'KYYYYLLLK', 'KYYYYLLLK', 'KYYYYLLLK', '.KYYYLLK.', '..KKKKK..'],
  },
  no: {
    pal: { S: '#a0a0a8' },
    rows: ['.........', '.........', '.........', '.........', '.SSSSSSS.', '.........', '.........', '.........', '.........'],
  },
};

SPRITES.spark = { pal: { W: '#f8d030' }, rows: SPRITES.sparkle.rows };
// Ko-fi (a mug with a heart), Patreon (its bar and circle) and Reddit (the alien), drawn like the GitHub mark.
SPRITES.kofi = {
  pal: { K: INK, W: '#ffffff', R: '#ff5e5b' },
  rows: [
    '.KKKKKKKKKKK..',
    '.KWWWWWWWWWKKK',
    '.KWWRRWRRWWK.K',
    '.KWRRRRRRRWK.K',
    '.KWRRRRRRRWK.K',
    '.KWWRRRRRWWKKK',
    '.KWWWRRRWWWK..',
    '.KWWWWRWWWWK..',
    '..KWWWWWWWK...',
    '...KKKKKKK....',
  ],
};
SPRITES.patreon = {
  pal: { K: INK, D: '#052d49', P: '#ff424d', H: '#ffa0a4' },
  rows: [
    'KKK...KKKK..',
    'KDK..KPPPPK.',
    'KDK.KPPHPPPK',
    'KDK.KPPPPPPK',
    'KDK.KPPPPPPK',
    'KDK.KPPPPPPK',
    'KDK..KPPPPK.',
    'KDK...KKKK..',
    'KDK.........',
    'KKK.........',
  ],
};
SPRITES.reddit = {
  pal: { K: INK, W: '#ffffff', O: '#ff4500' },
  rows: [
    '.........KKK.',
    '........KOOOK',
    '......K.KOOOK',
    '......K..KKK.',
    '...KKKKKKK...',
    'KKKWWWWWWWKKK',
    'KWKWWWWWWWKWK',
    'KKWOOWWWOOWKK',
    '.KWOOWWWOOWK.',
    '.KWWWWWWWWWK.',
    '.KWKWWWWWKWK.',
    '..KWWKKKWWK..',
    '...KKKKKKK...',
  ],
};
SPRITES.discord = {
  pal: { K: INK, W: '#ffffff', B: '#5865f2' },
  rows: [
    '..KK.....KK..',
    '.KBBKKKKKBBK.',
    '.KBBBBBBBBBK.',
    'KBBBBBBBBBBBK',
    'KBBWWBBBWWBBK',
    'KBBWWBBBWWBBK',
    'KBBWWBBBWWBBK',
    'KBBBBBBBBBBBK',
    'KBBBBBBBBBBBK',
    '.KBBKKKKKBBK.',
    '..KK.....KK..',
  ],
};
SPRITES.dual = {
  pal: { K: INK, U: '#58a0f0', W: '#d8f0ff', S: '#909098', L: '#c8c8d0', G: '#58c058' },
  rows: [
    '.KKKKKKKKKKKK.',
    'KSSSSSSSSSSSSK',
    'KSUUUUUUUUUUSK',
    'KSUWUUUUUUUUSK',
    'KSUUUUUUUUUUSK',
    'KSUUUUUUUUUUSK',
    'KSSSSSSSSSSSSK',
    '.KKKKKKKKKKKK.',
    '..KSSSSSSSSK..',
    '.KKKKKKKKKKKK.',
    'KLLLKGGGGKLLLK',
    'KLKLKGGGGKLKLK',
    'KLLLKGGGGKLLLK',
    'KLLLKKKKKKLLLK',
    '.KKKKKKKKKKKK.',
  ],
};
// The quick menu's ring of action tiles.
SPRITES.ring = {
  pal: { K: INK, R: '#e83028', B: '#3d6fe0', G: '#58c058', Y: '#f8d030', P: '#9058d8', O: '#f08030', S: '#909098' },
  rows: [
    '.....KKKK.....',
    '.....KRRK.....',
    '.....KRRK.....',
    'KKKK.KKKK.KKKK',
    'KOOK......KBBK',
    'KOOK......KBBK',
    'KKKK......KKKK',
    '......SS......',
    '......SS......',
    'KKKK......KKKK',
    'KPPK......KGGK',
    'KPPK......KGGK',
    'KKKK.KKKK.KKKK',
    '.....KYYK.....',
    '.....KYYK.....',
    '.....KKKK.....',
  ],
};
SPRITES.single = {
  pal: { K: INK, U: '#58a0f0', W: '#d8f0ff', L: '#c8c8d0' },
  rows: [
    '.KKKKKKKKKKKKKKKK.',
    'KLLLLKKKKKKKKLLLLK',
    'KLLKLKUWUUUUKLKLLK',
    'KLKKKKUUUUUUKLLLLK',
    'KLLKLKUUUUUUKLKKLK',
    'KLLLLKUUUUUUKLLLLK',
    'KLLLLKKKKKKKKLLLLK',
    '.KKKKKKKKKKKKKKKK.',
  ],
};

// The app's own bitmaps for its battle / party screens (PixelIcons.kt, PartyScreen.kt).
SPRITES.partyBall = {
  pal: { K: '#4a4a63', R: '#b54200', H: '#c65200', W: '#b5bdce' },
  rows: [
    '.......KKKKKK.......', '.....KKKKKKKKKK.....', '....KKKRRRRRRKKK....', '...KKRRRRRRRRRRKK...',
    '..KKRRRRRRRRRRRRKK..', '.KKRRRRRRRRRRRRRRKK.', '.KKRRRRRRRRRRRRRRKK.', 'KKRRRRRRKKKKRRRRRRKK',
    'KKHHHHHKKWWKKRRRRRKK', 'KKKKKKKKWWWWKKKKKKKK', 'KKKKKKKKWWWWKKKKKKKK', 'KKWWWWWKKWWKKWWWWWKK',
    'KKWWWWWWKKKKWWWWWWKK', '.KKWWWWWWWWWWWWWWKK.', '.KKWWWWWWWWWWWWWWKK.', '..KKWWWWWWWWWWWWKK..',
    '...KKWWWWWWWWWWKK...', '....KKKWWWWWWKKK....', '.....KKKKKKKKKK.....', '.......KKKKKK.......',
  ],
};
SPRITES.disc = {
  pal: { K: '#8a8a8a', G: '#a8a8a8' },
  rows: ['....KKKK....', '..KKGGGGKK..', '.KGGGGGGGGK.', '.KGGGGGGGGK.', 'KGGGGGGGGGGK', 'KGGGGGGGGGGK',
    'KGGGGGGGGGGK', 'KGGGGGGGGGGK', '.KGGGGGGGGK.', '.KGGGGGGGGK.', '..KKGGGGKK..', '....KKKK....'],
};
const ARROW_UP = ['0000000110000000', '0000001111000000', '0000011111100000', '0000111111110000', '0001111111111000',
  '0011111111111100', '0111111111111110', '1111111111111111', '0000011111100000', '0000011111100000',
  '0000011111100000', '0000011111100000', '0000011111100000', '0000011111100000', '0000011111100000', '0000011111100000'];
const ink = (rows, c = '#ffffff') => ({ pal: { 1: c }, rows: rows.map((r) => r.replaceAll('0', '.')) });
SPRITES.arrowUp = ink(ARROW_UP);
SPRITES.arrowDown = ink([...ARROW_UP].reverse());
SPRITES.back = ink(['0000100000000000', '0001100000000000', '0011100000000000', '0111111111110000', '1111111111111100',
  '0111111111111110', '0011100000001111', '0001100000000111', '0000100000000111', '0000000000000111', '0000000000000111',
  '0000000000001111', '0011111111111110', '0011111111111100', '0011111111110000']);
SPRITES.gear = ink(['0000001111000000', '0000001111000000', '0011001111001100', '0011111111111100', '0001111111111000',
  '0001110000111000', '1111100000011111', '1111100000011111', '1111100000011111', '1111100000011111', '0001110000111000',
  '0001111111111000', '0011111111111100', '0011001111001100', '0000001111000000', '0000001111000000'], '#585858');
SPRITES.male = ink(['00001111', '00000011', '00000101', '01111000', '10001000', '10001000', '10001000', '01110000'], '#42a5ff');
SPRITES.female = ink(['01110', '10001', '10001', '10001', '01110', '00100', '11111', '00100'], '#ff7b73');
SPRITES.maleParty = ink(SPRITES.male.rows.map((r) => r.replaceAll('1', '1').replaceAll('.', '0')), '#42ceff');
SPRITES.femaleParty = ink(SPRITES.female.rows.map((r) => r.replaceAll('.', '0')), '#ff9c94');

// Stand-ins for Pokémon icons (the game's own sprites are never bundled): a type's emblem.
SPRITES.flame = {
  pal: { K: INK, R: '#e84820', Y: '#f8c030', W: '#fff8c0' },
  rows: ['......K.........', '.....KRK........', '.....KRRK.......', '....KRRRK..K....', '....KRYRRK.KK...',
    '...KRRYYRK.KRK..', '...KRYYYRRKRRK..', '..KRRYYWYRRRRK..', '..KRYYWWYYRRK...', '..KRYYWWWYYRK...',
    '..KRRYYWWYYRK...', '...KRRYYYYRK....', '....KKRRRRKK....', '......KKKK......'],
};
SPRITES.feather = {
  pal: { K: INK, W: '#ffffff', L: '#a890f0', D: '#7860c8' },
  rows: ['..........KKK', '........KKWWK', '.......KWWWLK', '......KWWWLK.', '.....KWWWLDK.', '....KWWWLDK..',
    '...KWWWLDK...', '..KWWLLDK....', '..KWLDKK.....', '.KKKK........', 'KK...........'],
};
SPRITES.leaf = {
  pal: { K: INK, G: '#78c850', D: '#3f8a28' },
  rows: ['.........KKK', '.......KKGGK', '.....KKGGGGK', '....KGGGGDGK', '...KGGGGDGK.', '..KGGGGDGGK.',
    '..KGGGDGGK..', '.KGGDDGGK...', '.KGDGGKK....', '.KDKKK......', 'KDK.........', 'KK..........'],
};
SPRITES.rock = {
  pal: { K: INK, S: '#c8b068', L: '#e8d898', D: '#8a7438' },
  rows: ['....KKKKK....', '..KKSSSSSKK..', '.KSSLSSSSSSK.', '.KSLLSSSDSSK.', 'KSSSSSSDDSSSK', 'KSSSSSSSSSSDK',
    'KSDSSSSSSSDDK', '.KDDSSSDDDDK.', '..KKKKKKKKK..'],
};
SPRITES.egg = {
  pal: { K: INK, W: '#f8f8e8', G: '#88c858' },
  rows: ['....KKKK....', '..KKWWWWKK..', '.KWWWGGWWWK.', '.KWWWGGWWWK.', 'KWGGWWWWWWWK', 'KWGGWWWWGGWK',
    'KWWWWWWWGGWK', 'KWWWGGWWWWWK', '.KWWGGWWWWK.', '..KKWWWWKK..', '....KKKK....'],
};
SPRITES.abxy = {
  pal: { K: INK, G: '#5a5a66', L: '#8a8a98' },
  rows: ['....KKK....', '...KLLGK...', '...KGGGK...', 'KKK.KKK.KKK', 'KLGK...KLGK', 'KGGK...KGGK', 'KKK.KKK.KKK',
    '...KLLGK...', '...KGGGK...', '....KKK....'],
};

// The hero handheld's top screen: an overworld of our own (no game art).
SPRITES.cloud = {
  pal: { W: '#ffffff', L: '#d8ecf8' },
  rows: ['........WWWWW...........', '......WWWWWWWW..........', '...WWWWWWWWWWWW.WWWW....', '..WWWWWWWWWWWWWWWWWWWW..',
    '.WWWWWWWWWWWWWWWWWWWWWW.', 'WWWWWWWWWWWWWWWWWWWWWWWW', 'LLLLWWWWWWWWWWWWWWWWLLLL', '.LLLLLLLLLLLLLLLLLLLLLL.'],
};
SPRITES.tree = {
  pal: { K: '#24402a', G: '#58a848', L: '#88d070', D: '#3c8038', B: '#8a5a30' },
  rows: ['.....KKKKKK.....', '...KKGGGGGGKK...', '..KGGGGLGGGGGK..', '.KGGLLGGGGGLGGK.', '.KGGGGGGGDGGGGK.',
    'KGGLGGGGGGGGLGGK', 'KGGGGGDGGGLGGGGK', 'KGDGGGGGGGGGGDGK', '.KGGGGLGGDGGGGK.', '.KDGGGGGGGGGDK..',
    '..KKDDGGGDDKK...', '....KKKBBKKK....', '......KBBK......', '......KBBK......', '.....KBBBBK.....', '.....KKKKKK.....'],
};
SPRITES.tuft = {
  pal: { D: '#58a838', L: '#a8e078' },
  rows: ['................', '..D.............', '.D.D.......D....', '...........D.D..', '................',
    '.......L........', '......D.D.......', '..............D.', '.............D.D', '....D...........',
    '...D.D..........', '..........L.....', '.........D.D....', '................', '.D..............', 'D.D.............'],
};
SPRITES.hill = {
  pal: { H: '#9ccf78', D: '#88c068' },
  rows: ['..........HHHHHH................', '.......HHHHHHHHHHHH.............', '.....HHHHHHHHHHHHHHHH......HHH..',
    '...HHHHHHHHHHHHHHHHHHHH..HHHHHHH', '.HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH', 'HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH',
    'DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD'],
};
SPRITES.path = {
  pal: { S: '#e8d098', D: '#d0b070', P: '#f4e0b0' },
  rows: ['SSSSSSSSSSSSSSSS', 'SSSDSSSSSSSPSSSS', 'SSSSSSSSDSSSSSSS', 'SPSSSSSSSSSSSDSS', 'SSSSSSDSSSSSSSSS',
    'SSSSSSSSSSSSPSSS', 'SSDSSSSSSSSSSSSS', 'SSSSSSSSSPSSSSDS'],
};

// The demos' party orb (not the games' ball): a glossy round orb with a little daisy on it.
function daisyOrb(size, outline) {
  const c = size / 2;
  const r = size / 2 - 0.2;
  const inside = (x, y) => Math.hypot(x + 0.5 - c, y + 0.5 - c) < r;
  const f = Math.floor(c) - 1; // the flower's 2x2 heart
  const petal = (x, y) => (x >= f && x <= f + 1 && (y === f - 1 || y === f + 2)) || (y >= f && y <= f + 1 && (x === f - 1 || x === f + 2));
  const rows = [];
  for (let y = 0; y < size; y++) {
    let row = '';
    for (let x = 0; x < size; x++) {
      if (!inside(x, y)) { row += '.'; continue; }
      if ([[1, 0], [-1, 0], [0, 1], [0, -1]].some(([dx, dy]) => !inside(x + dx, y + dy))) { row += 'K'; continue; }
      if (x >= f && x <= f + 1 && y >= f && y <= f + 1) { row += 'Y'; continue; }
      if (petal(x, y)) { row += 'W'; continue; }
      const dx = x + 0.5 - c;
      const dy = y + 0.5 - c;
      if (dx + dy > size * 0.42) row += 'D';
      else if (Math.hypot(dx + size * 0.2, dy + size * 0.22) < size * 0.12) row += 'H';
      else row += 'T';
    }
    rows.push(row);
  }
  return { pal: { K: outline, T: '#4aa8d8', H: '#c8f0fc', D: '#2c78a8', W: '#ffffff', Y: '#f8d030' }, rows };
}
SPRITES.orb = daisyOrb(12, INK);
SPRITES.partyOrb = daisyOrb(18, '#4a4a63');
SPRITES.phone = {
  pal: { K: INK, S: '#55556a', U: '#58a0f0', W: '#d8f0ff', G: '#58c058', L: '#8a8a98' },
  rows: ['.KKKKKKKKKK.', 'KSSSSSSSSSSK', 'KSUUUUUUUUSK', 'KSUWUUUUUUSK', 'KSUUUUUUUUSK', 'KSUUUUUUUUSK',
    'KSKKKKKKKKSK', 'KSGGGGGGGGSK', 'KSGLLGGLLGSK', 'KSGGGGGGGGSK', 'KSGLLGGLLGSK', 'KSGGGGGGGGSK',
    'KSSSSKKSSSSK', '.KKKKKKKKKK.'],
};

// More of the hero's overworld - all our own.
SPRITES.house = {
  pal: { K: '#2a2430', R: '#d84838', D: '#a02c22', W: '#f4ead0', S: '#d8c8a0', U: '#78c0f0', B: '#8a5a30', Y: '#f8d030' },
  rows: ['.........KKKKKK.........', '.......KKRRRRRRKK.......', '.....KKRRRRRRRRRRKK.....', '...KKRRRRRRRRRRRRRRKK...',
    '.KKRRRRRRRRRRRRRRRRRRKK.', 'KRRRRRRRRRRRRRRRRRRRRRRK', 'KDDDDDDDDDDDDDDDDDDDDDDK', 'KKKKKKKKKKKKKKKKKKKKKKKK',
    '.KWWWWWWWWWWWWWWWWWWWWK.', '.KWKKKKWWWWWWWWWWKKKKWK.', '.KWKUUKWWWKKKKWWWKUUKWK.', '.KWKUUKWWWKBBKWWWKUUKWK.',
    '.KWKKKKWWWKBBKWWWKKKKWK.', '.KWWWWWWWWKBBKWWWWWWWWK.', '.KSSSSSSSSKBYKSSSSSSSSK.', '.KKKKKKKKKKKKKKKKKKKKKK.'],
};
SPRITES.fence = {
  pal: { K: '#5a3e2a', W: '#f0e2c0' },
  rows: ['..KK....', '.KWWK...', 'KKWWKKKK', 'WWWWWWWW', 'KKWWKKKK', '.KWWK...', '.KWWK...', '.KKKK...'],
};
SPRITES.sign = {
  pal: { K: '#3a2a20', B: '#b07840', L: '#e0b070' },
  rows: ['KKKKKKKKKK', 'KBBBBBBBBK', 'KBLLLLLLBK', 'KBBBBBBBBK', 'KBLLLLBBBK', 'KBBBBBBBBK', 'KKKKKKKKKK',
    '....KK....', '....KK....', '...KKKK...'],
};
SPRITES.pond = (() => {
  const w = 30, h = 12;
  const inside = (x, y) => ((x + 0.5 - w / 2) / (w / 2 - 0.3)) ** 2 + ((y + 0.5 - h / 2) / (h / 2 - 0.3)) ** 2 < 1;
  const rows = [];
  for (let y = 0; y < h; y++) {
    let row = '';
    for (let x = 0; x < w; x++) {
      if (!inside(x, y)) { row += '.'; continue; }
      if ([[1, 0], [-1, 0], [0, 1], [0, -1]].some(([dx, dy]) => !inside(x + dx, y + dy))) { row += 'K'; continue; }
      row += (y === 3 && x > 6 && x < 12) || (y === 6 && x > 17 && x < 22) ? 'W' : y < 4 ? 'L' : 'U';
    }
    rows.push(row);
  }
  return { pal: { K: '#3c78a8', U: '#5aa8e8', L: '#80c4f4', W: '#e0f4ff' }, rows };
})();
SPRITES.walker = {
  pal: { K: '#2a2430', G: '#48a848', W: '#ffffff', S: '#f4c8a0', Y: '#f8d030', N: '#384c88' },
  rows: ['..KKKKKK..', '.KGGGGGWK.', 'KGGGGGGGGK', 'KKKKKKKKKK', '.KSSSSSSK.', '.KSKSSKSK.', '.KSSSSSSK.', '..KKKKKK..',
    '.KYYYYYYK.', 'KSKYYYYKSK', 'KSKYYYYKSK', '.KKYYYYKK.', '..KNNNNK..', '..KNKKNK..', '..KK..KK..'],
};
// The games table's search box: a magnifying glass.
SPRITES.search = {
  pal: { K: INK, W: '#d8f0ff', L: '#ffffff' },
  rows: ['..KKKK.....', '.KWWWWK....', 'KWLLWWWK...', 'KWLWWWWK...', 'KWWWWWWK...', 'KWWWWWWK...', '.KWWWWK....',
    '..KKKKKK...', '......KKK..', '.......KKK.', '........KK.'],
};
// The language picker's globe.
SPRITES.globe = {
  pal: { K: INK, B: '#58a0f0', G: '#58c058', W: '#d8f0ff' },
  rows: ['....KKKK....', '..KKBBGGKK..', '.KWBBGGGGBK.', '.KWGBBGGBBK.', 'KGGGGBBBBBBK', 'KGGGBBBBBBBK',
    'KBGBBBBGGBBK', 'KBBBBBGGGGBK', '.KBBBBGGGBK.', '.KBBBBBGGBK.', '..KKBBBBKK..', '....KKKK....'],
};
SPRITES.bird = { pal: { K: '#3a3a48' }, rows: ['K...K', '.K.K.', '..K..'] };
// The homepage's quality-of-life cards: a PC, the keep-away spray, grass with no encounters, a TM, running, zoom.
SPRITES.pc = {
  pal: { K: INK, S: '#909098', U: '#58a0f0', W: '#d8f0ff', R: '#e83028' },
  rows: ['KKKKKKKKKKKK', 'KSSSSSSSSSSK', 'KSUUUUUUUUSK', 'KSUWWUUUUUSK', 'KSUWUUUUUUSK', 'KSUUUUUUUUSK', 'KSSSSSSSSRSK', 'KKKKKKKKKKKK', '....KSSK....', '..KKKKKKKK..', '.KSSSSSSSSK.', '.KKKKKKKKKK.'],
};
SPRITES.spray = {
  pal: { K: INK, W: '#ffffff', P: '#9058d8', L: '#c8a8f8' },
  rows: ['....KKK.....', '...KWWK.K...', '...KKKK..K..', '..KPPPPK..K.', '..KPLPPK....', '..KPLPPK....', '..KPPPPK....', '..KWWWWK....', '..KPPPPK....', '..KPLPPK....', '..KPPPPK....', '..KKKKKK....'],
};
SPRITES.grassOff = {
  pal: { D: '#2f8a3a', G: '#4cb04c', L: '#78d068', R: '#e83028' },
  rows: ['RR........RR', '.RR......RR.', '.DRR.D..RR..', 'DGDRRGDRRGD.', 'DLGDRRRRGLGD', '.DGGGRRGGGD.', 'DGLGRRRRDGLD', 'DGGRRGGRRGGD', '.DRRGGGGRRD.', 'DRRLGGGGLRRD', 'RRGGGGGGGGRR', 'RDDDDDDDDDDR'],
};
SPRITES.tmDisc = {
  pal: { K: INK, B: '#58a0f0', L: '#d8f0ff', W: '#ffffff' },
  rows: ['....KKKK....', '..KKBBBBKK..', '.KBBLBBBBBK.', '.KBLBBBBBBK.', 'KBBBBKKBBBBK', 'KBBBKWWKBBBK', 'KBBBKWWKBBBK', 'KBBBBKKBBBBK', '.KBBBBBBBBK.', '.KBBBBBBBBK.', '..KKBBBBKK..', '....KKKK....'],
};
SPRITES.run = {
  pal: { K: INK, R: '#e83028', W: '#ffffff' },
  rows: ['.....KKKK...', '.....KRRK...', '.....KRRK...', 'K....KRRKK..', '.....KRRRRK.', 'KK..KRRRRRRK', '...KRRWRRRRK', 'K.KRRRWRRRRK', '.KWWWWWWWWWK', '.KKKKKKKKKKK'],
};
SPRITES.zoom = {
  pal: { K: INK, U: '#58a0f0', W: '#d8f0ff', S: '#909098' },
  rows: ['..KKKK......', '.KUUUUK.....', 'KUWUUUUK....', 'KUWUUUUK....', 'KUUUUUUK....', 'KUUUUUUK....', '.KUUUUK.....', '..KKKKKK....', '......KSK...', '.......KSK..', '........KSK.', '.........KK.'],
};
SPRITES.tallgrass = {
  pal: { D: '#2f8a3a', G: '#4cb04c', L: '#78d068' },
  rows: ['.D...D..', 'DGD.DGD.', 'DLGDGLGD', '.DGGGGD.', 'DGLGDGLD', 'DGGGGGGD', '.DGGGGD.', 'DDDDDDDD'],
};
SPRITES.stick = {
  pal: { K: '#14141c', C: '#7fe0ff', G: '#4a4a58', L: '#7a7a88', D: '#30303a' },
  rows: ['.....KKKKKK.....', '...KKCCCCCCKK...', '..KCCKKKKKKCCK..', '.KCKKGGGGGGKKCK.', '.KCKGLLGGGGGKCK.',
    'KCKGLLGGGGGGGKCK', 'KCKGGGGGGGGGGKCK', 'KCKGGGGGGGGGGKCK', 'KCKGGGGGGGGGGKCK', 'KCKGGGGGGGGGDKCK',
    '.KCKGGGGGGGDKCK.', '.KCKKGGGGDDKKCK.', '..KCCKKKKKKCCK..', '...KKCCCCCCKK...', '.....KKKKKK.....'],
};
SPRITES.abxy = {
  pal: { K: '#14141c', G: '#3c3c48', L: '#6a6a78', W: '#b8b8c8' },
  rows: ['.....KKKK.....', '....KLLGGK....', '....KLWWGK....', '....KGGGGK....', '.KKKK.KK.KKKK.', 'KLLGGK..KLLGGK',
    'KLWWGK..KLWWGK', 'KGGGGK..KGGGGK', '.KKKK.KK.KKKK.', '....KLLGGK....', '....KLWWGK....', '....KGGGGK....', '.....KKKK.....'],
};
SPRITES.dpad = {
  pal: { K: '#14141c', L: '#55556a', D: '#3a3a4a', H: '#6e6e84' },
  rows: ['...KKKK...', '...KHLK...', '...KLLK...', 'KKKKLLKKKK', 'KHLLDDLLLK', 'KLLLDDLLLK', 'KKKKLLKKKK',
    '...KLLK...', '...KLLK...', '...KKKK...'],
};

// The hero handhelds' controls are handheld.js's (thorDpad2 & co.).

// The app's STATUS BAR icons: the place pin and the battery.
SPRITES.pin = {
  pal: { K: '#8a1810', R: '#e83028', W: '#ffffff' },
  rows: ['..KK..', '.KRRK.', 'KRWWRK', 'KRWWRK', '.KRRK.', '.KRRK.', '..KK..'],
};
SPRITES.battery = {
  pal: { K: '#505058', G: '#48b848' },
  rows: ['KKKKKKKKKKK.', 'KGGGGGGGG.KK', 'KGGGGGGGG.KK', 'KGGGGGGGG.KK', 'KKKKKKKKKKK.'],
};

// The devices section's "coming soon" card: a dashed outline with a plus.
SPRITES.moreDevice = {
  pal: { K: '#9a98a8', G: '#e83028' },
  rows: ['.K.K.K.K.K.K.', 'K...........K', '.............', 'K.....G.....K', '......G......', 'K...GGGGG...K',
    '......G......', 'K.....G.....K', '.............', 'K...........K', '.K.K.K.K.K.K.'],
};

// The coffee cup and its steam apart, so the steam can drift.
SPRITES.steam = { pal: SPRITES.coffee.pal, rows: SPRITES.coffee.rows.slice(0, 4) };
SPRITES.cup = { pal: SPRITES.coffee.pal, rows: SPRITES.coffee.rows.slice(5) };

/**
 * The PokeDaisy mark: a daisy drawn as a Poké Ball - eight petals, red above the band and white
 * below it, a black band through the side petals, and a yellow heart with the ball's black button
 * ring in it. Drawn from polar shapes on a [size] grid, then outlined and shaded in whole pixels.
 */
function daisyBall(size) {
  const c = size / 2;
  const disc = size * 0.17;
  const dist = size * 0.33;
  const ra = size * 0.165;
  const rb = size * 0.105;
  const petals = Array.from({ length: 8 }, (_, i) => (i * Math.PI) / 4);
  // Which part a pixel is in: 'disc', a petal index, or null.
  const part = (x, y) => {
    const dx = x + 0.5 - c;
    const dy = y + 0.5 - c;
    if (Math.hypot(dx, dy) < disc + 1) return 'disc';
    // Leave the grid's outer ring free, so the petal tips get their outline too.
    if (x < 1 || y < 1 || x > size - 2 || y > size - 2) return null;
    let best = null;
    let bestV = 1;
    petals.forEach((t, i) => {
      const px = dx - Math.cos(t) * dist;
      const py = dy - Math.sin(t) * dist;
      const u = px * Math.cos(t) + py * Math.sin(t);
      const v = -px * Math.sin(t) + py * Math.cos(t);
      const val = (u / ra) ** 2 + (v / rb) ** 2;
      if (val < bestV) { bestV = val; best = i; }
    });
    return best;
  };
  const rows = [];
  for (let y = 0; y < size; y++) {
    let row = '';
    for (let x = 0; x < size; x++) {
      const dx = x + 0.5 - c;
      const dy = y + 0.5 - c;
      const r = Math.hypot(dx, dy);
      const p = part(x, y);
      const around = [[1, 0], [-1, 0], [0, 1], [0, -1]].map(([ox, oy]) => part(x + ox, y + oy));
      if (p === null) { row += around.some((q) => q !== null) ? 'K' : '.'; continue; }
      if (p === 'disc') {
        if (r > disc) row += 'K';
        else if (r < disc * 0.3) row += 'w';
        else if (r < disc * 0.6) row += 'K';
        else row += dx < -1 && dy < -1 ? 'y' : 'Y';
        continue;
      }
      // A petal meeting another petal (not the disc) gets a line between them.
      if (around.some((q) => q !== null && q !== 'disc' && q !== p && q > p)) { row += 'K'; continue; }
      // The ball's band: two rows at full size, one on the small mark (else it hides the side petals).
      if (Math.abs(dy) < (size >= 24 ? 1 : 0.5)) { row += 'K'; continue; }
      const top = dy < 0;
      const edgeBR = part(x + 1, y + 1) !== p || part(x + 1, y) !== p || part(x, y + 1) !== p;
      const edgeTL = part(x - 1, y - 1) !== p;
      row += top ? (edgeBR ? 'D' : edgeTL ? 'H' : 'R') : (edgeBR ? 'S' : 'W');
    }
    rows.push(row);
  }
  return {
    pal: { K: INK, R: '#e83028', D: '#b01c14', H: '#ff9080', W: '#ffffff', S: '#c4c8d4', Y: '#f8d030', y: '#fff0a0', w: '#fff8d8' },
    rows,
  };
}

SPRITES.logo = daisyBall(32);

/** A sprite as a CSS url() - for tiled backgrounds. */
// The invented creatures (creatures.js) draw by their id: <Pixel name="emberpup" />.
for (const [id, c] of Object.entries(CREATURES)) SPRITES[id] = { pal: c.pal, rows: c.rows };
Object.assign(SPRITES, HANDHELD_SPRITES);
SPRITES.regionMap = regionMapSprite();
SPRITES.sea = seaSprite();
// The MAP tab's player: the walker's head, as the region map marks where you are.
SPRITES.mapHead = { pal: SPRITES.walker.pal, rows: SPRITES.walker.rows.slice(0, 8) };

// The BAG tab's invented items (never a real item's name or art): tonics, eye drops, a bun, a ball of thread.
const bottle = (c, l) => ({
  pal: { K: INK, W: '#ffffff', C: c, L: l, G: '#c8d0d8' },
  rows: ['....KKKK....', '....KGGK....', '....KKKK....', '...KWWWWK...', '...KCCCCK...', '..KCLCCCCK..', '..KCLCCCCK..', '..KCCWWCCK..', '..KCCWWCCK..', '..KCCCCCCK..', '...KCCCCK...', '....KKKK....'],
});
SPRITES.tonic = bottle('#d84890', '#f8a8d0');
SPRITES.tonicSuper = bottle('#f08828', '#f8d090');
SPRITES.drops = {
  pal: { K: INK, W: '#ffffff', B: '#4898e8', L: '#a8d8ff' },
  rows: ['.....KK.....', '....KWWK....', '....KKKK....', '...KBBBBK...', '...KBLBBK...', '..KBBLBBBK..', '..KBLBBBBK..', '..KBBBBBBK..', '..KBBBBBBK..', '...KBBBBK...', '....KKKK....'],
};
SPRITES.bun = {
  pal: { K: '#5a3010', B: '#d89040', L: '#f8c878', W: '#fff4d8' },
  rows: ['............', '....KKKK....', '..KKLLLLKK..', '.KLLWWLLLLK.', '.KLWWLLLLBK.', 'KBLLLLLLLBBK', 'KBBBBBBBBBBK', 'KKBBBBBBBBKK', '.KKKKKKKKKK.'],
};
SPRITES.thread = {
  pal: { K: INK, R: '#e84838', L: '#f8a090', W: '#d8c8b0' },
  rows: ['....KKKK....', '..KKRRRRKK..', '.KRLRRLRRRK.', '.KRRLRRLRRK.', 'KRLRRLRRLRRK', 'KRRLRRLRRLRK', 'KRRRLRRLRRRK', '.KRRRLRRLRK.', '.KRRRRLRRRK.', '..KKRRRRKKW.', '....KKKK..W.'],
};
// The companion window's X (PixelIcons.panelClose's shape).
SPRITES.winClose = ink(['1100011', '1110111', '0111110', '0011100', '0111110', '1110111', '1100011'], '#505058');


export function spriteUrl(name) {
  return `url("data:image/svg+xml,${encodeURIComponent(spriteSvg(name).svg)}")`;
}

/** One sprite as an SVG string: each row's runs of one colour merged into a rect. */
export function spriteSvg(name, attrs = '') {
  const s = SPRITES[name];
  if (!s) throw new Error(`no sprite ${name}`);
  const w = Math.max(...s.rows.map((r) => r.length));
  const h = s.rows.length;
  let rects = '';
  s.rows.forEach((row, y) => {
    let x = 0;
    while (x < row.length) {
      const c = row[x];
      let end = x + 1;
      while (end < row.length && row[end] === c) end++;
      if (c !== '.' && s.pal[c]) rects += `<rect x="${x}" y="${y}" width="${end - x}" height="1" fill="${s.pal[c]}"/>`;
      x = end;
    }
  });
  return { w, h, svg: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}" shape-rendering="crispEdges" ${attrs}>${rects}</svg>` };
}

/**
 * A pixel window frame as a 9-slice SVG: [layers] of [colour, width] from the outside in, the
 * corners rounded in whole pixels (radius [r]), then [fill]. Used with border-image, sliced at
 * the returned [slice] cells.
 */
export function frameSvg(layers, r, fill) {
  const total = layers.reduce((n, [, w]) => n + w, 0);
  const slice = Math.max(total, r);
  const n = slice * 2 + 1;
  const colourAt = (depth) => {
    let d = depth;
    for (const [c, w] of layers) {
      if (d < w) return c;
      d -= w;
    }
    return fill;
  };
  let rects = '';
  for (let j = 0; j < n; j++) {
    for (let i = 0; i < n; i++) {
      const x = Math.min(i, n - 1 - i);
      const y = Math.min(j, n - 1 - j);
      let depth;
      if (x < r && y < r) {
        const d = r - Math.hypot(r - x - 0.5, r - y - 0.5);
        if (d < 0) continue;
        depth = Math.floor(d);
      } else {
        depth = Math.min(x, y);
      }
      const c = colourAt(depth);
      if (c !== 'none') rects += `<rect x="${i}" y="${j}" width="1" height="1" fill="${c}"/>`;
    }
  }
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${n} ${n}" width="${n}" height="${n}" shape-rendering="crispEdges">${rects}</svg>`;
  return { slice, url: `url("data:image/svg+xml,${encodeURIComponent(svg)}")` };
}

/** Every frame the site uses: modern ink-outlined cards and buttons, plus the game's own
 * OPTION list / title / dialog windows (the app's OptionColors, GbaMenu.kt) for the game UI. */
const INKF = '#1d1b26';
export const FRAMES = {
  card: frameSvg([[INKF, 1]], 3, '#ffffff'),
  btn: frameSvg([[INKF, 1]], 2, '#ffffff'),
  btnHot: frameSvg([[INKF, 1]], 2, '#fff6d0'),
  primary: frameSvg([[INKF, 1], ['#ff8a78', 1]], 2, '#e83028'),
  primaryHot: frameSvg([[INKF, 1], ['#ffb4a8', 1]], 2, '#f5402f'),
  coffee: frameSvg([[INKF, 1], ['#fff6b8', 1]], 2, '#ffdd00'),
  coffeeHot: frameSvg([[INKF, 1], ['#ffffff', 1]], 2, '#ffe94d'),
  kofi: frameSvg([[INKF, 1], ['#e2eeff', 1]], 2, '#a8ccff'),
  kofiHot: frameSvg([[INKF, 1], ['#ffffff', 1]], 2, '#c4ddff'),
  patreon: frameSvg([[INKF, 1], ['#ffe0dc', 1]], 2, '#ffaaa2'),
  patreonHot: frameSvg([[INKF, 1], ['#ffffff', 1]], 2, '#ffc4be'),
  reddit: frameSvg([[INKF, 1], ['#ffe2d2', 1]], 2, '#ffb48e'),
  redditHot: frameSvg([[INKF, 1], ['#ffffff', 1]], 2, '#ffcbb0'),
  discord: frameSvg([[INKF, 1], ['#e3e6ff', 1]], 2, '#aab2ff'),
  discordHot: frameSvg([[INKF, 1], ['#ffffff', 1]], 2, '#c6ccff'),
  chip: frameSvg([[INKF, 1]], 1, '#ffffff'),
  chipOn: frameSvg([[INKF, 1]], 1, INKF),
  pillGreen: frameSvg([['#2c7a3a', 1]], 1, '#dff6e2'),
  pillYellow: frameSvg([['#9a6a00', 1]], 1, '#fff1bf'),
  pillBlue: frameSvg([['#2d55b0', 1]], 1, '#e0e9ff'),
  pillRed: frameSvg([['#a82018', 1]], 1, '#ffe2dd'),
  pillGrey: frameSvg([['#77748a', 1]], 1, '#efeef4'),
  list: frameSvg([['#293131', 1], ['#8c8cce', 1], ['#736b84', 2], ['#ded6de', 1], ['#ffffff', 2]], 4, '#e0dfdf'),
  title: frameSvg([['#63737b', 2], ['#ced6d6', 1]], 3, '#ffffff'),
  dialog: frameSvg([['#283848', 1], ['#6880a0', 1], ['#c8d8e8', 1], ['#ffffff', 1]], 3, '#ffffff'),
  hp: frameSvg([['#293131', 1]], 1, '#506058'),
  // The app's companion: Platinum-style buttons (BattleControlsScreen.kt), white windows, tab chips.
  pbFight: frameSvg([['#202020', 1]], 3, '#e8483c'),
  pbBag: frameSvg([['#202020', 1]], 3, '#e0a030'),
  pbRun: frameSvg([['#202020', 1]], 3, '#4890d8'),
  pbMon: frameSvg([['#202020', 1]], 3, '#58a840'),
  pbInfo: frameSvg([['#202020', 1]], 3, '#9068d0'),
  pbSugg: frameSvg([['#202020', 1]], 3, '#e8b830'),
  pbMove: frameSvg([['#202020', 1]], 3, '#e8e8b0'),
  appWin: frameSvg([['#4a4a6a', 1], ['#8c8cce', 1], ['#d6d6e6', 1]], 2, '#ffffff'),
  appTab: frameSvg([['#6a7378', 1]], 2, '#e0e0e0'),
  appTabOn: frameSvg([['#6a7378', 1]], 2, '#ffffff'),
  iconBox: frameSvg([], 3, '#e4e4e4'),
  iconBoxOn: frameSvg([], 3, '#d8eef0'),
  shell: frameSvg([[INKF, 1], ['#55556a', 1]], 6, '#3b3b4c'),
  thorShell: frameSvg([['#0c0c10', 1], ['#4a4a58', 1], ['#2e2e38', 1]], 7, '#24242c'),
  shellLight: frameSvg([[INKF, 1], ['#ffffff', 1]], 6, '#dcdce6'),
  bezel: frameSvg([[INKF, 1]], 3, '#101016'),
  // The hero handhelds (handheld units, not page pixels): the Thor's black shell and its screens' glass,
  // the Retroid Pocket 5's light grey shell and glass.
  thorLid: frameSvg([['#050507', 1], ['#5a5a68', 1], ['#3a3a46', 1]], 7, '#30303a'),
  thorBase: frameSvg([['#050507', 1], ['#5a5a68', 1], ['#3a3a46', 1]], 7, '#2c2c35'),
  glass: frameSvg([['#000000', 1], ['#2a2a34', 1]], 3, '#0b0b0f'),
  rp5Shell: frameSvg([[INKF, 1], ['#f8f7fb', 1], ['#e2e1e8', 1]], 13, '#cecdd6'),
  rp5Glass: frameSvg([['#4a4858', 1], ['#000000', 1]], 4, '#0c0c11'),
  rp5Key: frameSvg([['#6e6c7c', 1]], 1, '#9896a6'),
  // The companion's map buttons (OptionButton, title frame) and FLY in the field moves' blue (OptionColors.fieldMove).
  appBtn: frameSvg([['#63737b', 2], ['#ced6d6', 1]], 3, '#ffffff'),
  appBtnFly: frameSvg([['#3152cd', 2], ['#a4c5f6', 1]], 3, '#ffffff'),
  appKey: frameSvg([['#63737b', 1]], 2, '#ffffff'),
  // The companion window's frame over its edge (SidePanel.kt's WindowFrame: the title window's layers), see-through inside.
  winFrame: frameSvg([['#63737b', 2], ['#ced6d6', 1]], 4, 'none'),
  // FireRed's bag (ItemsScreen.kt's FireRedBag): the list window, its folder tabs, the blue description box.
  bagPanel: frameSvg([['#686868', 2], ['#e8e0a8', 1], ['#f0c870', 2], ['#d0b050', 2]], 4, '#f8f8c8'),
  bagTab: frameSvg([['#686868', 2], ['#e8e0a8', 1]], 3, '#d0b050'),
  bagTabOn: frameSvg([['#686868', 2], ['#e8e0a8', 1]], 3, '#f0c870'),
  bagDesc: frameSvg([['#005070', 2], ['#10a8d8', 1]], 3, '#0078c0'),
  bagIcon: frameSvg([['#b8d0d0', 1]], 2, '#f8f8f8'),
};

/** CSS for every frame: `.f-NAME` draws it (border-image), `.h-NAME` swaps to it on hover / focus. */
export function frameCss() {
  return Object.entries(FRAMES).map(([name, { slice, url }]) =>
    `.f-${name}{border-style:solid;border-color:transparent;border-width:calc(var(--px)*${slice});` +
    `border-image:${url} ${slice} fill/calc(var(--px)*${slice}) stretch}` +
    `.h-${name}:hover,.h-${name}:focus-visible{border-image-source:${url}}`).join('\n');
}
