captured: 2026-10-07 (headless mgba_dump, by hand)
rom: Pokemon SoulGold v1.1.4 (~/Downloads/Game ROMs & Emulation/gba/Pokemon SoulGold (v1.1.4).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon SoulGold (v1.1.4).sav
boot: as the soulgold fixture, then two copies of the Cyndaquil poked into party slots 2-3
  (96 bytes apart from 0x0203901C, HP 17 and 13; 0x020394D0 = 3 was poked too, mistaken for the count - the game
  recounts gPlayerPartyCount 0x02038DD5 to 3 itself at battle start), and a
  scripted wild battle: B6 1900 05 0000 0000 0000 00 0000 0000 B7 02 (setwildbattle
  SPECIES_PIKACHU, 5; dowildbattle; end) poked at 0x0203FF00 and `call 0x0823647C
  0x0203FF00` (ScriptContext_SetupScript). At the action menu: POKéMON, DOWN, DOWN, A,
  A (SHIFT) - the third mon (13/21) comes in - then the action menu again: dumped.
