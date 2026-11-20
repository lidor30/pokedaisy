captured: 2026-10-13 (headless mgba_dump, by hand)
rom: Pokemon FireRed Rocket Edition (~/Downloads/Game ROMs & Emulation/gba/Pokemon FireRed Rocket Edition.gba, sha1 033d0537...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - FireRed Version (USA, Europe) (Rev 1).srm (retail FireRed's)
boot: as `rocket_edition` (native-capture/boot/firered_hack.txt: the field by the sea), wait 200, then a scripted
  wild battle: B6 8F00 64 0000 B7 02 (setwildbattle SNORLAX Lv100; dowildbattle; end) poked at 0x0203FF00 and
  `call 0x08069AE5 0x0203FF00` (ScriptContext_SetupScript, rev 0's address and code), wait 300, A, wait 350,
  A, wait 300: the action menu ("What will JOLTEON do?"), dumped.
checked from there (same run): gActionSelectionCursor (0x02023FF8) walks 0 -> 1 -> 3 on RIGHT, DOWN; a written 2 +
  A opens the party menu (gPartyMenu 0x0203B0A0, slotId +9: 0, DOWN -> 1), A, A (SHIFT) puts party slot 1 into
  gBattlerPartyIndexes[0] (0x02023BCE = 1, "Go! KADABRA!"). A second run: a written action 0 + A, a written
  gMoveSelectionCursor (0x02023FFC) 1 + A spends move 1's PP (0x14 -> 0x13).
