captured: 2026-10-14 (headless mgba_dump, by hand; Game Boy Color: wram.bin = C000-DFFF as the bus had it,
  hram.bin = FF80-FFFE, wramx.bin = all 8 WRAM banks, 0x1000 each - the game keeps its state in bank 1)
rom: Pokemon Prism (v0.95 build 254 Hotfix 5) (~/Downloads/Game ROMs & Emulation/gbc/, sha1 75207669...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Prism (v0.95 build 254 Hotfix 5).sav - a new game played headless (the
  user had no Prism save; Crystal's and Crystal Clear's saves both boot to "The save file is corrupted!"): the clock,
  model 1, named Daisy, the camp-out with the father, down the path to the mine cart, which takes the player into
  ACQUA MINES. The story gives no Pokemon there yet, so the game's own TryAddMonToParty (3C:4000) was called once
  (mgba_dump's gbfar) for a Lv5 LARVITAR, and its ReceiveItem (00:12E5) for 10 POKe BALLS, 3 POTIONS, an ANTIDOTE,
  2 ORAN BERRIES and a BICYCLE. The START menu is off at that point of the story (wStatusFlags-like D84C bit 5, which
  also stops wild encounters): it was cleared to walk the cave until a wild SHINX (Lv2) came, caught with the second
  POKe BALL, and to save through the START menu - set again while the save prompt was up, so the save holds the
  game's own D84C (0x20). The game's own code wrote everything else.
boot: native-capture/boot/prism.txt (wait 900, START, A held 6 frames three times: the title, CONTINUE, the save
  summary): in ACQUA MINES (wMapGroup 31, wMapNumber 1 at Crystal's DCB5) at (29, 30).
