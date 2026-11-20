captured: 2026-10-07 (headless mgba_dump, by hand)
rom: Pokemon Emerald Seaglass v3.0 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Emerald Seaglass (v3.0).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Emerald Seaglass (v3.0).sav
boot: native-capture/boot/default.txt + 3x B, then a scripted wild battle: B6 1900 05 0000 0000 00
  0000 B7 02 (setwildbattle SPECIES_PIKACHU, 5; dowildbattle; end) poked at 0x0203FF00 and
  `call 0x081EF598 0x0203FF00` (ScriptContext_SetupScript). At the action menu, a copy of the
  Torchic poked into party slot 2 (0x02019C84) and gPlayerPartyCount (0x02019C1D) = 2 - poked
  before the battle the game drops it - then POKéMON, DOWN, A, A (SHIFT): the copy comes in (hit
  to 13/19), and the action menu again: dumped.
