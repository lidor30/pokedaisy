captured: 2026-10-23 (headless mgba_dump)
rom: Pokemon Dreamstone Mysteries (~/Downloads/Game ROMs & Emulation/gba/Pokemon Dreamstone Mysteries.gba, sha1 20e91aec...)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Dreamstone Mysteries.sav (the dreamstone fixture's README)
boot script: native-capture/boot/dreamstone_battle.txt: the field, then a scripted wild ZIGZAGOON Lv5 (setwildbattle 263 5 0
  + a second mon's three zero fields / dowildbattle / end at 0x0203F000, run through ScriptContext_SetupScript 0x081EAD35);
  dumped at the action menu ("What will Torchic do?").
gBattleTypeFlags 4 (IS_MASTER), gBattlersCount 2, gBattleMons[0] TORCHIC 35/35 Lv12 (BLAZE, FIRE, PP 25 30 20 5),
  gBattleMons[1] ZIGZAGOON (PICKUP, holding a POTION; the clock seeds the RNG, so its gender changes between runs);
  gEnemyParty slot 0 the same ZIGZAGOON; gBattlerControllerFuncs[0] = HandleInputChooseAction (0x080561FD). From here
  (scratchpad replays): RIGHT walked the action cursor (0x0200043C) 0 -> 1, DOWN 1 -> 3; FIGHT then RIGHT / DOWN walked the
  move cursor (0x02000440) 0 -> 1 -> 3; a written move cell 1 + A spent QUICK ATTACK's PP (30 -> 29); a written action
  cell 2 + A opened the party menu (0x0805432D; gPartyMenu 0x02031E04), DOWN (slotId 0 -> 2), A, SHIFT sent OSHAWOTT in
  (gBattlerPartyIndexes[0] = 2); the bag in battle waits in 0x080543CD, the move menu in 0x08055329.
