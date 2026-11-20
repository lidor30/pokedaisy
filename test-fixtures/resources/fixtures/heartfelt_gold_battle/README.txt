captured: 2026-11-16 (headless native-capture/nds_dump, by hand; mainram.bin zeroed but for what Gen4Reader read of it in
  the battle: the SaveData, the FieldSystem, its process manager and the battle's, the BattleSystem, BattleContext's
  battlers and the parties, each range rounded to 16 bytes).
rom: Pokemon Heartfelt Gold (v1.0.15).nds (~/Downloads/Game ROMs & Emulation/nds/, sha1 b2b27aeb...).
save: heartfelt_gold's, booted the same way, B to close the menu; PC anywhere (`action 1`) opened the storage system and
  was backed out of; then a wild battle by the game's own WildBattle script command (589: PIDGEY Lv5), nds_dump's
  `scriptgo`, wait 900. dump: at the commands, "What will Torchic do?", TORCHIC Lv7 23/23 against PIDGEY Lv5 (HeartGold's
  own battle screens; pk_nds_battle_command 5).
