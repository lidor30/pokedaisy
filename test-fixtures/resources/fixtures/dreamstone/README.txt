captured: 2026-10-23 (headless mgba_dump)
rom: Pokemon Dreamstone Mysteries (~/Downloads/Game ROMs & Emulation/gba/Pokemon Dreamstone Mysteries.gba, sha1 20e91aec...,
  no version shown in the game; its debug menu says pokeemerald-expansion v1.11.2)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Dreamstone Mysteries.sav - no save existed, so a new game was played headless
  (2026-10-22): the intro (the first look, name "Aaaaaaa"), the LAB PACKAGE upstairs, out into CARABRUE TOWN; then a
  script poked into free EWRAM (0x0203F000) and run with `call 0x081EAD35 <addr>` (its ScriptContext_SetupScript):
  callnative ScrCmd_createmon (0x0A1EDA35, the game's own givemon) TORCHIC 12, PIKACHU 9, OSHAWOTT 8, CHIKORITA 10,
  SPRIGATITO 7; setflag 0x860 / 0x861 (the POKéMON / POKéDEX menus); additem POKé BALL x10, POTION x5, ANTIDOTE x2,
  REPEL x3, ORAN BERRY x2; saved through the START menu's SAVE.
boot script: native-capture/boot/dreamstone.txt (CONTINUE, the field in CARABRUE TOWN: map 0.0, mapsec 0xD5)
the game's own screens: the party menu - TORCHIC Lv12 35/35, PIKACHU Lv9 25/25, OSHAWOTT Lv8 26/26, CHIKORITA (female)
  Lv10 30/30, SPRIGATITO Lv7 24/24; OSHAWOTT's summary No. 224, Quiet, 26 / 16 / 13 / 16 / 13 / 10, 314 EXP (105 to Lv9),
  TACKLE / TAIL WHIP / WATER GUN; its POKéDEX (titled HOENN): SEEN 2 / OWN 2, No. 054 CHIKORITA "Leaf POKéMON" 2'11"
  14.1 lbs.; the save screen: CARABRUE TOWN, BADGES 0, POKéDEX 2. Money 3000 (SaveBlock1 +0x490 XOR SaveBlock2 +0xAC).
