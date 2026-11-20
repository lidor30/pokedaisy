captured: 2026-11-12 (headless mgba_dump, by hand; Game Boy: wram.bin = C000-DFFF, hram.bin = FF80-FFFE)
rom: Pokemon - Blaue Edition (Germany) (SGB Enhanced).gb (~/Downloads/Game ROMs & Emulation/gbc/, sha1 20e72dc6...)
  - run as a Super Game Boy, like English Red.
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - Blaue Edition (Germany) (SGB Enhanced).sav - no save of its own existed: the red fixture's game (Nelson, 8 badges,
  $999999, play time 24:18, 10 owned / 106 seen, six Lv100s: Joseph, Liam, Fred, Henry, Seth, Richard; 17 bag items) written
  out by scripts/gen1_save_from_fixture.py fixtures/red <out.sav>. It loads in English Red too (German Blue keeps Red's SRAM layout).
boot: as the blue_de fixture, then a wild battle poked in on the field (wCurEnemyLevel 0xD12C = 12, wCurOpponent 0xD05E = 0xB0,
  CHARMANDER's internal id), wait 600, A, wait 300: the battle menu (KMPF / PkMn / ITEM / FLUCHT).
  Then A (KMPF), wait 60: the move list, cursor on DONNERBLITZ. Joseph PIKACHU Lv100 against a wild GLUMANDA Lv12, 33 HP.
