captured: 2026-10-08 (headless mgba_dump, by hand)
rom / save: as the emerald_ja fixture
boot: as emerald_ja, then a scripted wild battle: B6 1900 05 0000 B7 02 (setwildbattle SPECIES_PIKACHU, 5, no item;
  dowildbattle; end) poked at 0x0203FF00 and `call 0x08098880 0x0203FF00` (ScriptContext_SetupScript), wait 1300,
  A, wait 400: at the action menu (gBattlerControllerFuncs 0x03005AC0 = 0x08057199) - dumped. Then A -> 0x0805780D
  (moves), B, RIGHT, A -> 0x080594F1 (bag), B, LEFT, DOWN, A -> 0x08059439 (party; gPartyMenu 0x0203CB94 set).
