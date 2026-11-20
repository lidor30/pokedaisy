captured: 2026-10-21 (headless mgba_dump)
rom: Pokemon Team Rocket Dragonsden Version (~/Downloads/Game ROMs & Emulation/gba/Pokemon Team Rocket Dragonsden Version.gba,
  sha1 40780347..., unversioned)
save: ~/Downloads/Game ROMs & Emulation/rom saves from discord/mGBA Romhack Saves/Pokemon - Team Rocket Edition.srm (the only
  one on file, from the game's Discord; its CONTINUE window says Jason, TIME 0:01, BADGES 08)
boot script: native-capture/boot/team_rocket_trainer.txt (team_rocket_trainer's), then poke16 0x02024CD6 1 (ONIX's
  HP), A (FIGHT), DOWN, A (POISON FANG), wait 1150 ("Foe Onix fainted!"), A eight times 15 frames apart: "KANTO LEADER
  BROCK is about to use..." (frame 3842), dumped. ONIX 0 HP, gBattlerPartyIndexes[1] still 0, monToSwitchIntoId[1] = 2
  (OMASTAR, gEnemyParty slot 2) - where the next foe sits until it's sent out.
