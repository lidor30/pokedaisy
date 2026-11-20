captured: 2026-11-16 (headless, scripts/capture_fixture_headless.sh)
rom: Pokemon Monomyth v1.2 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Monomyth (v1.2).gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Monomyth (v1.0).srm
boot script: native-capture/boot/monomyth.txt
v1.2 (sha1 742e62ff..., game code BPEE) loads the monomyth fixture's v1.0 save as it is: the same party, money (1968) and map (a CATSEAR
  CITY building, the player at 6, 3); its gMapHeader / gObjectEvents / gBagPockets sit 4 bytes past v1.1's. From this point, headless: the PC
  script (special 57 through ScriptContext_SetupScript 0x08090C81) opened the PC menu, A opened BOXES, three B presses closed it and
  sLockFieldControls (0x0203BF6B) read 0 again.
