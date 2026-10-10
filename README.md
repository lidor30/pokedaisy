<div align="center">

<img src="https://pokedaisy.com/og.png" alt="PokeDaisy: dual-screen GBA player for the AYN Thor, Gen 3 Pokémon and ROM hacks, free and open source" width="100%">

<h3>Play Gen 3 Pokémon on the top screen. See everything about your game on the bottom one.</h3>

<p>
  <a href="https://github.com/lidor30/pokedaisy/releases/latest"><img src="https://img.shields.io/github/v/release/lidor30/pokedaisy?label=release&color=2f81f7" alt="Latest PokeDaisy release"></a>
  <a href="https://github.com/lidor30/pokedaisy/releases"><img src="https://img.shields.io/github/downloads/lidor30/pokedaisy/total?label=downloads&color=5c8a3c" alt="Total downloads"></a>
</p>

<p>
  <strong><a href="https://github.com/lidor30/pokedaisy/releases/latest">Download</a></strong>
  · <strong><a href="#features">Features</a></strong>
  · <strong><a href="#supported-games">Supported games</a></strong>
  · <strong><a href="https://github.com/lidor30/pokedaisy/issues">Report a problem</a></strong>
  · <strong><a href="https://buymeacoffee.com/lidor30g">Buy me a coffee</a></strong>
</p>

</div>

PokeDaisy is a GBA player for dual-screen Android handhelds, made for Pokémon
FireRed, LeafGreen, Emerald, Ruby, Sapphire and popular ROM hacks. The game runs
on the top screen. The bottom screen is a live companion that reads your game
as you play: your party, your bag, the map, battle matchups, your Pokédex, and a
spoiler-free guide. Everything is drawn in the game's own menu style.

## Devices

| Device                        | Status                                       |
| ----------------------------- | -------------------------------------------- |
| AYN Thor                      | Tested, made for it                          |
| Retroid Pocket Duo / Duo Lite | Should work, not yet tested on real hardware |
| Anbernic RG DS                | Should work, not yet tested on real hardware |
| Retroid Pocket 6              | Single screen: tested                        |
| Android phones (portrait)     | Should work, checked in screenshots only     |

Any Android 8.0+ device runs the games.

<details>
<summary><strong>Single-screen devices and phones</strong></summary>

On a device with one screen the
companion is a panel beside the game: press BACK to slide it in over the game,
tap the padlock on its edge to lock it beside the game (the game moves over),
and drag that tab sideways to resize it. BACK closes an unlocked panel; hold
BACK to leave the game.

Hold a phone upright and the game sits across the top with the companion docked
under it. Drag the grip on the companion's top edge to pick its height, or tap the
grip to step through small, normal and full. Without a controller, the touch
controls fill the space between the game and the companion.

</details>

## Install

1. Download `PokeDaisy-<version>.apk` from the
   [latest release](https://github.com/lidor30/pokedaisy/releases/latest) on the device.
2. Open it. If Android asks, allow your browser or file manager to install apps.
3. Open PokeDaisy. A short setup helps you:
   - **Link your ROMs folder** (optional). Every supported game in it shows up in
     your library, played right where it is. New games are picked up every time
     you open the app.
   - **Choose where saves go.** Use PokeDaisy's own folder, or point it at
     another emulator's (like RetroArch's) to keep playing the same saves.
   - **Add cover art** (optional) with a free [SteamGridDB](https://www.steamgriddb.com) API key.

You can change all of this later in Settings.

**No games are included.** Use your own legally dumped ROMs.

<details>
<summary><strong>Updates</strong></summary>

PokeDaisy checks for a new version each time you open it and offers to install
it. You can also check from **Settings > VERSION**.

</details>

## Features

### The companion (bottom screen)

- **PARTY** - your team with HP, levels and status, in your game's own party
  screen. Tap a Pokémon for its full summary: stats, moves, EXP, and what it's
  weak or resistant to. STATS shows its exact IVs and EVs, nature and Hidden Power.
- **BAG** - every pocket, with item icons and descriptions.
- **BATTLE** - opens by itself when a battle starts.
  - **INFO**: both Pokémon, your moves rated SUPER / RESISTED / NEUTRAL / IMMUNE,
    and the foe's weaknesses.
  - **SUGGESTIONS**: the best Pokémon and move for this fight.
  - **FOE TEAM**: the trainer's remaining Pokémon.
  - **STATS**: the foe's IVs, EVs and nature next to yours (off by default: turn on FOE IVS in Settings).
  - Touch buttons to fight, switch or run without reaching for the controls (FireRed and Emerald).
- **MAP** - the game's region map with you on it. Tap any place to name it,
  or search the list of every town and route.
- **DEX** - your Pokédex with seen / caught marks and full entries: sprite,
  types, stats, abilities, dex text, plus how it evolves (and from what, the
  whole family, method by method), where it's caught in the wild (by route and
  method, with levels and odds) and the moves it learns (level-up, and TMs /
  HMs where the game's tables are read), all from the game's own data.
- **GUIDE** - hints first, answers on a second tap:
  - **HERE**: wild Pokémon, items, gifts and trades in the area you're in, with what you already have ticked off.
  - **NEXT BOSS**: the next gym leader's team.
  - **EVOLUTIONS**, plus **TIPS**, **WHERE IS** and **STUCK?** pages.
- **CARD** - your trainer card, drawn exactly like the game's, front and back.
- **STATES** - 10 save-state slots with screenshots.

Pick which tabs sit in the tab bar in the companion's Settings.

<details>
<summary><strong>Languages</strong></summary>

The app's own text comes in English, Japanese, French, German, Italian and Spanish. By default it
follows the ROM's language (the device's in the Library); pick another under LANGUAGE in either
Settings screen. Pokémon, move and item names and the GUIDE pages stay as the game has them
(a translated hack's in its language; Quetzal's in the ones its own IDIOMA options pick).

