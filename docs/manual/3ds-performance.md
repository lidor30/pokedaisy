---
title: Performance on an original Nintendo 3DS
summary: Choose a core, reduce companion work on an original 3DS or 2DS, and collect useful performance logs.
section: help
order: 85
keywords: [old3ds, old 3ds, original 3ds, 2ds, gpsp, jit, slow 3ds, frame rate, performance log]
---

On an original Nintendo 3DS or 2DS, the game and companion share a small amount of memory and processor time. If you are setting up the app for the first time, start with the [3DS installation guide](3ds.md).

## Choose a core and view

Select your game in the library, press **X** and try `GBA CORE: GPSP`. It is experimental. Keep the same game, save and view when comparing it with another core.

For a separate display comparison, try `SCREEN: PIXEL 1x` without EXPAND. It avoids the extra scaling pass used by the sharper enlarged views. You can switch back if you prefer the larger picture.

For a 32 MiB game, start the CIA from the HOME Menu. The memory available to a Homebrew Launcher app depends on how you started the launcher. A failure to reserve enough memory is reported before the game starts.

## Companion updates

On an original 3DS or 2DS, background companion data is read about three times per second. Its bottom image is repainted at most 30 times per second in the background. Touch, companion buttons and battle transitions can update it immediately.

The game's top screen keeps its normal presentation cycle. New models keep the previous companion update cadence. Nuzlocke tracking also keeps its earlier polling rate while enabled.

## gpSP display and memory

Ordinary gpSP views use its native RGB565 image. EXPAND and flight keep the existing display path. The recompiler uses a smaller code cache to leave more memory for the game and companion.

For a display comparison, create an empty file named `sdmc:/pokedaisy/nonative` and reopen the game. This disables native display. Remove the file and reopen the game to restore it.

## Read the performance log

After testing, close the game through `SETTINGS` > `CLOSE GAME`, then read `sdmc:/pokedaisy/log.txt`. The startup lines say whether JIT and the separate gpSP renderer started. A failed renderer retries other thread priorities before drawing with the game on its main thread.

| Log field | What it means |
| --- | --- |
| `emulated FPS` | How many game frames were run per second |
| `presented FPS` | How many screen frames were submitted per second |
| `worker running CPU 1` | The gpSP renderer is running on the original 3DS's second CPU core |
| `worker OFF` | gpSP is drawing with the emulation thread |
| `ROM/RAM-full flushes` | How often those code caches were cleared during the reporting period |

An emulated rate of 60 with 20 presented frames means the game advances at its normal speed while you see fewer new pictures. Full speed depends on the game and enabled features; this build does not guarantee 60 FPS on an original model.

Performance lines stay in memory while you play. A full buffer keeps its most recent complete lines instead of writing to the SD card during gameplay. Close the game before collecting the log. Include your core, view and whether the sample was taken in the field, battle or a menu when reporting a problem.
