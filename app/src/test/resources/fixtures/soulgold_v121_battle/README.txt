captured: 2026-10-31 (headless mgba_dump, by hand)
rom: Pokemon SoulGold v1.2.1 (sha1 32988f91...; ~/Downloads/Game ROMs & Emulation/gba/Pokemon SoulGold (v1.2.1).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon SoulGold (v1.2).sav
boot: as the soulgold_v121 fixture, then a scripted wild battle: B6 19 00 05, 12 zero bytes, B7 02
  (setwildbattle SPECIES_PIKACHU, 5; nop padding; dowildbattle) poked byte by byte at 0x0203FF00 and
  `call 0x0823BC04 0x0203FF00` (ScriptContext_SetupScript), wait 1200, A, wait 200: at the action menu
  (gBattlerControllerFuncs[0] 0x08061DBD, gActionSelectionCursor 0x02001E50 = 0): dumped.
  Then, not in this dump: A -> 0x08060F01 (moves), B, RIGHT (cursor 1), A -> 0x0805FB39 (bag), B, LEFT,
  DOWN (cursor 2), A -> 0x0805FB7D (party; gPartyMenu 0x02038B6C set).