</details>

<details>
<summary><strong>The player (top screen)</strong></summary>

- **Fast-forward** with a speed cap, plus slow motion. **Smart** fast-forward
  drops to normal speed in menus and on the map, and keeps battles fast.
- **Fast-forward music** (alpha) that keeps playing the song at its normal speed, instead of chipmunk sound.
- **Save states** on hotkeys, with undo.
- **Rewind** (optional, Settings > REWIND): hold the REWIND HOLD hotkey (R on a keyboard; pick a pad button in
  HOTKEYS) to play the last ~20 seconds backwards. It never touches your save file.
- **Status bar** (optional): game, location, money, clock and battery above the game.
- **Aspect**: the GBA's own 3:2, or stretched to fill a 16:9 top screen (Settings shows a preview of both).
- **Shaders** (optional): an LCD grid (plain or on paper), scanlines or a CRT look, and the colours as the GBA's own screen
  showed them - on the game and, if you like, the companion screen too.
- **Overlays** (optional, Settings > OVERLAY): a frame around the game - two of PokeDaisy's own, or your
  own RetroArch overlays (a `.cfg` with its PNG, a `.zip` of them, or any PNG with a see-through screen).
  None come bundled: bring the ones you already use. Pick them from either screen.
- **Controls**: remap every GBA button and hotkey, or turn hotkeys off. X and Y
  are a second START and SELECT, like the menu and registered-item buttons in the
  DS games. **TURBO A** and **TURBO B** can go on any button (GAME BUTTONS): held, they
  press A / B over and over, in game time, so they keep up with fast-forward.
  On-screen touch controls appear when no controller is connected, and hide once
  a controller is used. Bluetooth and USB controllers (8BitDo, GameSir, Xbox, ...)
  connect while a game is running without restarting it, and HOME / guide works
  like a BACK tap for the companion.
- **Settings search**: type what you're after ("key bindings", "rebind", "turbo", "box art") and the matching
  settings show, whatever PokéDaisy calls them. First-time setup also walks through the game buttons.
