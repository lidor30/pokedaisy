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
  int tab, overlay, summarySlot, dexOpen, dexPage;
};
static struct screen lastScreen = {-1, -1, -1, -1, -1};
static struct screen screen_of(const struct pd_ui *u) {
  return (struct screen){u->tab, u->overlay, u->summarySlot, u->dexOpen, u->dexOpen ? u->dexPage : -1};
}
static void focus_new_screen(void) { lastScreen.tab = -1; }
// What the cursor can land on: not the tab bar, nor a scrim or a list's drag area.
static bool bag_browsing(const struct pd_ui *u) {
  return u->tab == PD_TAB_BAG && u->overlay == PD_OVERLAY_NONE && !u->itemPicking && u->bagSelected < 0;
}
static bool focusable(const struct pd_ui *u, int id) {
  if (bag_browsing(u)) return id >= HIT_BAG_ROW && id < HIT_BAG_ROW_END;
  return !(id >= HIT_TAB && id < HIT_TAB + PD_TAB_COUNT) && !(id >= HIT_BAG_TAB && id < HIT_BAG_LIST) && id != HIT_MORE && id != HIT_FF && id != HIT_SCRIM &&
         id != HIT_SHOULDER_L && id != HIT_SHOULDER_R && id != HIT_SCROLL_BAR &&
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
  int mapW, mapH; // the page's pixels (240 x 160, a Game Boy's 160 x 144)
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
  int page = 0, tx = art->w / 16, ty = art->h / 16;
  bool player = pd_map_player_tile(g, s, &page, &tx, &ty);
  int shown = u->mapPage >= 0 ? u->mapPage : player ? page : 0;
  if (shown >= art->pages) shown = 0;
  // The page's cursor grid, else the whole page (a Game Boy town map has no grid: pd_map_pick takes the smallest
  // section under the cursor).
  if (!pd_map_bounds(g, shown, &mapCursor.minX, &mapCursor.minY, &mapCursor.maxX, &mapCursor.maxY)) {
    mapCursor.ready = false;
    return;
  }
  mapCursor.mapW = art->w;
  mapCursor.mapH = art->h;
  if (mapCursor.ready && mapCursor.rom == g->loadId && mapCursor.page == shown &&
      mapCursor.requestedPage == u->mapPage &&
      mapCursor.sel == u->mapSel && mapCursor.dungeon == u->mapDungeon)
    return;
  if (!player || page != shown) {
    tx = art->w / 16;
    ty = art->h / 16;
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
static void focus_activate(struct pd_ui *u) {
  int at = focus_index(u, focusId);
  if (at < 0 || !focusable(u, focusId))
    return;
  const struct pd_hit *h = &u->hits[at];
  if (map_cursor_active(u)) {
    // Call the map's existing selection directly: overlaid map buttons must
    // not intercept a tile in the top-right corner of the grid.
    u->touchX0 = h->x + (mapCursor.tx * 8 + 4) * h->w / mapCursor.mapW;
    u->touchY0 = h->y + (mapCursor.ty * 8 + 4) * h->h / mapCursor.mapH;
    ui_map_act(u, HIT_MAP);
    mapCursor.sel = u->mapSel;
    mapCursor.dungeon = u->mapDungeon;
    mapCursor.requestedPage = u->mapPage;
  } else if (h->id >= HIT_SLOT && h->id < HIT_SLOT + PD_PARTY_SIZE) {
    u->focusId = h->id;
    pd_ui_focus_press(u);
  } else {
    pd_ui_touch(u, h->x + h->w / 2, h->y + h->h / 2, true);
    pd_ui_touch(u, h->x + h->w / 2, h->y + h->h / 2, false);
  }
}
// The map art and hit IDs are native to this core; the grid/button transitions
// are the same as the DS companion.
static bool map_control(struct pd_ui *u, int dx, int dy, bool accept, bool back, bool places, bool *close) {
  if (u->tab != PD_TAB_MAP || u->overlay != PD_OVERLAY_NONE || !mapCursor.ready)
    return false;
  const int buttons[] = {HIT_MAP_PLACES, HIT_MAP_ME, HIT_MAP_FLY, HIT_MAP_REGION};
  int ids[sizeof buttons / sizeof buttons[0]], count = 0, action = -1;
  for (unsigned i = 0; i < sizeof buttons / sizeof buttons[0]; ++i) {
    if (focus_index(u, buttons[i]) < 0) continue;
    if (focusId == buttons[i]) action = count;
    ids[count++] = buttons[i];
  }
  switch (pd_ui_map_control(&action, count, dx, dy, accept, back, places)) {
  case PD_MAP_CLOSE:
    if (u->moreOpened) u->overlay = PD_OVERLAY_MORE;
    else *close = true;
    action = -1;
    break;
  case PD_MAP_OPEN_PLACES:
    ui_map_act(u, HIT_MAP_PLACES);
    break;
  case PD_MAP_CHOOSE:
    ui_map_act(u, ids[action]);
    if (ids[action] != HIT_MAP_PLACES && u->overlay == PD_OVERLAY_NONE)
      action = -1;
    break;
  case PD_MAP_CENTER:
    ui_map_act(u, HIT_MAP_ME);
    break;
  case PD_MAP_MOVE:
    mapCursor.tx += dx;
    mapCursor.ty += dy;
    if (mapCursor.tx < mapCursor.minX) mapCursor.tx = mapCursor.minX;
    if (mapCursor.tx > mapCursor.maxX) mapCursor.tx = mapCursor.maxX;
    if (mapCursor.ty < mapCursor.minY) mapCursor.ty = mapCursor.minY;
    if (mapCursor.ty > mapCursor.maxY) mapCursor.ty = mapCursor.maxY;
    focus_activate(u);
    break;
  case PD_MAP_IDLE:
    break;
  }
  focusId = action < 0 ? HIT_MAP : ids[action];
  return true;
}

// After a draw: where the cursor is now (an index into u->hits, -1 = none).
static int focus_resolve(struct pd_ui *u) {
  struct screen now = screen_of(u);
  bool changed = memcmp(&now, &lastScreen, sizeof now) != 0;
  const bool partyBack = now.tab == PD_TAB_PARTY && now.overlay == PD_OVERLAY_NONE &&
      now.summarySlot < 0 && lastScreen.tab == PD_TAB_PARTY && lastScreen.summarySlot >= 0;
  int floor = modal_floor(u);
  const bool settings = now.tab == PD_TAB_SETTINGS && now.overlay == PD_OVERLAY_NONE;
  const bool mapBack = now.tab == PD_TAB_MAP && now.overlay == PD_OVERLAY_NONE &&
      lastScreen.tab == PD_TAB_MAP;
  int i = settings ? focus_index(u, u->settingsNavId)
                   : changed && !partyBack && !mapBack ? -1 : focus_index(u, focusId);
  lastScreen = now;
  if (i >= 0 && (i < floor || !focusable(u, u->hits[i].id)))
    i = -1;
  if (i < 0 && !changed && focusId >= 0) { // gone: the nearest item to where it was
    int best = 1 << 30;
    for (int j = floor; j < u->hitCount; j++) {
      const struct pd_hit *h = &u->hits[j];
      int dx = h->x + h->w / 2 - (focusAt.x + focusAt.w / 2),
          dy = h->y + h->h / 2 - (focusAt.y + focusAt.h / 2);
      if (focusable(u, h->id) && dx * dx + dy * dy < best)
        best = dx * dx + dy * dy, i = j;
    }
  }
  if (i < 0 && u->startId >= 0)
    i = focus_index(u, u->startId);
  if (i >= 0 && (i < floor || !focusable(u, u->hits[i].id)))
    i = -1;
  for (int j = floor; i < 0 && j < u->hitCount; j++)
    if (focusable(u, u->hits[j].id))
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
    if (!focusable(u, t->id))
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
    int mw = mapCursor.mapW, mh = mapCursor.mapH;
    tile = (struct pd_hit){h->x + x * h->w / mw,
                          h->y + y * h->h / mh,
                          (x + 8) * h->w / mw - x * h->w / mw,
                          (y + 8) * h->h / mh - y * h->h / mh,
                          HIT_MAP, false};
    h = &tile;
    pd_fill(&canvas, h->x - 1, h->y - 1, h->w + 2, 1, 0x202828);
    pd_fill(&canvas, h->x - 1, h->y + h->h, h->w + 2, 1, 0x202828);
    pd_fill(&canvas, h->x - 1, h->y, 1, h->h, 0x202828);
    pd_fill(&canvas, h->x + h->w, h->y, 1, h->h, 0x202828);
  }
  if (u->handheldDex && u->tab == PD_TAB_DEX &&
      ((h->id >= HIT_DEX_ROW && h->id <= HIT_DEX_ROW + PD_DEX_BYTES * 8) ||
       (h->id >= HIT_DEX_READ_ONLY && h->id <= HIT_DEX_READ_ONLY + PD_DEX_BYTES * 8) ||
       (u->dexOpen && h->id == HIT_DEX_PAGE + u->dexPage)))
    return; // these Dex indicators are drawn by the shared view
  if (h->id >= HIT_SLOT && h->id < HIT_SLOT + PD_PARTY_SIZE)
    return; // a party slot shows its own selected look (pd_ui.focusId)
  pd_fill(&canvas, h->x, h->y, h->w, 2, 0xff7331);
  pd_fill(&canvas, h->x, h->y + h->h - 2, h->w, 2, 0xff7331);
  pd_fill(&canvas, h->x, h->y, 2, h->h, 0xff7331);
  pd_fill(&canvas, h->x + h->w - 2, h->y, 2, h->h, 0xff7331);
}
