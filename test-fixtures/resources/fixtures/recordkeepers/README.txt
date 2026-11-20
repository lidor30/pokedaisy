captured: 2026-11-18 (headless, scripts/capture_fixture_headless.sh, native-capture/boot/recordkeepers.txt)
rom: Pokemon RecordKeepers v1.2.1 (~/Downloads/Game ROMs & Emulation/gba/Pokemon RecordKeepers (v1.2.1).gba, sha1 8df1c972...):
  retail Emerald (USA, Europe) with the Hackdex patch applied
save: ~/Documents/RetroArch/saves/mGBA/Pokemon RecordKeepers (v1.2.1).sav - it deletes retail Emerald's save, so a new game was
  played headless: the intro mashed through (the player named Tiana), then a script poked into free EWRAM (0x0203F000) and run
  with `call 0x081D7BC5 <addr>` (its ScriptContext_SetupScript): callnative ScrCmd_createmon (0x0A1DA5E1) CHARMANDER 10,
  PIKACHU 8, MUDKIP 5; additem POKE BALL x10, POTION x5; saved through its own START menu's Save ("Tiana saved the game.")
the game's own screens: CONTINUE shows Tiana, TIME 0:01, RUNS 0; its INFO page the trainer ID 45398E0B (gPlayerParty[0]'s
  OT ID). It has no party screen before the story's flag, so the party's stats are the dump's (Charmander Lv10 29/29,
  Pikachu Lv8 24/24, Mudkip Lv5 20/20), and the battle shows the lead's
