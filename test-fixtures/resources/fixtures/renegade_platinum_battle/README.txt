captured: 2026-11-06 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000,
  zeroed but for what Gen4Reader reads in a battle: gBattleApplicationTemplate (0x020EA358), 0x021C0000-0x021C0FFF
  (sSaveDataPtr, sFieldSystem), the SaveData at 0x0227E19C (+0x21000), and each range it reads of the FieldSystem, its
  process manager, the battle's ApplicationManager, the BattleSystem, BattleContext.battleMons and the battle's parties,
  rounded to 16 bytes).
rom: Pokemon Renegade Platinum (v1.3.0).nds (~/Downloads/Game ROMs & Emulation/nds/, sha1 d4ee068b..., Renegade Platinum v1.3.0).
save: the renegade_platinum fixture's (booted the same way), then a wild battle started by the game's own
  StartWildBattle script command (292: CLEFAIRY, Lv 5) from the player's room, run by nds_dump's `scriptgo`; dumped at
  the action menu ("What will Turtwig do?"), TURTWIG Lv 5 21/21 against CLEFAIRY Lv 5. Its FIGHT screen listed Tackle
  NORMAL 35/35, Withdraw WATER 40/40, Absorb GRASS 25/25. The battle's type chart, read at 0x021E9F80 (overlay 21), was
  byte for byte the cart's.
