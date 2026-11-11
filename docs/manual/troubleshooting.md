---
title: Troubleshooting and FAQ
summary: Answers to the questions people ask most, from missing games and lost saves to silent music, achievements and what the app sends online.
section: help
order: 3
keywords: [help, faq, problem, issue, bug, broken, fix, not working, missing, privacy, data, network, internet, offline, contact, report, discord, community, chat, support]
---

Something not working the way you expect? Start here. If your question isn't answered, see [the last section](#how-do-i-report-a-bug-or-ask-for-a-game) for how to reach us.

## A game doesn't show up in my library

Work through these in order:

- **Is the folder linked?** Tap `+` > `MANAGE ROMS FOLDERS` in the library (or open Settings > `FOLDERS` > `ROMS FOLDERS`) and check the game's folder is in the list, or one it sits in. If not, tap `ADD FOLDER`. Picking a folder needs Android's "All files access" for PokeDaisy; if Android asks, allow it and tap the button again. Only folders on the device's own storage or SD card work, not cloud folders.
- **Did it rescan?** The library looks for new games every time you open it. Tap the Refresh button (the circular arrow at the top of the library) to scan now; it tells you what it found.
- **Is it deep in subfolders?** The scan looks a few folders down, not forever. Move very deeply nested games up a level or two.
- **Is it a file PokeDaisy reads?** `.gba`, `.gb`, `.gbc` and `.nds` files, or one of those inside a `.zip` or `.7z`. Other archives and disc images aren't games it can play.
- **Is it a game the companion supports?** The linked folders only list Pokémon games: supported ones, plus unknown ones built on FireRed, LeafGreen, Emerald, Ruby or Sapphire (tagged `NOT SUPPORTED`, so you can still try them). Any other GBA or Game Boy game can be added with the **+** button in the library; PokeDaisy asks before adding a game the companion can't read.
- **Did you hide it?** Games you hid with `HIDE` stay out of the library. Open Settings > `HIDDEN GAMES` and tap `SHOW` next to it.

See [Library](library.md) for more on folders and importing.

## The companion says NOT SUPPORTED

