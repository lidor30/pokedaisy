captured: 2026-10-15 (headless mgba_dump, by hand)
rom / save / boot: as soulgold_card, with everything the card shows: all eight badges (flags 0x993..), FLAG_SYS_POKEDEX_GET (0x98D) and the National Dex (SaveBlock2 +0x22 = 0xDA, var 0x4046 = 0x302, flag 0x9C2), all 1025 caught, the HALL OF FAME 123:45:06, a frontier record, every achievement, link wins / losses, trades, pokéblocks, contests (game stats), battle points (SaveBlock2 +0xA96), money 9,999,999 and 999:59 played poked in through gSaveBlock1Ptr (0x030040C4) / gSaveBlock2Ptr (0x030040C0)
  in the same run (the save blocks and the key move on every boot), then the card opened (4x DOWN once the POKéDEX is
  in the START menu) and dumped. TrainerCardTest's render of this dump matched the game's frame pixel for pixel.
