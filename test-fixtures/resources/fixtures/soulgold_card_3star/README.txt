captured: 2026-10-15 (headless mgba_dump, by hand)
rom / save / boot: as soulgold_card, with three stars: the HALL OF FAME (game stats 10 / 1, SaveBlock1 +0x2144 XOR the key), every regional entry but the legendaries caught (SaveBlock1 +0x3279), a frontier record of 50 (SaveBlock2 +0x8B4) and 79 achievements (SaveBlock1 +0x346C), one short of the fourth star poked in through gSaveBlock1Ptr (0x030040C4) / gSaveBlock2Ptr (0x030040C0)
  in the same run (the save blocks and the key move on every boot), then the card opened (4x DOWN once the POKéDEX is
  in the START menu) and dumped. TrainerCardTest's render of this dump matched the game's frame pixel for pixel.
