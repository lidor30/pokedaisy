captured: 2026-10-15 (headless mgba_dump, by hand)
rom / save: as the quetzal_es_v1 fixture.
boot: native-capture/boot/rhh_splash.txt, then the quetzal_v1_battle fixture's scripted wild PIDGEY (Lv3) through v1 Spanish's
  ScriptContext_SetupScript (`call 0x080DE358 0x0203FF00`), wait 300, A, wait 500 (GYARADOS' Intimidate message): the
  action menu (LUCHA / BOLSA / POKéMON / HUIDA), dumped. gBattlerControllerFuncs[0] read in each menu: action 0x0808DC65,
  FIGHT 0x0808E8ED, BAG 0x08090AFD, POKéMON 0x08090A0D - English v1's, 0x24 earlier.
