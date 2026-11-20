captured: 2026-10-08 (headless mgba_dump, by hand)
rom: Pokemon SoulGold v1.2, second build (sha1 5d6a0362...; ~/Downloads/Game ROMs & Emulation/gba/Pokemon SoulGold (v1.2) (Alt 1).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon SoulGold (v1.2).sav
boot: as the soulgold_v12b fixture, then a scripted wild battle: B6 1900 05 then 12 zero bytes, B7 02
  (setwildbattle SPECIES_PIKACHU, 5; nop padding; dowildbattle) poked at 0x0203FF00 and
  `call 0x08236CC4 0x0203FF00` (ScriptContext_SetupScript, 0x98 before the first v1.2's), wait 1200, A
  through the last message: at the action menu (gBattlerControllerFuncs[0] 0x08061D09): dumped.
  Then, not in this dump: A -> 0x08060E59 (moves), B, RIGHT, A -> 0x0805FB01 (bag), B, LEFT, DOWN, A ->
  0x0805FB45 (party; gPartyMenu 0x02038D2C set) - every handler at NATIVE_SOULGOLD_V1_2's address.
