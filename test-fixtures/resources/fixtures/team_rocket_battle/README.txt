captured: 2026-10-21 (headless mgba_dump)
rom: Pokemon Team Rocket Dragonsden Version (~/Downloads/Game ROMs & Emulation/gba/Pokemon Team Rocket Dragonsden Version.gba,
  sha1 40780347..., unversioned)
save: ~/Downloads/Game ROMs & Emulation/rom saves from discord/mGBA Romhack Saves/Pokemon - Team Rocket Edition.srm (the only
  one on file, from the game's Discord; its CONTINUE window says Jason, TIME 0:01, BADGES 08)
boot script: native-capture/boot/team_rocket_battle.txt: the field (team_rocket's), then a scripted wild RATTATA Lv5
  (setwildbattle 19 5 0 + a second mon's three zero fields / dowildbattle / end at 0x0203F800, run through
  ScriptContext_SetupScript 0x080CEDC5); dumped at the action menu ("What will Crobat do?", frame 2108).
gBattleTypeFlags 4 (IS_MASTER), gBattlersCount 2, gBattleMons[0] CROBAT 247/247 Lv79 (ability 39, types 3 / 2, PP 10 15
  15 15), gBattleMons[1] RATTATA; gEnemyParty slot 0 the same RATTATA; gBattlerControllerFuncs[0] = HandleInputChooseAction
  (0x08082291). From here (scratchpad replays): RIGHT walked the action cursor (0x020250FC) 0 -> 1, DOWN 1 -> 3 (RUN); a
  written move cell 2 + A spent Poison Fang's PP (15 -> 14); a written action cell 2 + A opened the party menu
  (0x08084CB5), DOWN, A, SHIFT sent HOUNDOOM in (gBattlerPartyIndexes[0] = 1).
