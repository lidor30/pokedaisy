/* The companion's D-pad cursor and the HUD cursor, as linux/main.c has them (moved, not rewritten):
 * textually included by pd_libretro.c, which defines the key enum, canvas and focus state first. */
/* The D-pad's cursor over the companion. Kept by hit id, since every draw makes
 * the hit list anew: a press that leaves the screen as it was keeps it where it
 * is (the nearest item if that one went), and a new screen (a tab, a summary, a
 * pick-list, a dex entry) puts it on that screen's start (pd_ui.startId: a
 * pick-list's current choice, a confirm's CANCEL), else its first item. */
static int focusId = -1;
static struct pd_hit focusAt;
struct screen {
  int tab, overlay, summarySlot, dexOpen;
};
static struct screen lastScreen = {-1, -1, -1, -1};
static struct screen screen_of(const struct pd_ui *u) {
  return (struct screen){u->tab, u->overlay, u->summarySlot, u->dexOpen};
}
static void focus_new_screen(void) { lastScreen.tab = -1; }
// What the cursor can land on: not the tab bar, nor a scrim or a list's drag area.
static bool focusable(int id) {
  return !(id >= HIT_TAB && id < HIT_TAB + PD_TAB_COUNT) && id != HIT_FF && id != HIT_SCRIM &&
         id != HIT_BAG_LIST && id != HIT_DEX_LIST && id != HIT_GUIDE_LIST && id != HIT_SETTINGS_LIST;
}
// A window over the screen (the guide's notice, a picker, PLACES) starts with its scrim: the cursor stays on what
// comes after it - the screen under the window can't be reached (or pressed) while it's open.
static int modal_floor(const struct pd_ui *u) {
  for (int i = 0; i < u->hitCount; i++)
    if (u->hits[i].id == HIT_SCRIM)
      return i;
  return 0;
}
static int focus_index(const struct pd_ui *u, int id) {
  for (int i = modal_floor(u); i < u->hitCount; i++)
    if (u->hits[i].id == id)
      return i;
  return -1;
}
// The shared map is a touch target. Linux keeps a tile cursor over that same
// target, using the shared player/section layouts rather than another grid.
static struct {
  bool ready;
  unsigned rom;
  int page, requestedPage, tx, ty, sel, dungeon;
  int minX, minY, maxX, maxY;
} mapCursor;
static void map_cursor_sync(const struct pd_ui *u, const struct pd_game *g,
                            const struct pd_snapshot *s) {
  if (u->tab != PD_TAB_MAP) {
    mapCursor.ready = false;
    return;
  }
  const struct pd_map_art *art = pd_map_art(g);
  if (!art) {
    mapCursor.ready = false;
    return;
  }
  if (focus_index(u, HIT_MAP) < 0)
    return;
  int page = 0, tx = PD_MAP_W / 16, ty = PD_MAP_H / 16;
  bool player = pd_map_player_tile(g, s, &page, &tx, &ty);
  int shown = u->mapPage >= 0 ? u->mapPage : player ? page : 0;
  if (shown >= art->pages) shown = 0;
  const struct pd_region_layout *layouts = g->kind == PD_GAME_EMERALD ?
      pd_layouts_emerald : pd_layouts_firered;
  int pages = g->kind == PD_GAME_EMERALD ? pd_layouts_emerald_count : pd_layouts_firered_count;
  if (shown < 0 || shown >= pages) {
    mapCursor.ready = false;
    return;
  }
  const struct pd_region_layout *layout = &layouts[shown];
  mapCursor.minX = layout->offX;
  mapCursor.minY = layout->offY;
  mapCursor.maxX = layout->offX + layout->w - 1;
  mapCursor.maxY = layout->offY + layout->h - 1;
  if (mapCursor.ready && mapCursor.rom == g->loadId && mapCursor.page == shown &&
      mapCursor.requestedPage == u->mapPage &&
      mapCursor.sel == u->mapSel && mapCursor.dungeon == u->mapDungeon)
    return;
  if (!player || page != shown) {
    tx = PD_MAP_W / 16;
    ty = PD_MAP_H / 16;
  }
  if (u->mapDungeon >= 0) {
    tx = u->mapSelTx;
    ty = u->mapSelTy;
  } else if (u->mapSel >= 0) {
    struct pd_tile_rect r;
    if (pd_map_tiles(g, u->mapSel, &page, &r, 1) && page == shown) {
      tx = r.x + (r.w - 1) / 2;
      ty = r.y + (r.h - 1) / 2;
    }
  }
  mapCursor.ready = true;
  mapCursor.rom = g->loadId;
  mapCursor.page = shown;
  mapCursor.requestedPage = u->mapPage;
  mapCursor.tx = tx < mapCursor.minX ? mapCursor.minX : tx > mapCursor.maxX ? mapCursor.maxX : tx;
  mapCursor.ty = ty < mapCursor.minY ? mapCursor.minY : ty > mapCursor.maxY ? mapCursor.maxY : ty;
  mapCursor.sel = u->mapSel;
  mapCursor.dungeon = u->mapDungeon;
}
static bool map_cursor_active(const struct pd_ui *u) {
  return mapCursor.ready && u->tab == PD_TAB_MAP &&
         u->overlay == PD_OVERLAY_NONE && focusId == HIT_MAP;
}
// B leaves the grid for PLACES / ME / region buttons; another B is normal Back.
static bool map_cursor_back(struct pd_ui *u) {
  int i = focus_index(u, HIT_MAP_PLACES);
  if (!map_cursor_active(u) || i < 0)
    return false;
  focusId = HIT_MAP_PLACES;
  focusAt = u->hits[i];
  return true;
}
static void focus_activate(struct pd_ui *u) {
  int at = focus_index(u, focusId);
  if (at < 0)
    return;
  const struct pd_hit *h = &u->hits[at];
  if (map_cursor_active(u)) {
    // Call the map's existing selection directly: overlaid map buttons must
    // not intercept a tile in the top-right corner of the grid.
    u->touchX0 = h->x + (mapCursor.tx * 8 + 4) * h->w / PD_MAP_W;
    u->touchY0 = h->y + (mapCursor.ty * 8 + 4) * h->h / PD_MAP_H;
    ui_map_act(u, HIT_MAP);
    mapCursor.sel = u->mapSel;
    mapCursor.dungeon = u->mapDungeon;
    mapCursor.requestedPage = u->mapPage;
  } else {
    pd_ui_touch(u, h->x + h->w / 2, h->y + h->h / 2, true);
    pd_ui_touch(u, h->x + h->w / 2, h->y + h->h / 2, false);
  }
}
// After a draw: where the cursor is now (an index into u->hits, -1 = none).
static int focus_resolve(struct pd_ui *u) {
  struct screen now = screen_of(u);
  bool changed = memcmp(&now, &lastScreen, sizeof now) != 0;
  lastScreen = now;
  int floor = modal_floor(u);
  int i = changed ? -1 : focus_index(u, focusId);
  if (i >= 0 && i < floor)
    i = -1;
  if (i < 0 && !changed && focusId >= 0) { // gone: the nearest item to where it was
    int best = 1 << 30;
    for (int j = floor; j < u->hitCount; j++) {
      const struct pd_hit *h = &u->hits[j];
      int dx = h->x + h->w / 2 - (focusAt.x + focusAt.w / 2),
          dy = h->y + h->h / 2 - (focusAt.y + focusAt.h / 2);
      if (focusable(h->id) && dx * dx + dy * dy < best)
        best = dx * dx + dy * dy, i = j;
    }
  }
  if (i < 0 && u->startId >= 0)
    i = focus_index(u, u->startId);
  if (i >= 0 && i < floor)
    i = -1;
  for (int j = floor; i < 0 && j < u->hitCount; j++)
    if (focusable(u->hits[j].id))
      i = j;
  focusId = i >= 0 ? u->hits[i].id : -1;
  if (i >= 0)
    focusAt = u->hits[i];
  return i;
}
/* The list window (a scrolled list's own hit) the hit [h] is in, -1 = none. */
static int list_of(const struct pd_ui *u, const struct pd_hit *h) {
  int cx = h->x + h->w / 2, cy = h->y + h->h / 2;
  for (int j = modal_floor(u); j < u->hitCount; j++) {
    const struct pd_hit *l = &u->hits[j];
    bool window = l->id == HIT_BAG_LIST || l->id == HIT_DEX_LIST || l->id == HIT_GUIDE_LIST ||
                  l->id == HIT_SETTINGS_LIST || (l->id == HIT_SCRIM && l->scroll);
    if (window && l != h && cx >= l->x && cx < l->x + l->w && cy >= l->y && cy < l->y + l->h)
      return j;
  }
  return -1;
}
static bool centre_in(const struct pd_hit *l, const struct pd_hit *t) {
  int cx = t->x + t->w / 2, cy = t->y + t->h / 2;
  return cx >= l->x && cx < l->x + l->w && cy >= l->y && cy < l->y + l->h;
}
/* The scroll of the list on screen (the draw clamps it to the list's length), NULL = none. */
static int *list_scroll(struct pd_ui *u) {
  if (u->overlay == PD_OVERLAY_PLACES)
    return &u->placesScroll;
  if (u->overlay != PD_OVERLAY_NONE)
    return NULL;
  return u->tab == PD_TAB_BAG ? &u->bagScroll : u->tab == PD_TAB_DEX ? &u->dexScroll : u->tab == PD_TAB_GUIDE ? &u->guideScroll
         : u->tab == PD_TAB_SETTINGS ? &u->settingsScroll : NULL;
}
/* A move that found nothing and scrolled the list instead: after the next draw, the row it brought into view. */
static int focusRetry = -1;
static bool focus_step(struct pd_ui *u, int direction, bool scroll);
static void focus_move(struct pd_ui *u, int direction) {
  focusRetry = -1;
  if (!focus_step(u, direction, true))
    focusRetry = direction;
}
/* After a draw: a scrolled move's second half, onto the row now shown (no further scroll). */
static int focus_after_draw(struct pd_ui *u, int at) {
  if (focusRetry < 0)
    return at;
  int d = focusRetry;
  focusRetry = -1;
  focus_step(u, d, false);
  return focus_index(u, focusId);
}
/* The cursor's step; false when there was nothing that way and the list scrolled one row instead. */
static bool focus_step(struct pd_ui *u, int direction, bool scroll) {
  if (map_cursor_active(u)) {
    if (direction == LEFT && mapCursor.tx > mapCursor.minX) mapCursor.tx--;
    if (direction == RIGHT && mapCursor.tx < mapCursor.maxX) mapCursor.tx++;
    if (direction == UP && mapCursor.ty > mapCursor.minY) mapCursor.ty--;
    if (direction == DOWN && mapCursor.ty < mapCursor.maxY) mapCursor.ty++;
    focus_activate(u); // Name the location immediately, like the game's map.
    return true;
  }
  int cur = focus_index(u, focusId);
  if (cur < 0) {
    focus_resolve(u);
    return true;
  }
  const struct pd_hit *h = &u->hits[cur];
  int best = -1, score = 1000000;
  for (int i = modal_floor(u); i < u->hitCount; i++) {
    if (i == cur)
      continue;
    struct pd_hit *t = &u->hits[i];
    if (t->id == HIT_SCRIM || t->id == HIT_BAG_LIST || t->id == HIT_DEX_LIST || t->id == HIT_GUIDE_LIST ||
        t->id == HIT_SETTINGS_LIST)
      continue;
    int dx = (t->x + t->w / 2) - (h->x + h->w / 2),
        dy = (t->y + t->h / 2) - (h->y + h->h / 2);
    int along = direction == LEFT    ? -dx
                : direction == RIGHT ? dx
                : direction == UP    ? -dy
                                     : dy;
    // Across: the gap between the two along the other axis, 0 where they overlap (a full-width row right
    // under a button beats a chip far off to the side).
    int across = direction == LEFT || direction == RIGHT
                     ? (t->y + t->h <= h->y ? h->y - (t->y + t->h) : t->y >= h->y + h->h ? t->y - (h->y + h->h) : 0)
                     : (t->x + t->w <= h->x ? h->x - (t->x + t->w) : t->x >= h->x + h->w ? t->x - (h->x + h->w) : 0);
    // What lines up with the cursor on the other axis comes first (the next chip in a tab bar, not the slot
    // just above it); an item off to the side is only reached where nothing lines up.
    int cost = along + across * 4 + (across > 0 ? 100000 : 0);
    if (along > 0 && cost < score) {
      best = i;
      score = cost;
    }
  }
  // In a list, a target past the list's window (the tab bar under it) waits until the list has scrolled to its end:
  // the list scrolls one row instead, and after the next draw the cursor goes onto the row that came into view (or,
  // with the list at its end and nothing new, on past it).
  int list = list_of(u, h);
  bool vertical = direction == UP || direction == DOWN;
  bool leaves = list >= 0 && (best < 0 || !centre_in(&u->hits[list], &u->hits[best]));
  if (scroll && vertical && (best < 0 || leaves)) {
    int *at = list_scroll(u);
    if (at) {
      int d = h->h > 0 && h->h <= 48 ? h->h : 18; // one row: the cursor's own
      *at += direction == DOWN ? d : -d;
      return false;
    }
  }
  if (best >= 0) {
    focusId = u->hits[best].id;
    focusAt = u->hits[best];
  }
  return true;
}
static void focus_draw(struct pd_ui *u, int focus) {
  if (focus < 0 || focus >= u->hitCount)
    return;
  struct pd_hit *h = &u->hits[focus];
  struct pd_hit tile;
  if (map_cursor_active(u)) {
    int x = mapCursor.tx * 8, y = mapCursor.ty * 8;
    tile = (struct pd_hit){h->x + x * h->w / PD_MAP_W,
                          h->y + y * h->h / PD_MAP_H,
                          (x + 8) * h->w / PD_MAP_W - x * h->w / PD_MAP_W,
                          (y + 8) * h->h / PD_MAP_H - y * h->h / PD_MAP_H,
                          HIT_MAP, false};
    h = &tile;
    pd_fill(&canvas, h->x - 1, h->y - 1, h->w + 2, 1, 0x202828);
    pd_fill(&canvas, h->x - 1, h->y + h->h, h->w + 2, 1, 0x202828);
    pd_fill(&canvas, h->x - 1, h->y, 1, h->h, 0x202828);
    pd_fill(&canvas, h->x + h->w, h->y, 1, h->h, 0x202828);
  }
  if (h->id >= HIT_SLOT && h->id < HIT_SLOT + PD_PARTY_SIZE)
    return; // a party slot shows its own selected look (pd_ui.focusId)
  pd_fill(&canvas, h->x, h->y, h->w, 2, 0xff7331);
  pd_fill(&canvas, h->x, h->y + h->h - 2, h->w, 2, 0xff7331);
  pd_fill(&canvas, h->x, h->y, 2, h->h, 0xff7331);
  pd_fill(&canvas, h->x + h->w - 2, h->y, 2, h->h, 0xff7331);
}
/* The HUD hotkey's cursor: the target to the [direction] of [cur] among where the HUD put them (hud_hits, in HUD
 * canvas pixels) - what lines up on the other axis first, as focus_move does; -1 = nowhere that way. */
