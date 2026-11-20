captured: 2026-10-16 (headless mgba_dump, by hand; cmds.txt is the exact command file)
rom: Pokemon Elite Redux 2.65.4.1b (~/Downloads/Game ROMs & Emulation/gba/Pokemon Elite Redux (2.65.4.1b).gba)
save: ~/Downloads/Game ROMs & Emulation/rom saves from discord/saves/Pokemon - Elite Redux.sav (the elite_redux fixtures')
boot: native-capture/boot/elite_redux.txt (the field), then a scripted trainer battle: 5C 03 0901 0000 <lose text> 02
  (trainerbattle TRAINER_BATTLE_SINGLE_NO_INTRO_TEXT, trainer 265 ROXANNE, local id 0, lose text = 0x089AEE84, her name;
  then end) poked at 0x0203FF00 and `call 0x081F3985 0x0203FF00` (ScriptContext1_SetupScript).
then wait 300, A ("Leader Roxanne would like to battle!"), wait 400: the action menu ("What will Zekrom do?", FIGHT / INFO
  over POKéMON / FORFEIT), dumped. gBattleTypeFlags 0x0C (TRAINER 8 | IS_MASTER 4). The randomizer save gives Roxanne five
  Lv20s (gEnemyParty, 0x4C plaintext mons, slot 5 empty): ANORITH 54/54 (Focus Sash, Jolly), LILEEP, CORSOLA, NOIBAT
  (species 2503), NOSEPASS. gBattlerPartyIndexes (0, 0), monToSwitchIntoId 06 06 06 06. Foe gBattleMons[1]: ANORITH,
  abilities 52 / 303 / 315 / 4 (HYPER CUTTER + innates FOSSILIZED / HYDRATE / BATTLE ARMOR), item 287 FOCUS SASH, stats
  49 / 37 / 45 / 24 / 31, stages all 6 - as the game's INFO > Pokémon Stats page shows them.
