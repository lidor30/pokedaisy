captured: 2026-10-30 (headless mgba_dump, by hand)
rom: Pokemon Unbound v2.1.1.1 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Unbound (v2.1.1.1).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - Unbound (v2.1.1.1).srm
boot: native-capture/boot/default.txt (the field), wait 60, then the game's own SendMonToPC (FireRed rev 0's 0x08040B90,
  CFRU's hook) called on the party's lead (GIBLE Lv11, 0x02024284) four times, the current box (0x02029314) set to 0,
  19, 22 and 24 before each and back to 0 after: the same GIBLE in box 1, 20, 23 and 25's first slot (each call
  returned 1, "sent to the PC"), at CFRU's places (0x02029318, 0x0203CB44, 0x02027434, 0x02024638), 58-byte
  records. wait 5, dumped. The save had nothing boxed before.
