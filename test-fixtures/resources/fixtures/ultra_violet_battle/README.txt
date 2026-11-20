captured: 2026-10-11 (headless mgba_dump, by hand)
rom: Pokemon Ultra Violet v1.22 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Ultra Violet (v1.22).gba, sha1 3b6c51d8...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Ultra Violet (v1.22).sav
boot: as `ultra_violet` (native-capture/boot/default.txt: the field in PALLET TOWN), wait 200, then a scripted
  wild battle: B6 1000 03 0000 B7 02 (setwildbattle PIDGEY Lv3; dowildbattle; end) poked at 0x0203FF00 and
  `call 0x08069AE5 0x0203FF00` (ScriptContext_SetupScript, rev 0's address and code), wait 300, A, wait 200,
  A, wait 250: the action menu ("What will CHARMANDER do?"), dumped.
