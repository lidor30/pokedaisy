captured: 2026-10-11 (headless mgba_dump, by hand)
rom: Pokemon Recharged Yellow v1.9.4 (~/Downloads/Game ROMs & Emulation/gba/Pokemon Recharged Yellow (v1.9.4).gba, sha1 7d9fae6c...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Recharged Yellow (v1.8.0).srm (the only save; every later release loads it as is)
boot: as `recharged_yellow_v194`, then a scripted wild battle: B6 1300 03 0000 B7 02 (setwildbattle RATTATA Lv3; dowildbattle;
  end) poked at 0x0203FF00 and `call 0x081A56DD 0x0203FF00` (its ScriptContext_SetupScript, the function whose
  literal pool holds gScriptCmdTable and stores r0 as the script pointer), wait 300, A, wait 200: the action
  menu ("What will WARTORTLE do?"), dumped.
