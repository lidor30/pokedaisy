// The homepage's DOWNLOAD cards: every platform PokeDaisy ships on, each with its files and its install guide.
// A file is looked up by name in the newest release that has it (getReleases: published, not pre-releases), so a
// platform's files show up by themselves once a release carries them (docs/RELEASING.md's list); until then the row
// reads SOON. Windows and iOS are on the way: a card with no files.
import { msg } from '../i18n/index.js';

/**
 * [match]: the release file's name, exact or a test. [icon]: a pixel.js sprite. [label]: who the file is for.
 * [guide]: the manual page on installing it (/docs/<page>/); [localGuide]: that page comes in every site language.
 */
export const PLATFORMS = [
  {
    id: 'android', name: 'ANDROID', icon: 'android', guide: '/docs/install/',
    text: msg('Android 8.0 or newer: dual-screen handhelds, one-screen handhelds and phones.'),
    files: [
      { match: (n) => n.endsWith('.apk'), icon: 'android', label: msg('The app (APK)') },
    ],
  },
  {
    id: 'linux', name: 'LINUX', icon: 'tux', guide: '/docs/linux-install/', localGuide: true,
    text: msg('Steam Deck, Linux PCs and Linux handhelds, or the cores alone for RetroArch.'),
    files: [
      { match: 'pokedaisy-linux-x86_64.zip', icon: 'pc', label: msg('Steam Deck and Linux PCs') },
      { match: 'pokedaisy-linux-aarch64.zip', icon: 'handheld', label: msg('TrimUI handhelds (CrossMix, NextUI)') },
      { match: 'pokedaisy-portmaster-aarch64.zip', icon: 'handheld', label: msg('PortMaster handhelds (ROCKNIX, Knulli, muOS)') },
      { match: 'PokeDaisy-NXRedux.zip', icon: 'handheld', label: msg('TrimUI Brick on NXRedux') },
      { match: 'pokedaisy-libretro-x86_64.zip', icon: 'chip', label: msg('Cores for RetroArch, on a PC') },
      { match: 'pokedaisy-libretro-aarch64.zip', icon: 'chip', label: msg('Cores for RetroArch, on an ARM handheld') },
    ],
  },
  {
    id: '3ds', name: 'NINTENDO 3DS', icon: 'n3ds', guide: '/docs/3ds/', localGuide: true, preview: true,
    text: msg('A 3DS with custom firmware and the Homebrew Launcher. An early preview.'),
    files: [
      { match: 'pokedaisy.3dsx', icon: 'n3ds', label: msg('The app (.3dsx)') },
      { match: 'pokedaisy.cia', icon: 'n3ds', label: msg('The app for the HOME Menu (.cia)') },
    ],
  },
  { id: 'windows', name: 'WINDOWS', icon: 'windows', soon: true, files: [] },
  { id: 'ios', name: 'IOS', icon: 'iphone', soon: true, files: [] },
];

const matches = (file, name) => (typeof file.match === 'string' ? file.match === name : file.match(name));

/** [PLATFORMS] with each file's download from [releases] (newest first): { url, name, size, version } or null. */
export function downloadsFrom(releases) {
  return PLATFORMS.map((p) => ({
    ...p,
    files: p.files.map((f) => {
      for (const r of releases) {
        const a = (r.assets ?? []).find((x) => matches(f, x.name));
        if (a) return { ...f, url: a.url, file: a.name, size: a.size, version: r.version };
      }
      return { ...f, url: null, file: typeof f.match === 'string' ? f.match : null };
    }),
  }));
}

/** A file's size the way people read it: "21 MB", "640 KB". */
export function formatSize(bytes) {
  if (!bytes) return '';
  return bytes >= 1024 * 1024 ? `${Math.round(bytes / 1024 / 1024)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}
