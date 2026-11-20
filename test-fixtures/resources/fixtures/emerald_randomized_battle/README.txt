captured: 2026-10-31T16:41:16Z (headless, scripts/capture_fixture_headless.sh)
rom: Pokemon Emerald randomized (scripted wild battle) (~/Downloads/Game ROMs & Emulation/gba/Pokemon Emerald Randomized (v4.6.1) (seed 13578136444066).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - Emerald Version (USA, Europe).srm
boot script: native-capture/boot/randomized_emerald_battle.txt
At the action menu of a scripted wild PIKACHU Lv5 (FIGHT on the cursor). Checked from there (the same boot script, run again): a written
move cell 1 in gMoveSelectionCursor (0x020244B0) + A, after A on FIGHT, spent that move's PP (gBattleMons[0].pp[1]
0x19 -> 0x18) and the turn played: retail's one-press picks.
