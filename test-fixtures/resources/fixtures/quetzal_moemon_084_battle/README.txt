captured: 2026-10-13 (headless mgba_dump, by hand)
rom: Moemon Quetzal English Alpha 0.8.4 Hotfix (~/Downloads/Game ROMs & Emulation/gba/Moemon Quetzal (Alpha 0.8.4 Hotfix) (English).gba, sha1 f1035f61...)
save: ~/Downloads/Game ROMs & Emulation/gba/Pokemon Quetzal (Alpha 9.0) (Spanish).sav (Quetzal Spanish Alpha 9 v0's: 0.8.4 loads it)
boot: as `quetzal_moemon_084`, then a scripted wild battle: B6 1000 03 0000 0000 00 0000 B7 02 (setwildbattle SPECIES_PIDGEY, 3,
  as for `quetzal_battle`) poked at 0x0203FF00 and `call 0x080D35BD 0x0203FF00` (its ScriptContext_SetupScript), wait 300,
  A, wait 200, A, wait 200, A (past GYARADOS's Intimidate), wait 200: the action menu (gBattlerControllerFuncs[0] =
  0x08087FED), dumped.
  This build's own Pokémon pictures are not in this dump: the two decompressed battle sprites (the foe's
  front, 0x02008E4C, the player's back, 0x02006E4C, 0x800 bytes each) and the 18 palette slots in
  0x02039000-0x02039C00 that differ from the base build's dump were zeroed.
