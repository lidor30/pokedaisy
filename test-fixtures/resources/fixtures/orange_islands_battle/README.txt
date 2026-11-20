captured: 2026-10-09 (headless mgba_dump, by hand)
rom: Pokemon Orange Islands (~/Downloads/Game ROMs & Emulation/gba/Pokemon Orange Islands.gba, sha1 8bac897d...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Orange Islands.srm
boot: as `orange_islands` (the field on VALENCIA ISLAND), then a scripted wild battle: B6 9901 08 0000 B7 02
  (setwildbattle species 409 - its CRYSTAL ONIX - Lv8; dowildbattle; end) poked at 0x0203FF00 and
  `call 0x08069AE5 0x0203FF00` (ScriptContext_SetupScript, retail rev 0's address and code), wait 300, A,
  wait 200: the action menu ("What will PIKACHU do?"), dumped.
