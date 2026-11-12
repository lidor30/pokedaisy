# dsp_dump: headless DSperate, the app's way

`nds_dump`'s twin for the app's second DS core (docs/DEVELOPMENT.md's "DSperate, the second DS core"). It builds DSperate's
core as the app does (`app/src/main/cpp/nds/dsperate.cmake`) and drives the app's own wrapper (`nds/pk_nds_dsp.cpp`, its
`pk_ndsd_*` calls) with nds_dump's script commands, so one script runs on both cores and their outputs compare. DSperate's
recompilers and fast memory are Linux / Android only: build it in an arm64 Linux container (or on an arm64 Linux box).

```bash
docker run --rm -v "$PWD":/src -w /src debian:bookworm bash -c '
  apt-get update && apt-get install -y cmake ninja-build g++ zlib1g-dev &&
  cmake -S native-capture/dsp_dump -B /tmp/b -G Ninja && cmake --build /tmp/b && /tmp/b/dsp_dump ROM.nds SAVE.sav < script.txt'
```

Debian 12's clang 14 can't build DSperate's AES instructions (`vaeseq_u8` undeclared): use its GCC there (the NDK's clang is
fine). A checkout in a git worktree needs the main repository mounted at its own path for nds_dump (its melonDS patch is
applied with git).

Like nds_dump it **writes every save the game makes back to `SAVE.sav`**: give it a copy.

## Script commands

As nds_dump's: `wait N`, `keys K`, `press K`, `hold K N`, `touch X Y [N]`, `shot FILE` (both screens, 256x384 PNG),
`dump DIR` (`mainram.bin`, `dtcm.bin`), `savestate FILE` / `loadstate FILE` (the app's DSperate states), `fieldinfo`,
`trace N`, `peek32 ADDR`, `ards W W ...` (one Action Replay cheat; `ards` alone takes it out), `bench N`. Its own:

| Command | What it does |
| --- | --- |
| `matches FILE` | `pk_nds_state_matches_save` on FILE: 1 the save matches, 0 it doesn't, -1 can't tell, 2 another core's state. |
| `flush` | Writes a pending save now (1 if one was pending). |
| `finishsave N` | Runs up to N frames until the game's save writes are done. |
| `skip 1` / `skip 0` | Frames not drawn (fast-forward's) from here on / drawn again. |

`bench N` prints wall and process CPU time per frame and the audio frames made a frame (48 kHz: ~802). nds_dump's `bench`
prints process CPU too, so `threaded 0` + `arm9shift 2` + `--jit` there (melonDS as the app runs it) compares with this.
