captured: 2026-10-15 (headless mgba_dump, by hand)
rom / save / boot: as lazarus_card, with everything the card shows poked in through gSaveBlock1Ptr (0x03003664) /
  gSaveBlock2Ptr (0x03003668) before opening it: all eight badge flags (0x867..), FLAG_SYS_POKEDEX_GET (0x861) and the
  National Dex (SaveBlock2 +0x1E = 0xDA, var 0x4046 = 0x302, flag 0x896), every caught flag (SaveBlock1 +0x3329), the five
  museum paintings (+0x3014), the frontier symbols (flags 0x8C4..0x8D1), flags 0x29 / 0x4A6, game stats (SaveBlock1
  +0x1618, XOR the key) for the HALL OF FAME, link wins / losses, trades, pokéblocks and contests, battle points
  (SaveBlock2 +0xEBE), money and play time. The game drew the gold card with four stars; TrainerCardTest's render matched
  its frames (front and back) pixel for pixel.
