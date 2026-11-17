# Old 3DS gpSP changes and validation

This change reduces repeated frontend work and makes gpSP's renderer and JIT
failures diagnosable. It is based on upstream `79bd00e` (1.6.1). Its smaller
EXPAND buffers, 8 MiB linear heap, memory reports, ROM hash checks and armGBA
borrowed-code arena are retained. The earlier local memory fixes are not
duplicated here. No release binaries, ROMs, BIOS images or user saves belong
in the PR.

## Behavior

- Only original 3DS/2DS models use 334 ms background companion polling and
  a 30 FPS background bottom-canvas paint cap. Input and battle transitions
  bypass the caps. New 3DS and desktop hosts retain the previous polling
  cadence. Enabled Nuzlocke tracking keeps its original ten-frame cadence.
- The main presentation loop and audio rate are not capped to 30 FPS.
- Ordinary gpSP frames use native RGB565 texture uploads. The 32-bit frame
  is still maintained for companion readers, screenshots and compositing.
  EXPAND and active/finishing flight retain the old upload path. The render
  job owns a copy in the existing staging buffer before emulation resumes.
- Canvas and overlay uploads visit 8x8 texture tiles together. Pixel tests
  compare their entire output, including unused padding, with the original
  conversions and layout.
- The 3DS build defaults to gpSP's supported `SMALL_TRANSLATION_CACHE` preset:
  2 MiB ROM plus 384 KiB RAM instead of 8 MiB plus 2.5 MiB. This reduces its
  contiguous allocation by 8.125 MiB. All core objects use the same definition.
  `-DPD_GPSP_SMALL_CACHE=OFF` restores the previous sizes for comparisons.
  This setting affects New 3DS builds too and needs performance validation there.
- Contiguous cartridge storage is acquired before `retro_init` and verified
  after loading, before hashing or exposing it to companion readers. Upstream's
  load-warning text is retained.
- Companion RAM writes notify gpSP's existing SMC mechanism before invalidating
  translations, rather than triggering its full-cache compatibility fallback.
  Both bytes of each halfword code tag are checked. Identical RAM/ROM writes
  are skipped. ROM changes still invalidate the ROM translations.
- Render-thread creation retries standard/background priorities on the same
  requested CPU. Failed allocation stages and actual `svcCreateThread` Results
  are logged, along with the CPU observed inside a successful worker.
- Held log overflow discards the oldest complete lines rather than forcing SD
  I/O during gameplay. Flushes still happen when the game closes or suspends.

## Earlier hardware evidence

The tester reported that mGBA and gpSP started working and performance improved
in earlier local builds based on 1.6.0. Their 32 MiB Unbound r3 log showed:

| Observation | Result |
| --- | --- |
| JIT | Enabled, 2048 KiB ROM / 384 KiB RAM cache |
| Frontend presentation thread | Started on CPU 1 with the 80% CPU-time request accepted |
| gpSP render worker | Failed to start; no failure stage or kernel Result was recorded |
| Emulated FPS | 32 to 52, often 47 to 50 in later samples |
| Presented FPS | 12 to 18 |
| Core + companion time | Usually 94 to 98% of the loop |
| Free heap after startup | Stable at 4443 KiB |

These results motivate the worker diagnostics; they do not prove its cause is
priority-related. Neither the local r4 retry/invalidation changes nor this
rebased branch have been verified on physical hardware. The newer upstream
memory layout may also change the outcome. No 60 FPS result is claimed.

## Automated and local checks

```sh
git submodule update --init third_party/mgba third_party/armgba third_party/gpsp
make -C 3ds test-old3ds
make -C 3ds 3ds
```

`test-old3ds` builds the host gpSP adapter and runs five CTest cases without
external ROMs:
poll timing/fast-forward/urgent updates; all 65,536 RGB565 colors and tiled
canvas/overlay/native uploads; 3DS thread-priority retries with stubbed system
calls; bounded deferred logging; and cartridge/worker integration. The 3DS
workflow runs them before cross-building.

The integration case generates a 32 MiB diagnostic cartridge in the build
directory from our own short ARM program and an address-dependent pattern.
No game data, Nintendo logo, external BIOS or ARM assembler is needed. It
checks the entire ROM's CRC, every 1 MiB boundary, executed RAM markers and
every output pixel in both video formats. It reopens the core without and
with a worker, including after an injected thread-creation failure on Linux.
The generated cartridge is removed after a successful run. The startup error
belongs to the current open, so an unthreaded reopen cannot report a previous
worker's failure.

An optional integration test uses a caller-provided ROM and a temporary save:

```sh
mkdir -p 3ds/build/old3ds-tests/run
3ds/build/old3ds-tests/rom_memory_test '/path/to/game.gba' \
  3ds/build/old3ds-tests/run/test.sav
```

It covers allocation refusal/retry, full-ROM CRC and block boundaries, repeated
and out-of-range RAM/ROM writes, threaded/unthreaded pixel equivalence,
injected thread-creation failure and close/reopen. Host builds use the
interpreter; these tests do not execute ARM11 translated code or the 3DS GPU.

## Hardware checks still needed before merging

- Original 3DS/2DS: confirm the worker starts on CPU 1, or capture its exact
  allocation stage / kernel Result. Compare emulated and presented FPS on
  the same ROM, save, view and field/battle/menu scenes for at least 30 seconds.
- Compare native display with an empty `sdmc:/pokedaisy/nonative` marker file;
  reopen the game between runs. Check colors, frame ownership and input latency.
- Exercise EXPAND, flight and its ending frame, view changes, screenshots,
  save states, cheats/ROM patches and companion writes with ARM11 JIT enabled.
- Check Nuzlocke tracking and touch/battle transition responsiveness with the
  throttled background display.
- Check repeated loading/closing, save persistence, HOME/lid suspend/resume,
  long sessions and log-buffer overflow on original and New models.
- New 3DS: compare the smaller code cache against `PD_GPSP_SMALL_CACHE=OFF`
  for cache flush frequency, frame pacing and companion memory headroom.
- Test GB/GBC and all three GBA cores; this PR does not claim to resolve the
  armGBA startup failure seen in the earlier local 32 MiB test.

After closing the game, collect `sdmc:/pokedaisy/log.txt`. `perf:` reports
emulated/presented rates and frontend work; `gpSP perf:` reports actual worker
CPU, last-frame render/wait/conversion times, period cache flushes and native
sound mixes. A `core 1` budget line alone does not prove gpSP's renderer started.