- **Themes**: PokéDaisy (the default, the website's light backdrop with floating logos) or one of eight game-coloured
  ones (FireRed, LeafGreen, Emerald, ...) for the library and settings, in Settings > THEME.

</details>

<details>
<summary><strong>Library</strong></summary>

- List or grid view, recently played, and cover art from SteamGridDB or RetroAchievements box art (or pick your own image).
- ROMs can be plain `.gba` files or zipped (`.zip` / `.7z`), in your ROMs folder or imported with **+**.
- **Refresh** rescans your ROMs folder and tells you what it found.
- Long-press a game to:
  - **Rename** or **hide** it.
  - **Load a save** file into it (your current save is kept as a dated backup).
  - See its **info**: ROM, save file and save states.
  - Give it its own **save folder** (another emulator's, so both keep playing the same save).
- Games the companion can't read yet are tagged **NOT SUPPORTED**. Their INFO, and the companion's own notice,
  have an **ASK FOR SUPPORT** button that opens a GitHub issue with the ROM's file name and SHA-1 (never the ROM).
- **Best effort** for those: on the companion's NOT SUPPORTED page, **TRY BEST EFFORT** (in game, with a Pokémon)
  reads the game as each one PokéDaisy knows and keeps the one that fits. A different build of a supported
  version is then just supported; a hack that shares a supported game's memory gets party, bag, map and battles
  (tagged **PARTIALLY SUPPORTED**, with whatever didn't match - Pokédex, guide, item text - switched off). It's
  remembered for that ROM; INFO > FORGET MATCH undoes it. Unsupported ROMs of a Gen 3 Pokémon game in your ROMs
  folder are listed (tagged) so you can try it.
- Works with frontends like **ES-DE**, **Cocoon** and **iiSU**. They can launch
  a game straight into PokeDaisy. Setup guides for [iiSU](docs/iisu/README.md) and
  [ES-DE](docs/es-de/README.md); the
  [technical details](docs/DEVELOPMENT.md#launch-from-a-frontend-cocoon-iisu-es-de-)
  are for other frontends.

</details>

<details>
<summary><strong>Saves</strong></summary>

Saves are standard `.sav` / `.srm` files, the same format as mGBA and RetroArch,
so you can move them between emulators freely. A game finds its save by the ROM's
file name (for a zipped game, the name of the ROM inside the archive, as in RetroArch).

To share saves with RetroArch, add its save folders under Settings > FOLDERS > **ALSO LOOK IN**
(e.g. `RetroArch/saves/mGBA` and `RetroArch/saves/gpSP`): a save found there is played and written
right there, so RetroArch keeps seeing it. New saves go to PokeDaisy's saves folder. A single game
can also have its own folder (its library menu > SAVE FOLDER). Only the folders themselves are
searched, never their subfolders, so a synced folder's version history (Syncthing's `.stversions`)
is left alone.

</details>

## Supported games

Any GBA game plays. The companion needs one of the games below, **in the exact
version listed** (ROM hacks are recognized by their file, so another version
shows a "not supported" notice).

✅ works · ◐ partly · — not yet

| Game                   | Version                 | Party | Bag | Battle | Map | Pokédex | Guide |
| ---------------------- | ----------------------- | :---: | :-: | :----: | :-: | :-----: | :---: |
| Pokémon FireRed        | USA/Europe, rev 0 and 1 |  ✅   | ✅  |   ✅   | ✅  |   ✅    |  ✅   |
| Pokémon FireRed        | ES / DE / FR / IT / JP  |  ✅   | ✅  |   ✅   | ✅  |   ✅    |  ✅   |
| Pokémon LeafGreen      | USA/Europe, rev 0 and 1 |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon LeafGreen      | ES / FR / IT / JP       |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Emerald        | USA/Europe              |  ✅   | ✅  |   ✅   | ✅  |   ✅    |  ✅   |
| Pokémon Emerald        | ES / DE / FR / IT / JP  |  ✅   | ✅  |   ✅   | ✅  |   ✅    |  ✅   |
| Pokémon Ruby           | USA, rev 0, 1 and 2     |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Ruby           | ES/DE/FR/IT/JP, rev 0-1 |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Sapphire       | USA, rev 0, 1 and 2     |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Sapphire       | ES/DE/FR/IT/JP, rev 0-1 |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Unbound        | 2.1.1.1                 |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Unbound        | 2.1.1.1 French          |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Radical Red    | 4.1                     |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Gaia           | 3.2                     |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Odyssey        | 4.1.1                   |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Heart and Soul | 2.0.6                   |  ✅   | ✅  |   ✅   | ✅  |   ✅    |  ✅   |
| Pokémon Amethyst       | 1.3.0, 1.4.1            |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Celia's Stupid Romhack | 1.1.4                   |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Orange Islands | Beta 5.7, an older beta |  ✅   | ✅  |   ◐    | ✅  |   ✅    |  ✅   |
| Pokémon Lazarus        | 2.0                     |  ✅   | ✅  |   ◐    | ✅  |   ✅    |   ◐   |
| Emerald Seaglass       | 3.0                     |  ✅   | ✅  |   ◐    | ✅  |   ✅    |   ◐   |
| Too Many Types 2       | 1.5.2                   |  ✅   | ✅  |   ◐    | ✅  |   ✅    |   ◐   |
| Pokémon Glazed         | 9.2.0                   |  ✅   | ✅  |   ◐    | ✅  |   ✅    |   ◐   |
| Emerald Imperium       | 1.3.1                   |  ✅   | ✅  |   ◐    | ✅  |   ✅    |   ◐   |
| Pokémon Quetzal        | English Alpha 9 v0      |  ✅   | ✅  |   ◐    | ✅  |   ✅    |   ◐   |
| Pokémon Quetzal        | Spanish Alpha 9 v0      |  ✅   | ✅  |   ◐    | ✅  |   ✅    |   ◐   |
| Emerald Rogue          | 2.2.1-EX                |  ✅   | ✅  |   ✅   | ✅  |   ✅    |   —   |
| Pokémon SoulGold       | 1.1.4, 1.2 (two builds) |  ✅   | ✅  |   ◐    | ✅  |   ✅    |   ◐   |
| Pokémon R.O.W.E.       | 2.1.9.1 Experimental    |  ✅   | ✅  |   ◐    | ✅  |   ◐    |   —   |
| Pokémon Yellow (GB)    | USA/Europe              |  ✅   | ✅  |   ◐    | ✅  |   ✅    |   —   |

<details>
<summary><strong>Notes on the table</strong></summary>

Game Boy / Color games play too; Pokémon Yellow is the first one the companion reads,
in the game's own look (its font, icons and town map, rebuilt from your ROM).
Map ◐ means place names
only, without the map picture. Guide ◐ means the live pages only (the wild Pokémon here and the next
boss), without the area lists. Battle ◐ means the battle panes work but haven't
been checked in every kind of battle. The guides were
written from each game's own data and may contain mistakes; the app says so the
first time you open one.

</details>

Want another game supported? [Open an issue](https://github.com/lidor30/pokedaisy/issues).

## Privacy

PokeDaisy has no accounts, ads or analytics. It only goes online to:

- check this page for a new version, and download it if you say so;
- fetch cover art from SteamGridDB or RetroAchievements, only if you add your own API key;
- RetroAchievements, only if you sign in;
- share a BEST EFFORT match, only if you tap SHARE when asked. The window lists every value sent,
  and that's all of it: the ROM's SHA-1, size, game code and revision, the game it was read as and how
  well, and the app version - no file name, account or device details. It goes to PokéDaisy's own
  Firebase database, where nobody can read it back through the API.

Your games and saves never leave your device.

<details>
<summary><strong>AI tooling note</strong></summary>

This project may use AI-assisted development tools, such as Claude Code, to help with code generation, refactoring, and documentation. All changes are still reviewed by the maintainer and validated with the project's existing checks before release.

</details>

## Building from source

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Support PokeDaisy

PokeDaisy is free, with no ads and nothing locked. If it made a playthrough better and
you'd like to say thanks, you can buy me a coffee. It's completely optional and doesn't
unlock anything; it just helps cover the time that goes into mapping new games and ROM hacks.
It pays for PokeDaisy's own code only: no games, ROMs or game content are sold or handed out.

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/lidor30g)

Bug reports, game requests and a star on the repo help just as much.

## Credits

<details>
<summary>mGBA, rcheevos, fonts, the pret decomps, cover art sources</summary>

- [mGBA](https://mgba.io) by Vicki Pfau (endrift) and contributors runs the games (MPL 2.0;
  source in [`third_party/mgba`](https://github.com/mgba-emu/mgba)), with its bundled blip_buf (LGPL 2.1) and inih (BSD).
- [rcheevos](https://github.com/RetroAchievements/rcheevos) by RetroAchievements.org (MIT) for achievements.
- [Pixel Operator](https://www.dafont.com/pixel-operator.font) font by Jayvee Enaguas (CC0).
- [PixelMplus](https://github.com/itouhiro/PixelMplus) (M+ FONT LICENSE, M+ FONTS PROJECT) for Japanese text, converted to Pixel Operator's pixel grid by `scripts/gen_jp_font.py`.
- The [pret](https://github.com/pret) decompilation projects and [pokeemerald-expansion](https://github.com/rh-hideout/pokeemerald-expansion), which the game data and guides were checked against.
- [PokeAPI](https://pokeapi.co) (BSD 3-Clause) for the National Dex species list.
- AndroidX, Jetpack Compose, Kotlin and Apache Commons Compress (Apache 2.0), XZ for Java (0BSD).

Every license is in [NOTICE](NOTICE) and, in the app, under Settings > LICENSES.
- Cover art from [SteamGridDB](https://www.steamgriddb.com) and its contributors, and box art from [RetroAchievements](https://retroachievements.org).

</details>

## License

PokeDaisy is free software under the [GNU GPL v3](LICENSE), with one
[additional term](NOTICE): if you share PokeDaisy or anything built from it,
keep the credit **"Based on PokeDaisy by Lidor Itzhari -
https://github.com/lidor30/pokedaisy"** in its documentation and credits.
Forks must stay open source under the same license.

PokeDaisy is a fan project, not affiliated with or endorsed by Nintendo,
Game Freak, Creatures or The Pokémon Company. Pokémon and all related names are
trademarks of their respective owners; Game Boy, Game Boy Color and Game Boy Advance
are trademarks of Nintendo. Device names (AYN Thor, Retroid, Anbernic) belong to their
makers, and RetroAchievements and SteamGridDB are independent services; none of them
endorse PokeDaisy. ROM hacks belong to their creators. No games, BIOS or game art are
included: use your own legally dumped ROMs.