static int hud_step(const struct hud_hits *hh, int cur, int direction) {
  int from = -1, best = -1, score = 1 << 30;
  for (int i = 0; i < hh->n; i++)
    if (hh->at[i].id == cur)
      from = i;
  if (from < 0)
    return hh->n ? hh->at[0].id : -1;
  const typeof(hh->at[0]) *h = &hh->at[from];
  for (int i = 0; i < hh->n; i++) {
    if (i == from)
      continue;
    const typeof(hh->at[0]) *t = &hh->at[i];
    int dx = (t->x + t->w / 2) - (h->x + h->w / 2), dy = (t->y + t->h / 2) - (h->y + h->h / 2);
    int along = direction == LEFT ? -dx : direction == RIGHT ? dx : direction == UP ? -dy : dy;
    int across = direction == LEFT || direction == RIGHT
                     ? (t->y + t->h <= h->y ? h->y - (t->y + t->h) : t->y >= h->y + h->h ? t->y - (h->y + h->h) : 0)
                     : (t->x + t->w <= h->x ? h->x - (t->x + t->w) : t->x >= h->x + h->w ? t->x - (h->x + h->w) : 0);
    int cost = along + across * 4 + (across > 0 ? 100000 : 0);
    if (along > 0 && cost < score)
      best = i, score = cost;
  }
  return best >= 0 ? hh->at[best].id : cur;
}
