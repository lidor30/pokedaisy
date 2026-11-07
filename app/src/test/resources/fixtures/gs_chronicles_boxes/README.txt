captured: 2026-11-03 (headless mgba_dump, by hand)
rom: Pokemon GS Chronicles v2.7.6 (~/Downloads/Game ROMs & Emulation/gba/Pokemon GS Chronicles (v2.7.6).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon GS Chronicles (v2.7.6).sav
boot: native-capture/boot/gs_chronicles.txt (the field), wait 60, then the game's own SendMonToPC (FireRed rev 0's 0x08040B90, CFRU's hook)
  called on the party's lead (0x02024284), the current box (0x02029314) set to 0, 19, 22, 24 before each
  and back to 0 after: the same lead in box 1, 20, 23 and 25's first slot (each call returned 1, "sent to the PC"), at CFRU's places
  (0x02029318, 0x0203CB44, 0x02027434, 0x02024638), 58-byte records - Unbound's layout.
  wait 5, dumped. The save had nothing boxed before.
