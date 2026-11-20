captured: 2026-10-14 (headless, mgba_dump by hand)
rom: Pokemon Emerald Legacy v1.1.4 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Emerald Legacy (v1.1.4).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Emerald Legacy (v1.1.4).sav
boot script: native-capture/boot/default.txt, wait 60, then RAM pokes, then START, DOWN x3, A (the TRAINER CARD), wait 120, dump.
The pokes give every star Legacy's card counts (src/trainer_card.c CountPlayerTrainerStars) and more to draw:
GAME_STAT_ENTERED_HOF 1 and FIRST_HOF_PLAY_TIME 1:02:03 (SaveBlock1+0x1714, XOR the key), every POKéDEX owned / seen
bit (SaveBlock2 +0x28 / +0x5C, SaveBlock1's copies +0xAF0 / +0x3B34), the museum's five paintings (contestWinners
+0x3008, entries 8-12's species), the frontier symbols (flags 0x924-0x931), FLAG_CAUGHT_MEW (0x1CA), the eight badges
(0x8C7-0x8CE) and FLAG_SYS_POKEDEX_GET (0x8C1). The game drew its black five-star card: POKéDEX 210, time 0:10.
