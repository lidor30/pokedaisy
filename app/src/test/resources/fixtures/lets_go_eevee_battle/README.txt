captured: 2026-11-02T10:27:38Z (headless, scripts/capture_fixture_headless.sh)
rom: Pokemon Let's Go Eevee (~/Downloads/Game ROMs & Emulation/gba/Pokemon Let's Go Eevee.gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Let's Go Eevee.sav
boot script: native-capture/boot/lets_go_battle.txt
the battle: lets_go_pikachu's save (see that README), then a wild WOOLOO Lv5 (species 315, the hack's No. 300) by
  setwildbattle / dowildbattle through rev 0's ScriptContext_SetupScript (lets_go_battle.txt), dumped at the action menu
  (PIKACHU Lv10 34/34 vs WOOLOO). WOOLOO's seen bit is set at 0x0203C0C8 alone (seen), not at 0x0203C133 (caught).
  Checked headless from this point: a written action cell 2 + A opened the party menu, a written move cell 1 + A used
  TAIL WHIP; the party menu is a two-column grid (gPartyMenu.slotId: DOWN 0 -> 2 -> 4, RIGHT 0 -> 1), and A, SHIFT on
  slot 3 sent SNOM in.
