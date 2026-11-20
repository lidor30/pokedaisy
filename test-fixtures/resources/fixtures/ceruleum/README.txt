captured: 2026-11-18 (headless, scripts/capture_fixture_headless.sh, native-capture/boot/ceruleum.txt)
rom: Pokemon Ceruleum v1.1.1 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Ceruleum (v1.1.1).gba, sha1 2b0b6086...): retail
  Emerald (USA, Europe) with the PokeCommunity thread's xdelta (pokeceruleum.xdelta) applied
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Ceruleum (v1.1.1).sav - no save existed, so a new game was played headless:
  the intro mashed through (the player named Aaaaaaa; the game gives a XATU Lv70), then a script poked into free EWRAM
  (0x0203F000) and run with `call 0x081E2869 <addr>` (its ScriptContext_SetupScript): setflag 0x860 / 0x861, callnative
  ScrCmd_createmon (0x0A1E5281) CHARIZARD 10, PIKACHU 8, CYNDAQUIL 5; additem POKE BALL x10, POTION x5, REPEL x2; saved
  through the START menu's SAVE in COUNCIL HQ
the game's own screens: the party menu - Xatu Lv70 171/171, Charizard Lv10 37/37, Pikachu Lv8 24/24, Cyndaquil Lv5 19/19;
  the trainer card's money 258984; the save screen: COUNCIL HQ
