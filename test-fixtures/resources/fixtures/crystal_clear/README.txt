captured: 2026-10-14 (headless mgba_dump, by hand; Game Boy Color: wram.bin = C000-DFFF as the bus had it,
  hram.bin = FF80-FFFE, wramx.bin = all 8 WRAM banks, 0x1000 each - the game keeps its state in bank 1)
rom: Pokemon Crystal Clear (v2.6.0) (~/Downloads/Game ROMs & Emulation/gbc/, sha1 84d5c492...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Crystal Clear (v2.6.0).sav - a new game played headless in v2.6.0
  (the user had no Crystal Clear save, and the Crystal fixtures' save doesn't fit: Crystal Clear moved the map ids,
  so it boots on a broken map with WRAM overwritten): NORMAL mode, NEW BARK TOWN, the CHRIS model, MALE, the default
  RED PACK / POKeGEAR, named CHRIS; BULBASAUR from the starter list (it comes holding a MIRACLE SEED); MOM's battle
  downstairs lost (the crystal_clear_trainer fixture), then a wild SENTRET caught on ROUTE 29 with one of the 5 POKe
  BALLs the game gives; saved through the START menu. Nothing poked. The same file is under each build's name there
  (v2.6.0 Color Filter, v2.5.11 Standard Colors and Color Filter): it loads in all four as it is.
boot: native-capture/boot/crystal.txt (wait 600, START, A held 3 frames four times: the title, CONTINUE, the save
  summary): on ROUTE 29 (wMapGroup 24, wMapNumber 3 at DA3A) at (45, 11), in the grass.
