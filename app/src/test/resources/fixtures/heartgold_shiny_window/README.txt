captured: 2026-11-14 (headless native-capture/nds_dump, driven frame by frame; mainram.bin zeroed but for what SHINY ODDS
  reads: sFieldSystem 0x021D4158, gOverlayTemplate_Battle's init word 0x020FA484, the FieldSystem's process manager word,
  the process manager's parent / child, the child OverlayManager (template, exec_state, proc_state, args, data), the
  BattleSetup's battle type and party pointers, and the foe's Party (capacity, count, two records).
rom: Pokemon - HeartGold Version (USA) (IPKE rev 0, sha1 4fcded0e...)
save: none - a new game (boy, named AAAAAAA) to the bedroom, then a field script run as the PC job runs its own (LockAll;
  GiveMon CYNDAQUIL Lv10; WildBattle PIDGEY Lv3 not shiny; ReleaseAll; End): dumped on the first frame the battle's
  OverlayManager is the field's child with its data still NULL (frame 135) - the window SHINY ODDS acts in. The foe, a
  PIDGEY (personality 7F258347), is as the encounter made it.
