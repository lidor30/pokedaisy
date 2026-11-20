captured: 2026-10-11 (headless mgba_dump, by hand)
rom: Pokemon SoulGold v1.2, third build (sha1 2be7c99a...; ~/Downloads/Game ROMs & Emulation/gba/Pokemon SoulGold (v1.2) (Alt 2).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon SoulGold (v1.2).sav
boot: as the soulgold_v12c fixture, then a scripted wild battle: B6 19 00 05, 12 zero bytes, B7 02
  (setwildbattle SPECIES_PIKACHU, 5; nop padding; dowildbattle) poked byte by byte at 0x0203FF00 and
  `call 0x08236DBC 0x0203FF00` (ScriptContext_SetupScript, 0xF8 after the second v1.2's), wait 1200, A:
  at the action menu (gBattlerControllerFuncs[0] 0x08061D09): dumped.
  Then, not in this dump: A -> 0x08060E59 (moves), B, RIGHT, A -> 0x0805FB01 (bag), B, LEFT, DOWN, A ->
  0x0805FB45 (party; gPartyMenu 0x02038D2C set) - every handler at NATIVE_SOULGOLD_V1_2's address.
