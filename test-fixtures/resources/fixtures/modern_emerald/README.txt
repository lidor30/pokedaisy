captured: 2026-11-09T08:26:28Z (headless, scripts/capture_fixture_headless.sh)
rom: Pokemon Modern Emerald v3.6 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Modern Emerald (v3.6).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Modern Emerald (v3.6).sav
boot script: native-capture/boot/modern_emerald.txt
The save: a new game played headless (retail Emerald's save is "erased due to corruption" by this build): the player Aaaaa
(boy), the game-mode screens left on the Classic preset (MODERN TYPES, FAIRY TYPE off; TYPE CHART Gen VI+; no randomizer),
then in the moving truck six Pokémon and a bag given through the game's own script commands (givemon / additem / setflag
FLAG_SYS_POKEMON_GET and FLAG_SYS_POKEDEX_GET, poked into EWRAM 0x0203FD00 and run by ScriptContext_SetupScript 0x081D940D):
TREECKO Lv5, SYLVEON Lv30 holding LEFTOVERS, GARDEVOIR Lv35, MARILL Lv12, GALLADE Lv40, MAWILE Lv20; 5 POTIONs, 10 POKé
BALLs, 3 ORAN BERRYs, 2 REPELs, TM01. Saved from the START menu (SAVE, YES) and rebooted: CONTINUE, the truck (map 25 / 40),
six in the party, 3000 money. The dump is the field, free.
