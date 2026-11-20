captured: 2026-11-02 (headless native-capture/nds_dump, by hand; mainram.bin = the DS's 4 MB main RAM at 0x02000000,
  zeroed but for what Gen4Reader reads: 0x021D1000-0x021D3FFF, the SaveData, the FieldSystem, its process manager and
  child (the battle's), gOverlayTemplate_Battle (0x020F70B8), the battle manager's pointer (0x02255BAC), the manager,
  its BattleSetup and active slots, every battle object and the party record it points at, and both parties).
rom: Pokemon Origin HeartGold (v4.0.3) (EN 1.0.0-rc8) (~/Downloads/Game ROMs & Emulation/nds/, sha1 a54a6475...)
save: origin_heartgold's new game (its README), from before its FLY: then a field script, WildBattle (589) PIDGEY Lv50,
  wait 760; POKEMON (touch 215 172), DRAGAPULT (touch 192 135), SHIFT (touch 128 70), wait 900; FIGHT (touch 128 70),
  AGILITY (touch 64 120, held 6), wait 900. dump: at the commands, DRAGAPULT out with its Speed +2, PIDGEY Lv50.
