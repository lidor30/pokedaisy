captured: 2026-10-13T20:35:02Z (headless, scripts/capture_fixture_headless.sh)
rom: Pokemon GS Chronicles v2.7.6 (~/Downloads/Game ROMs & Emulation/gba/Pokemon GS Chronicles (v2.7.6).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon GS Chronicles (v2.7.6).sav
boot script: native-capture/boot/gs_chronicles.txt
the save: the user has none, and retail FireRed's doesn't load ("The save file has been deleted..."), so it is a
  new game played headless with mgba_dump (2026-10-13): Oak's intro, name "Aa", no species randomizer, out of the
  house into NEW BARK TOWN; then a script poked into free EWRAM and run with `call 0x08069AE5 <addr>` (FireRed rev 0's
  ScriptContext_SetupScript, kept by CFRU): givemon CYNDAQUIL 5 / TOTODILE 6 / CHIKORITA 7, setflag 0x828 / 0x829
  (the POKéMON / POKéDEX menus), additem POTION x5, POKé BALL x10, AWAKENING x3, givemoney 3000 (13000 in all); saved
  through the START menu's SAVE. The game's own screens: CYNDAQUIL Lv5 19/19, Modest, OT Aa IDNo 57797, stats
  19/9/10/12/11/11 (HP/ATK/DEF/SPA/SPD/SPE); POKéDEX Seen 3 Owned 3 (No001 Chikorita, No004 Cyndaquil, No007
  Totodile); trainer card ₽13000.
