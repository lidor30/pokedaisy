captured: 2026-10-14 (headless mgba_dump, by hand; Game Boy Color: wram.bin = C000-DFFF as the bus had it,
  hram.bin = FF80-FFFE, wramx.bin = all 8 WRAM banks, 0x1000 each - Crystal keeps its state in bank 1)
rom: Pokemon - Crystal Version (USA, Europe) (Rev 1) (~/Downloads/Game ROMs & Emulation/gbc/, sha1 f2f52230...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - Crystal Version (USA, Europe) (Rev 1).sav - a new game played
  headless in rev 1 (the user had no Crystal save): CHRIS, CHIKORITA from ELM's lab, the lab's POTION, then a
  wild PIDGEY caught on ROUTE 29; saved through the START menu. Two things were poked into RAM before that, to
  have more to show, not got in game: 5 POKé BALLs (wNumBalls 0xD8D7 = 1, 0xD8D8 = 0x05, 0xD8D9 = 5,
  0xD8DA = 0xFF; one was thrown at the PIDGEY) and CHIKORITA's held BERRY (wPartyMon1Item 0xDCE0 = 0xAD).
boot: wait 600, press START, wait 200, then A (held 3 frames) and wait 120 three times, A, wait 200 (title,
  CONTINUE, the save summary): on ROUTE 29 (wMapGroup 24, wMapNumber 3) at (46, 11).
