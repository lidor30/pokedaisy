captured: 2026-10-09 (headless mgba_dump, by hand)
rom: Pokemon R.O.W.E. v2.1.9.1 Experimental (sha1 81bd0f4b...)
save: ~/Documents/RetroArch/saves/mGBA/rowe.sav
boot: native-capture/boot/rhh_splash.txt, wait 120, then a scripted wild battle: the bytes
  b6 09 01 03 00 00 00 00 00 00 00 b7 02 (setwildbattle WURMPLE Lv3 / dowildbattle / end) poked at
  0x0203F300 and `call 0x080D94E5 0x0203F300` (ScriptContext1_SetupScript), wait 400, A, wait 200:
  the action menu (gBattlerControllerFuncs[0] 0x03004AD0 = 0x08075791), dumped.
