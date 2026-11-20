captured: 2026-11-20 (headless mgba_dump; Game Boy Color: wram.bin = C000-DFFF as the bus had it, hram.bin = FF80-FFFE,
  wramx.bin = all 8 WRAM banks, sram.bin = the cart's battery RAM, 4 banks of 0x2000 - where Crystal keeps its PC boxes)
rom: Pokemon - Kristall-Edition (Germany) (~/Downloads/Game ROMs & Emulation/gbc/)
save: the crystal_de fixture's save (~/Documents/RetroArch/saves/mGBA/Pokemon - Kristall-Edition (Germany).sav, copied fresh),
  then, by the game's own functions (gbfar; English's addresses but RemoveMonFromPartyOrBox, 03:6038 here, a byte earlier):
  TryAddMonToParty 03:588C, SendGetMonIntoFromBox 03:5B3F, RemoveMonFromPartyOrBox 03:6038, SaveBox 05:4E0C / LoadBox 05:5021
  around each box change (wCurBox DB72), the box names poked into wBoxNames (DB75), then SaveGameData 05:4B85:
  BOX1 (current): RATTFRATZ 4, WIESOR 5, HOOTHOOT 6, RAUPY 4, HORNLIU 5, WEBARAK 7, LEDYBA 6, KNOFENSA 8;
  WASSER: KARPADOR 10, ENTON 12, QUAPSEL 11, TENTACHA 14, KRABBY 13, GOLDINI 9, MARILL 12;
  HÖHLE: NEBULAK 15, ZUBAT 10, ONIX 14, ABRA 9, TRAUMATO 12; SELTEN (box 8): DRATINI 15, LARVITAR 20, SICHLOR 18, LAPRAS 25;
  party ENDIVIE, TAUBSI, PIKACHU 15, EVOLI 12, VOLTILAMM 10, KLEINSTEIN 11.
dump: that save loaded fresh (native-capture/boot/crystal_de.txt, wait 200), on ROUTE 29.
