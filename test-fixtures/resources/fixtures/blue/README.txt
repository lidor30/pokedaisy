captured: 2026-10-15 (headless mgba_dump, by hand; Game Boy: wram.bin = C000-DFFF, hram.bin = FF80-FFFE)
rom: Pokemon Blue Version (USA, Europe): the user has no Blue cart, so pret/pokered's build was used
  ($DECOMPS/pokered/pokeblue.gbc, sha1 d7037c83..., = retail Blue's per pokered's roms.sha1) - run as a Super Game Boy
  (model 0x20), no border.
save: the red fixture's (~/Documents/RetroArch/saves/mGBA/Pokemon - Red Version (USA, Europe) (SGB Enhanced).sav: Nelson,
  8 badges, six Lv100s), which loads as it is - Red and Blue share the SRAM layout and every WRAM address - except that it
  keeps Red's tileset pointers for the map (below).
boot: native-capture/boot/blue.txt: as red.txt, but with the save's summary up wTilesetBlocksPtr (0xD52C) = AF 53 and
  wTilesetGfxPtr (0xD52E) = AF 4D (Blue's Pokecenter_Block / Pokecenter_GFX, 0x10 before Red's - without them the Pokemon
  Center draws as garbage). On the field in Vermilion City's Pokemon Center (wCurMap 0x59, y 5, x 3), the party / bag /
  money as in the red fixture.
