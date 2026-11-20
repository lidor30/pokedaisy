captured: 2026-10-16 (headless mgba_dump, by hand; Game Boy Color: wram.bin = C000-DFFF as the bus had it,
  hram.bin = FF80-FFFE, wramx.bin = all 8 WRAM banks, 0x1000 each - the game keeps its state in bank 1)
rom: Pokemon Polished Crystal (v3.2.3).gbc (~/Downloads/Game ROMs & Emulation/gbc/, the official release build, sha1 6930b48a...)
save: ~/Documents/RetroArch/saves/mGBA/polishedcrystal-3.2.3.sav - a new game played headless (the user had no Polished
  Crystal save): the start-up options left at their defaults (START), the clock at the game's default (DAY 10:00, SUNDAY,
  Daylight Saving Time YES at MOM's), the default (middle) trainer-card photo, named Daisy, MOM's POKeGEAR, CYNDAQUIL from
  ELM's lab (no nickname; it comes holding an ORAN BERRY), LYRA's battle in the lab lost (the polished_crystal_trainer
  fixture), the aide's POTION, then a wild PIDGEY (Lv3) caught on ROUTE 29 with the second POKe BALL thrown; saved
  through the START menu in ROUTE 29's grass. Poked into RAM, not got in game: 5 POKe BALLs before the catch (wNumBalls
  01:D90B = 1, wBalls D90C = 0x01 POKE_BALL, 5, 0xFF; two were thrown, 3 left), CYNDAQUIL's ORAN BERRY put back
  (wPartyMon1Item 01:DCD7 = 0x44 ORAN_BERRY - the game gave it and the battles ate it twice), and the text speed (wOptions1
  00:CFF1 = 0xC3, INSTANT) while playing, put back to the game's own 0xC2 (FAST) before saving. No POKeDEX yet (ELM's
  errand isn't done; wStatusFlags D7E3 = 0x10) and only the phone card in the POKeGEAR (wPokegearFlags D9D8 = 0x84).
  The same file is under each 3.2.x build's name there: it loads in 3.2.0, 3.2.1, 3.2.2 and faithful-3.2.3 as it is
  (sSaveVersion = 10 in all of them, no upgrade screen; the polished_crystal_3_2_* / _faithful fixtures).
  Player Daisy (wPlayerName 01:D47B, 0x53-terminated), ID 12548 (wPlayerID D478 = 31 04), wPlayerGender D47A, money 3000
  (wMoney D7E5 = 00 0B B8), play time 0:05. Party (wPartyCount 01:DCCE = 2, wPartyMons DCD6, 48-byte party_struct:
  macros/ram.asm - species, item, 4 moves, ID, exp, 6 EVs, 3 DV bytes, personality, form, 4 PP, happiness, pokerus,
  caught ball / level / location, level @+0x1F, status, unused, HP @+0x22, max HP, 5 stats; nicknames at
  wPartyMonNicknames DE38, 11 bytes each): CYNDAQUIL (0x9B) Lv5 13/19, TACKLE (0x21) / LEER (0x2B), holding ORAN BERRY
  (0x44); PIDGEY (0x10) Lv3 3/15, female, TACKLE, no item. Bag: Items pocket empty (wNumItems D827 = 0), Medicine
  POTION (0x19) x1 (wNumMedicine D8BF = 1), Balls POKe BALL x3, Berries empty (wNumBerries D93F), Key Items none
  (wKeyItems D800), no TMs (wTMsHMs D7F5).
boot: native-capture/boot/polished_crystal.txt (wait 600, START held 3 frames, A held 3 frames three times: the title,
  Continue, the save summary): on ROUTE 29 (wMapGroup 24, wMapNumber 3 at 01:DCAC) at (49, 12) (wYCoord DCAE = 12,
  wXCoord DCAF = 49), in the grass. Every RAM address here is the same in 3.2.0 - 3.2.3 and faithful-3.2.3 (their .sym
  files' WRAM / HRAM sections differ only in a few trainer-script and printer variables); hROMBank is FF87, not
  pokecrystal's FF9D (mgba_dump's gbfar / gbinject assume FF9D - not used here).
