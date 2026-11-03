captured: 2026-11-02T10:21:17Z (headless, scripts/capture_fixture_headless.sh)
rom: Pokemon Let's Go Pikachu (~/Downloads/Game ROMs & Emulation/gba/Pokemon Let's Go Pikachu.gba)
save: ~/Documents/RetroArch/saves/mGBA/Pokemon Let's Go Pikachu.sav
boot script: native-capture/boot/lets_go.txt
the save: the user has none, and retail FireRed's is "deleted" by it ("The save file has been deleted..."), so it is a new
  game played headless in Let's Go Pikachu with mgba_dump (2026-11-02): the intro (name "A", rival "AAAA") into the
  player's room in PALLET TOWN; then a script poked into free EWRAM (0x0203F800) and run with `call 0x08069AE5 <addr>`
  (FireRed rev 0's ScriptContext_SetupScript, kept by the hack): givemon PIKACHU 10 / EEVEE 9 / FALINKS 12 holding a
  POTION / SNOM 7 / FUECOCO 11, setflag 0x828 / 0x829 (the POKéMON / POKéDEX menus), additem POTION x5, POKé BALL x10,
  ULTRA BALL x2, RARE CANDY x3, TM01 x1, givemoney 3000 (6000 in all); saved through the START menu's SAVE (the save
  needs ~1200 frames). The same file loads in all four builds (Pikachu, Eevee and their Translation Only builds), copied
  under each one's name. The game's own screens: its party screen PIKACHU Lv10 34/34, EEVEE Lv9 31/31, FALINKS Lv12
  41/41, SNOM Lv7 21/21, FUECOCO Lv11 38/38; its save screen POKéDEX 2 (Kanto's: PIKACHU, EEVEE). Its givemon gave
  some moves odd PP (PIKACHU's THUNDERSHOCK 4/30, DOUBLE KICK 0/30 on its FIGHT screen too).