The game plays fine, but the companion doesn't recognise the file. Usually that means it's a version of a game or hack that isn't on the list, or a ROM that's been modified. Run the file through the [ROM check](https://pokedaisy.com/compatibility/) to see why (the ROM stays on your device).

If it's built on one of the original GBA games, PokeDaisy may still read it by best effort, or ask you **WHICH GAME IS THIS?** If you'd like it supported properly, tap `ASK FOR SUPPORT`. All of this is explained in [Supported games](supported-games.md).

## The companion keeps showing LOADING

Right after a game starts, the companion has to work out which game it is and find its data. For the original games that's almost instant; a big hack can take a few seconds while its file is checked. When you resume a game, the companion shows your party and bag as you left them and swaps in the live data a moment later. The `DEX`, `GUIDE` and `CARD` tabs read the game's own tables, so they're the last to fill in.

If it stays on LOADING:

- Get past the title screen and into your save. Some games have nothing to read until then.
- Give it a moment longer. If something's wrong, a short message appears under LOADING after a while saying what it's waiting for.
- Check the game is supported (see above). A game the companion can't read shows NOT SUPPORTED, not LOADING, once it's sure.

## My saves didn't carry over after updating from an old version

Early versions of PokeDaisy had a different app id (the name Android knows an app by). It changed twice, from `com.pokedaisey.PokeDaiseyApp` to `com.pokedaisey.app` and then to `com.pokedaisy.app`, when the "Daisey" misspelling was fixed. To Android each of those is a different app, so a version with a new id starts empty, and your old games, saves, states and settings stay where the old one left them.

They're not lost. To bring them over:

1. Connect the device to a computer over USB (on Android 11 and newer, most file manager apps can't open these folders).
2. Find `Android/data/com.pokedaisey.app/files/` (or `Android/data/com.pokedaisey.PokeDaiseyApp/files/`) on the device's storage.
3. Copy its `roms` and `saves` folders into `Android/data/com.pokedaisy.app/files/`, the new app's folder (open PokeDaisy once first, so the folder exists).
4. Open PokeDaisy. Your games show up with their saves.

If you kept your games in a linked ROMs folder and your saves in a folder of your own (like RetroArch's), there's nothing to copy: link the same folders again in Settings > `FOLDERS`.

Once everything's there, you can uninstall the old app.

## My game didn't resume where I left it

When a game opens, PokeDaisy normally puts you back exactly where you were. Before it does, it checks that your save file hasn't changed since that point. If it has (you played the same save in RetroArch, moved your saves folder, or copied a save in), resuming would put the old save back over the newer one. So PokeDaisy sets the old resume point aside and starts the game from your save file instead. Your newest save wins.

Two more reasons a game might start from its save:

- `RESUME GAMES` is off in Settings (in the `LIBRARY` group).
- You used `LOAD SAVE` in the game's library menu, which always starts from the save you picked.

> [!TIP]
> Every time a game starts, PokeDaisy copies its save first and keeps the last ten copies. If a save ever goes wrong, long-press the game and use `RESTORE BACKUP`. See [Saves](saves.md).

## The music goes silent when I fast-forward

That's `FF MUSIC` set to `STEADY` (the default) getting ready. Instead of speeding the music up into a squeak, `STEADY` plays a clean recording of the song at its normal tempo while the game races ahead. Those recordings are made from your ROM, one song at a time, the first time you play a game, and until a song's recording is ready it stays quiet while you fast-forward.

Play for a while and it fills in. If you'd rather always hear something, set `FF MUSIC` to `SPED-UP` (the game's own sound, faster) in either Settings screen. See [Fast-forward](fast-forward.md).

## The game feels choppy or the device runs hot

Turn on `FPS / CPU` (Settings, `SCREEN` group) to see what's going on. At normal speed it should read about 60. Tap the readout for more detail, including late frames, audio underruns, battery temperature and whether the device is throttling.

If it's below 60 or stutters:

- Set `SMOOTHING` to `OFF` and `FILTER` to `NONE` under `SHADERS`, then add them back one at a time.
- The first time you play a game with `FF MUSIC` on `STEADY`, PokeDaisy records its music in the background, which keeps a second processor core busy for a while. It's capped, but on a small device you may notice the heat. `SPED-UP` or `OFF` skips it.
- Close other apps running in the background, and check the device isn't in a battery saver mode.

If it reads 60 but movement still looks uneven, look at the `SCREEN` line in the panel. `VSYNC` means each frame is timed to your screen's refresh, which is the smoothest. `TIMER` means a clock times them instead, which can look a little less even:

- `TIMER (AUDIO)`: the sound kept running out, so PokeDaisy switched to the clock to keep it playing. It tries the screen's timing again after a minute, then waits longer each time it has to switch back.
- `TIMER (SCREEN)`: your screen's refresh didn't come at a steady pace, as on some devices. PokeDaisy goes back to the screen's timing once it does.

If it stays on `TIMER`, tell us your device on the [feedback page](https://pokedaisy.com/feedback/) along with what the line says.

If the picture runs fine but your buttons react late, for example nothing happens for a moment after `FIGHT`, set `LIGHT COMPANION` to `ON` (Settings, `SCREEN` group, `DISPLAY OPTIONS`). See [Light companion](screen.md#light-companion-for-slow-devices).

## Achievements won't unlock

Check these:

- **Are you signed in?** Sign in on the top screen: Settings > `RetroAchievements`. The companion can't sign you in (the bottom screen has no keyboard).
- **Does your ROM have a set?** Achievements only work for the exact ROM the RetroAchievements site knows. Long-press the game in the library and open `INFO`: its RetroAchievements section says whether there's a set for that file. Most ROM hacks don't have one.
- **Is a cheat on?** While any cheat is loaded, achievements pause. Turn cheats off and they carry on. See [Cheats](cheats.md).
- **Softcore only.** PokeDaisy earns achievements in softcore mode. Hardcore isn't available in PokeDaisy.
- **Offline?** Without a connection, PokeDaisy keeps trying in the background.

More in [Achievements](achievements.md).

## The app won't update

When PokeDaisy finds a new version, it downloads it and opens Android's installer. If that doesn't work:

- **Allow installing apps.** The first time, Android asks you to let PokeDaisy install apps ("Install unknown apps"). Allow it, then go back and tap the update again. A download that already finished is reused, so it won't download again.
- **Same signer, newer version.** Android only installs an update over the app you have if both come from the same place. If you built PokeDaisy yourself or installed a copy from somewhere else, the official update won't go over it. You'd have to uninstall first, and uninstalling deletes everything in PokeDaisy's own folder, so copy your `saves` (and `roms`, if you imported games) out of `Android/data/com.pokedaisy.app/files/` before you do.

You can always get the newest version by hand from the [releases page](https://github.com/lidor30/pokedaisy/releases/latest), and check for one any time with Settings > `VERSION`.

## The companion is on the wrong screen

On a device whose main display is the bottom one, the game ends up on the bottom and the companion on top. Turn on `SWAP SCREENS` (Settings, `SCREEN` group, on either screen) and they trade places. See [Devices](devices.md).

If the companion has disappeared from its screen entirely, you may have tapped `CLOSE COMPANION`. Tap the game, then press BACK to bring it back.

## What does PokeDaisy send over the internet?

PokeDaisy has no accounts of its own, no ads and no tracking. Your games and saves never leave your device. It only goes online for these:

- **Update check.** It asks GitHub for the newest release when you open the library (and when you tap `VERSION`), and downloads it only if you say so.
- **The manual.** `HELP` loads this manual from the PokeDaisy website and keeps a copy for reading offline.
- **Cover art**, only if you add your own key under Settings > `COVER ART`: from SteamGridDB and RetroAchievements. To find a game's art, RetroAchievements is asked by the ROM's fingerprint (its MD5, or for a DS game the fingerprint RetroAchievements uses for DS carts), never the file.
- **RetroAchievements**, only if you sign in. Opening a game's `INFO` in the library also asks RetroAchievements whether there's an achievement set for that ROM, by its fingerprint.
- **Crash reports**, only if you turn `CRASH REPORTS` on (it's off until you do; the app asks once, after a few days). A report holds the device model, Android version and where the app crashed.
- **Sharing a best effort match**, only if you tap `SHARE` when asked. The window lists every value sent: the ROM's SHA-1, size, game code and revision, the game it was read as and how well, and the app version. No file name, account or device details.

The play time the library uses to ask for support once ([Supporting PokeDaisy](library.md#supporting-pokedaisy)) never leaves your device either.

`ASK FOR SUPPORT` doesn't send anything by itself: it opens the game request form in your browser, which you can read before you send it.

## How do I report a bug or ask for a game?

- **Want help, or not sure it's a bug?** Ask on [PokeDaisy's Discord](https://discord.gg/vcmyKZZV5q) (Settings > `DISCORD` opens it). It's also the place for ideas and a chat.
- **No GitHub account?** Use the [feedback page](https://pokedaisy.com/feedback/) for bug reports, game requests and ideas.
- **On GitHub:** report a bug in [Issues](https://github.com/lidor30/pokedaisy/issues). Ask for a game in [Game requests](https://github.com/lidor30/pokedaisy/discussions/categories/game-requests) and share an idea in [Ideas](https://github.com/lidor30/pokedaisy/discussions/categories/ideas), where you can also upvote other people's.
- **For a game the companion doesn't read,** `ASK FOR SUPPORT` on the companion's NOT SUPPORTED page (or in the game's library `INFO`) fills in the details we need.

For a bug, it helps a lot to say which device you have, which game and version, what you did and what happened instead. Never attach a ROM: a file name and the SHA-1 from the game's `INFO` are enough.
