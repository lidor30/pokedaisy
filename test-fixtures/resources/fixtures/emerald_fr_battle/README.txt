captured: 2026-10-08 (headless mgba_dump, by hand)
rom / save: as the emerald_fr fixture
boot: as emerald_fr, then a scripted wild battle: B6 1900 05 0000 B7 02 (setwildbattle SPECIES_PIKACHU, 5, no item;
  dowildbattle; end) poked at 0x0203FF00 and `call 0x08098F08 0x0203FF00` (ScriptContext_SetupScript), wait 1300, A,
  wait 400: at the action menu (KADABRA vs PIKACHU) - dumped. One A more opens the move menu; RIGHT, A the bag;
  LEFT, DOWN, A the party - each at the config's handler address.
