captured: 2026-10-25 (headless, native-capture/mgba_dump)
rom: ~/Downloads/Game ROMs & Emulation/gba/Pokemon - FireRed Version (USA, Europe) (Rev 1).gba
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - FireRed Version (USA, Europe) (Rev 1).srm
SHINY ODDS' foe, in battle: boot/default.txt, B x3, then setwildbattle PIKACHU Lv5 + end at 0x0203FF00 through
ScriptContext_SetupScript (0x08069AF9), 10 frames, savestate + dump; ShinyOdds.Roller (odds 1) rewrote gEnemyParty[0]
from that dump, the state was loaded, the 80 bytes poked back, then dowildbattle + end, 420 + 120 frames, A, 200 frames.
The battle drew the shiny palette; gBattleMons[1] holds the shiny personality (shiny value < 8).
