captured: 2026-10-21 (headless mgba_dump)
rom: Pokemon Team Rocket Dragonsden Version (~/Downloads/Game ROMs & Emulation/gba/Pokemon Team Rocket Dragonsden Version.gba,
  sha1 40780347..., unversioned)
save: ~/Downloads/Game ROMs & Emulation/rom saves from discord/mGBA Romhack Saves/Pokemon - Team Rocket Edition.srm (the only
  one on file, from the game's Discord; its CONTINUE window says Jason, TIME 0:01, BADGES 08)
boot script: native-capture/boot/team_rocket_trainer.txt: the field, then a scripted trainer battle (trainerbattle
  TRAINER_BATTLE_SINGLE_NO_INTRO_TEXT, trainer 415 KANTO LEADER BROCK, local id 0, lose text = his name), A, A: the
  action menu ("What will Crobat do?", frame 2510), dumped.
gBattleTypeFlags 0x0C (TRAINER 8 | IS_MASTER 4); gEnemyParty: ONIX Lv45 100/100 (CHOICE BAND, ROCK TOMB),
  KABUTOPS Lv46 125/125, OMASTAR Lv46 134/134, GOLEM Lv47 151/151; gBattlerPartyIndexes (0, 0); gBattleStruct
  (0x02000010) + 0x62 = monToSwitchIntoId 06 06 06 06, battlerPartyOrders after it. Foe gBattleMons[1]: ONIX, ability 5
  (STURDY), item 452 (CHOICE BAND), stats 59 / 162 / 81 / 45 / 59, stages all 6.
