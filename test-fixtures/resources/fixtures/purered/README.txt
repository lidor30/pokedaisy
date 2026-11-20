captured: 2026-10-22 (headless mgba_dump, by hand; Game Boy Color: wram.bin = C000-DFFF as the bus has it, wramx.bin = all
  8 WRAM banks, hram.bin = FF80-FFFE)
rom: Pokemon PureRed v2.7.6 (~/Downloads/Game ROMs & Emulation/gbc/Pokemon PureRed (v2.7.6).gb, sha1 2e94d09c...,
  = Vortyne/pureRGB's `make red` at tag v2.7.6 with rgbds 1.0.3) - a Color cart (rgbfix -c): mGBA runs it as a Game Boy
  Color, its COLORS option at OG (the Color's two-tone palettes: white / pink / dark red / black, green sprites).
save: the red fixture's (~/Documents/RetroArch/saves/mGBA/Pokemon - Red Version (USA, Europe) (SGB Enhanced).sav: Nelson,
  8 badges, $999999, six Lv100s) - a retail save, which PureRGB's own updater converts on CONTINUE (PureRed has no save of
  its own; the user's rule: the existing save first).
boot: native-capture/boot/purergb.txt: CONTINUE, the updater ("Original Game", START), NO to its warp to Pallet Town, with
  wCurMapTileset's bit 7 cleared (gbpoke 1 d36f 06) so the map header reloads from the cart. On the field in Vermilion
  City's Pokemon Center: wCurMap (1:D366) 0x59, wYCoord 5, wXCoord 3; wPartyCount (1:D16B) 6; the bag (1:D542) 16 items
  (the updater moved them and took the COIN CASE); 10 owned / 106 seen. The bus maps WRAM bank 1 at D000 here.
