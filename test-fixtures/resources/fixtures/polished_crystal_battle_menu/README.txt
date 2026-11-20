captured: 2026-10-16 (headless mgba_dump, by hand; files as the polished_crystal fixture's)
rom / save: as the polished_crystal fixture (3.2.3).
boot: as the polished_crystal_battle fixture up to the battle menu (wild SENTRET Lv2), then RIGHT, DOWN (one frame, 20
  apart): dumped with the cursor on Run - w2DMenuCursorInitY 14, X 9, 2 x 2, w2DMenuFlags1 0, offsets 0x26,
  wMenuJoypadFilter 0x0D, wMenuCursorY 2, wMenuCursorX 2, a filled ▶ (0xF0) at its tile (15, 16) (wCursorCurrentTile
  CE87 = 0xC2EF). Polished's battle menu is Crystal's 2 x 2 in its own order: Fight (10, 14), <PK><MN> (16, 14), Bag
  (10, 16), Run (16, 16) - the ▶ at column 9 or 15, row 14 or 16 - in a box from (8, 12) to (19, 17), with an empty box
  (0, 12)-(8, 17) beside it. Its shortcuts (wBattleMenuFlags CE95, engine/menus/menu.asm): in a wild battle B moves the
  cursor to Run (without choosing it), and in any battle START picks Bag and SELECT picks <PK><MN>. One-frame presses
  take on every frame here (w2DMenuFlags1 bit 6 is off: one poll a frame).
