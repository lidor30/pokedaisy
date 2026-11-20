captured: 2026-10-15 (headless mgba_dump, by hand)
rom: Pokemon Quetzal English Alpha 9 v1 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Quetzal (Alpha 9v1) (English).gba, sha1 c108cec7...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Quetzal (Alpha 9.0) (English).sav - the v0 save (the quetzal fixture's), which v1
  loads as it is: the same RAM and save layout (every RAM literal pool of v0's code maps to the same word in v1's).
boot: native-capture/boot/rhh_splash.txt: on the field, Kanto's Route 1 (Charmander Lv5, 10/19 HP). The party struct is v0's
  but byte 0x1B, whose top three bits v1 sets (not read by the companion).
