captured: 2026-10-11 (headless mgba_dump, by hand)
rom: Pokemon Sword and Shield Ultimate Plus v1.2.1.2 Casual and Performance Patch (its own ROM; the base build's save)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).sav
boot: as `sword_shield_up` with this build (native-capture/boot/default.txt: the field in POSTWICK), wait 200, then a scripted
  wild battle: B6 6304 04 0000 B7 02 (setwildbattle species 1123 WOOLOO Lv4; dowildbattle; end) poked at
  0x0203FF00 and `call 0x08069AE5 0x0203FF00` (ScriptContext_SetupScript, FireRed rev 0's address and code,
  kept by CFRU), wait 300, A, wait 200, A, wait 250: the action menu ("What will Scorbunny do?"), dumped.
