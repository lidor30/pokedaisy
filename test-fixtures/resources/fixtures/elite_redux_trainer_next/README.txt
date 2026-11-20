captured: 2026-10-16 (headless mgba_dump, by hand; cmds.txt is the exact command file)
rom: Pokemon Elite Redux 2.65.4.1b (~/Downloads/Game ROMs & Emulation/gba/Pokemon Elite Redux (2.65.4.1b).gba)
save: ~/Downloads/Game ROMs & Emulation/rom saves from discord/saves/Pokemon - Elite Redux.sav (the elite_redux fixtures')
boot: native-capture/boot/elite_redux.txt (the field), then a scripted trainer battle: 5C 03 0901 0000 <lose text> 02
  (trainerbattle TRAINER_BATTLE_SINGLE_NO_INTRO_TEXT, trainer 265 ROXANNE, local id 0, lose text = 0x089AEE84, her name;
  then end) poked at 0x0203FF00 and `call 0x081F3985 0x0203FF00` (ScriptContext1_SetupScript).
then wait 300, A, wait 400 (the action menu), poke16 gBattleMons[1].hp (0x0201C5B2) 1, A (FIGHT), A (RAZOR LEAF),
  wait 1440: ANORITH fainted, Zekrom's BEAST BOOST raised its Attack, "Leader Roxanne sent out Nosepass!" (no switch
  prompt: Set style), the next action menu, dumped. gBattlerPartyIndexes[1] = 4 (NOSEPASS, gEnemyParty slot 4), slot 0
  HP 0 / 54, monToSwitchIntoId back to 06 06 06 06 (it held 4 only from the AI's pick until the send-out),
  battlerPartyOrders[1] 41 23 05. Zekrom's statStages[ATK] = 7 (INFO shows one up arrow, Atk 106 = 71 x 1.5); foe
  NOSEPASS: abilities 100 / 5 / 116 / 321 (STALL; STURDY / SOLID ROCK / JUGGERNAUT), item 310 EVIOLITE, stats 26 / 71 /
  23 / 37 / 47, Bold - matching INFO > Pokémon Stats.
