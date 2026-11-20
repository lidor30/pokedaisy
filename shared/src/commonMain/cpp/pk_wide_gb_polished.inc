/* GPL-3.0. Polished Crystal v3.2.3 object adapter for upstream wide renderer.
 * Source: macros/ram.asm, home/map_objects.asm, engine/overworld/overworld.asm,
 * engine/overworld/player_object.asm, engine/gfx/dynamic_pals.asm.
 * Reads actual map objects/ROM graphics; never writes emulated RAM/VRAM.
 */
struct pc_wide_sprite {
  unsigned char state, tiles[192];
};
static struct pc_wide_sprite pcWideSprites[0xef];
static void pcWideSpriteCacheReset(void) {
  memset(pcWideSprites, 0, sizeof pcWideSprites);
}
static bool pcWideObjectNow(int tod, unsigned flag) {
  // CheckObjectFlag: FFFF always; other FFxx hidden. Bound EventFlagAction.
  if (flag != 0xffff &&
      (flag >= 2303 ||
       (gbRam(gbCfg[GB_EVENT_FLAGS] + flag / 8) & (1u << (flag % 8)))))
    return false;
  int time = gbRam(gbCfg[GB_TIME_OF_DAY]);
  return tod == 0xff || (time < 4 && (tod & (1u << time)));
}
static bool pcWideLoadedAt(const struct GbSnap *s, int sprite, int mx, int my) {
  // map_setup.asm preserves walkers across connections and converts InitX/Y.
  // Their actual position wins over a template at their original position.
  for (int i = 1; i < 13; i++) {
    const uint8_t *o = s->objs + 40 * i;
    if (o[0] && o[0] == sprite && o[0x14] == (uint8_t)mx &&
        o[0x15] == (uint8_t)my)
      return true;
  }
  return false;
}
static void pcWideMapObject(const struct GbSnap *s, const uint8_t *m, int x,
                            int y, int fromOam) {
  int sprite = m[1];
  for (int i = 0; i < 4 && sprite >= 0xf5; i++)
    sprite = s->vars[sprite - 0xf5];
  // Dynamic monster/decor/form sprites and special large objects require
  // their own game routines; do not draw an unrelated generic stand-in.
  if (!sprite || sprite > 0xca || gbOamHas(x, y, gbNObjs))
    return;
  (void)fromOam;
  // Ordinary NPC/standing-item movement only. Special object actions choose
  // additional frames/geometry and must not be approximated by a person.
  if (m[4] > 11 && m[4] != 0x18 && m[4] != 0x19)
    return;
  const uint8_t *header = gbRom(gbCfg[GB_SPRITE_DATA] + 4u * (sprite - 1), 4);
  const uint8_t *move = gbRom(gbCfg[GB_MOVE_DATA] + 6u * m[4], 6);
  if (!header || !move || (header[3] >> 6) >= 3 || (move[3] & 0x81) ||
      (move[5] & 0x80))
    return;
  int pal = m[6] ? m[6] - 1 : header[3] & 0x3f, slot = -1;
  for (int i = 0; i < 8; i++)
    if (s->loadedPals[i] == pal) {
      slot = i;
      break;
    }
  // Use the game's currently applied time/weather-corrected palette only.
  if (slot < 0)
    return;
  struct pc_wide_sprite *cache = &pcWideSprites[sprite];
  if (!cache->state) {
    uint8_t decoded[1024];
    uint32_t at = gbLinear(header[2], header[0] | header[1] << 8);
    int bytes = gbDecode(gbB->memory.rom, gbB->memory.romSize, at, decoded,
                      sizeof decoded);
    int need = (header[3] >> 6) == 2 ? 64 : 192;
    cache->state = bytes >= need ? 1 : 2;
    if (cache->state == 1)
      memcpy(cache->tiles, decoded, (size_t)need);
  }
  if (cache->state != 1)
    return;
  int face = (header[3] >> 6) == 2 ? 0 : move[1] & 3;
  int first = gbNObjs;
  gbAddStanding(x, y, face, 0, slot, 0);
  for (int i = first; i < gbNObjs; i++)
    gbObjectRomTiles[i] = cache->tiles + 16 * gbObjs[i].tile;
}
static void pcWideObjects(const struct GbSnap *s, int ox, int oy, int fromOam) {
  for (int i = 1; i < 21; i++) {
    const uint8_t *m = s->mapObjs + 16 * i;
    if (m[0] != 0xff || s->masks[i] || pcWideLoadedAt(s, m[1], m[3], m[2]) ||
        !pcWideObjectNow(m[7], m[12] | m[13] << 8))
      continue;
    pcWideMapObject(s, m, 16 * m[3] + 32 - ox, 16 * m[2] + 28 - oy, fromOam);
  }
  for (int d = 0; d < 4; d++) {
    const struct GbConn *c = s->conn + d;
    for (int i = 0; c->on && i < c->nObjs; i++) {
      const uint8_t *event = gbRom(c->objs + 13u * i, 13);
      if (!event || !pcWideObjectNow(event[6], event[11] | event[12] << 8))
        continue;
      if (pcWideLoadedAt(s, event[0], 2 * c->offX + event[2] - 6,
                         2 * c->offY + event[1] - 6))
        continue;
      uint8_t m[14] = {0xff};
      memcpy(m + 1, event, 13);
      pcWideMapObject(s, m, 32 * c->offX + 16 * (event[2] - 4) - ox,
                      32 * c->offY + 16 * (event[1] - 4) - 4 - oy, fromOam);
    }
  }
}
