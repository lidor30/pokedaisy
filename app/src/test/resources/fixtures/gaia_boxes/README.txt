captured: 2026-11-03 (headless mgba_dump, by hand)
rom: Pokemon Gaia v3.2 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Gaia (v3.2).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Gaia (v3.2).sav
boot: native-capture/boot/default.txt (the field), wait 60, then the game's own SendMonToPC (FireRed rev 0's 0x08040B90, CFRU's hook)
  called on the party's lead (0x02024284), the current box (0x02029314) set to 0, 13 before each
  and back to 0 after: the same lead in box 1 and 14's first slot (each call returned 1), at 0x02029318 and 0x02030CF8: vanilla's
  14 boxes of 80-byte records, each the party struct's first 80 bytes byte for byte (plaintext, like its party).
  Box 20 doesn't exist there: a call with the current box at 19 never returned.
  wait 5, dumped. The save had nothing boxed before.
