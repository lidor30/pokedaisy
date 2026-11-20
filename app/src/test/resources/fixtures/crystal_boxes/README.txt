captured: 2026-11-20 (headless mgba_dump, native-capture/boot/crystal_boxes.txt; Game Boy Color: wram.bin = C000-DFFF
  as the bus had it, hram.bin = FF80-FFFE, wramx.bin = all 8 WRAM banks, sram.bin = the cart's battery RAM, 4 banks
  of 0x2000 - where Crystal keeps its PC boxes)
rom: Pokemon - Crystal Version (USA, Europe) (Rev 1) (~/Downloads/Game ROMs & Emulation/gbc/)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon - Crystal Version (USA, Europe) (Rev 1).sav (the `crystal` fixture's
  new game), copied fresh for the run: mgba_dump writes the save back, and a second run on top of a first one stacks.
what was done, all by the game's own functions (gbfar), none of it poked as box bytes:
  - four Pokémon added to the party with TryAddMonToParty (03:588C), then three sent to BOX 1 / BOX 8 with
    SendGetMonIntoFromBox (03:5B3F) and RemoveMonFromPartyOrBox (03:6039), switching wCurBox through SaveBox / LoadBox:
    BOX 1 = MAGIKARP Lv5, GEODUDE Lv8 (SRAM bank 2), BOX 8 = HOOTHOOT Lv4, ZUBAT Lv6 (bank 3);
  - BOX 8 renamed TEAM in wBoxNames (WRAM bank 1, DBB4);
  - BOX 3 left as the current box (wCurBox 2) with a PIDGEY Lv3 in it: sBox (bank 1, AD10) holds it, its place in
    bank 2 still holds the empty box the game loaded.
party after it: CHIKORITA, PIDGEY, PIKACHU, EEVEE.
