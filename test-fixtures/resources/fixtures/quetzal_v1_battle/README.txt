captured: 2026-10-15 (headless mgba_dump, by hand)
rom / save: as the quetzal_v1 fixture.
boot: native-capture/boot/rhh_splash.txt, then a scripted wild battle as quetzal_battle's (setwildbattle SPECIES_PIDGEY, 3;
  dowildbattle: B6 10 00 03 00 00 00 00 00 00 00 B7 02 poked at 0x0203FF00) through v1's ScriptContext_SetupScript
  (`call 0x080DE340 0x0203FF00`), wait 300, A, wait 200: the action menu (Charmander Lv5, 10/19 HP), dumped.
  gBattlerControllerFuncs[0] (0x03005580) read in each menu: action 0x0808DC89, FIGHT 0x0808E911, BAG 0x08090B21,
  POKéMON 0x08090A31.
