IWRAM captures for ShopWatch (GitHub #41), headless (mgba_dump), from the retail Emerald and FireRed rev 1 saves.
A Poké Mart clerk's script started with ScriptContext_SetupScript (Emerald 0x08098EF8, FireRed 0x08069AF8) on
Oldale Town's clerk (Emerald 0x081FC240) / Two Island's (FireRed 0x08164A18), then:
  *_menu  - the BUY / SELL / QUIT window over the field
  *_buy   - the buy screen (Emerald callback2 0x080DFD65, FireRed 0x0809ADF9)
  *_sell  - the sell bag (Emerald 0x081AAD5D, FireRed 0x08107F59)
  *_after - back on the field after "Please come again!" (the script has ended: scriptPtr 0)
In the first three the global context's scriptPtr sits just past the clerk's `pokemart` (0x86 + the item list).
