captured: 2026-11-14 (headless native-capture/nds_dump, driven frame by frame; mainram.bin zeroed but for what SHINY ODDS
  reads: sFieldSystem 0x021C07DC, gBattleApplicationTemplate's init word 0x020EA358, the FieldSystem's process manager word,
  the process manager's parent / child, the child ApplicationManager (template, execState, procState, args, data), the
  FieldBattleDTO's battle type and party pointers, and the foe's Party (capacity, count, two records).
rom: Pokemon - Platinum Version (USA) (Rev 1) (CPUE rev 1, sha1 0862ec35...)
save: none - a new game (boy, named AA, rival named by default) to the bedroom, then a field script run as the PC job runs
  its own (LockAll; GivePokemon PIPLUP Lv10; StartWildBattle STARLY Lv3; ReleaseAll; End): dumped on the first frame the
  battle's ApplicationManager is the field's child with its data still NULL (frame 108 after the script started) - the
  window SHINY ODDS acts in. The foe, a STARLY (personality 5A6188AC, shiny value 4569), is as the encounter made it.
