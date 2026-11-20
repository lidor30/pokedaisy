captured: 2026-10-31T16:41:03Z (headless, scripts/capture_fixture_headless.sh)
rom: Pokemon FireRed rev 1 randomized (scripted wild battle) (~/Downloads/Game ROMs & Emulation/gba/Pokemon FireRed Randomized (v4.6.1) (Rev 1, seed 18359028979400).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - FireRed Version (USA, Europe) (Rev 1).srm
boot script: native-capture/boot/randomized_firered_battle.txt
At the action menu of a scripted wild PIKACHU Lv5 (FIGHT on the cursor). Checked from there (the same boot script, run again): a written
move cell 1 in gMoveSelectionCursor (0x02023FFC) + A, after A on FIGHT, spent that move's PP (gBattleMons[0].pp[1]
0x14 -> 0x13) and the turn played: retail's one-press picks.
