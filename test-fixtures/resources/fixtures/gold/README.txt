captured: 2026-10-15 (headless mgba_dump, by hand; Game Boy Color: wram.bin = C000-DFFF as the bus had it,
  hram.bin = FF80-FFFE, wramx.bin = all 8 WRAM banks, 0x1000 each - the game keeps its state in bank 1)
rom: Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible) (~/Downloads/Game ROMs & Emulation/gbc/, sha1
  d8b8a360...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).sav - a new game
  played headless (the user had no Gold / Silver save): the clock (10:00, SUNDAY), the name GOLD from the list, MOM's
  POKeGEAR, CYNDAQUIL from ELM's lab (no nickname), the aide's POTION, then a wild PIDGEY (Lv2) caught on ROUTE 29 with the
  second POKe BALL thrown; saved through the START menu in ROUTE 29's grass. Poked into RAM, not got in game: 5 POKe BALLs
  before the catch (wNumBalls 0xD5FC = 1, 0xD5FD = 0x05, 0xD5FE = 5, 0xD5FF = 0xFF; two were thrown, 3 left), CYNDAQUIL's
  held BERRY (wPartyMon1Item 0xDA2B = 0xAD), and the text speed (wOptions 0xD199 = 1, FAST) while playing, put back to the
  game's MID (3) before saving. The same file is the Silver save there (the silver fixture): it loads in Silver as it is.
  Party: CYNDAQUIL Lv5 17/20 (TACKLE / LEER, BERRY), PIDGEY Lv2 13/13 (TACKLE); bag POTION x1, POKe BALL x3; money 3000;
  play time 0:04; ID 54088.
boot: native-capture/boot/gold.txt (wait 600, START held 3 frames, A held 3 frames three times: the title, CONTINUE, the
  save summary): on ROUTE 29 (wMapGroup 24, wMapNumber 3 at DA00) at (45, 11), in the grass.
