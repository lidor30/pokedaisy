// GUIDE: HERE (the area: TO DO, wild POKéMON, people, items), BOSS (the
// next bosses' teams) and the hand-written TIPS / WHERE IS / STUCK? pages -
// the app's GuideScreen. Hint first: a tap shows the hint, the next the
// answer; one entry open at a time. The first visit per game shows the app's
// "written with AI" notice.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "pd_dex.h"
#include "pd_guide.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"

#define CHIP_H 22
#define ROW 18
#define LINE 16
#define MAX_ENTRIES 160
#define MAX_SECTIONS 48
#define MAX_PAGES 8
#define MAX_BOSSES_SHOWN 4

// --- the page being shown, rebuilt every draw ---

struct view_entry {
    char title[48], detail[32];
    const char* hint;   // NULL = none
    const char* answer; // "" = nothing to reveal
    char hintBuf[96], answerBuf[220];
    bool owned;
};

struct view_section {
    char heading[56], note[120];
    int first, count;
};

enum page_kind { PAGE_HERE, PAGE_BOSS, PAGE_STATIC, PAGE_WHERE };
struct page_ref {
    enum page_kind kind;
    int index; // the guide's page, for PAGE_STATIC
    const char* label;
};

static struct view_entry entries[MAX_ENTRIES];
static struct view_section sections[MAX_SECTIONS];
static int entryCount, sectionCount;
static struct page_ref pages[MAX_PAGES];
static int pageCount;
static int lastKindBit;

static struct view_section* add_section(const char* heading, const char* note) {
    if (sectionCount >= MAX_SECTIONS) return NULL;
    struct view_section* v = &sections[sectionCount++];
    snprintf(v->heading, sizeof(v->heading), "%s", heading ? heading : "");
    snprintf(v->note, sizeof(v->note), "%s", note ? note : "");
    v->first = entryCount;
    v->count = 0;
    return v;
}

static struct view_entry* add_entry(void) {
    if (entryCount >= MAX_ENTRIES || !sectionCount) return NULL;
    struct view_entry* e = &entries[entryCount++];
    memset(e, 0, sizeof(*e));
    e->answer = "";
    sections[sectionCount - 1].count++;
    return e;
}

static int steps(const struct view_entry* e) {
    return !*e->answer ? 0 : e->hint ? 2 : 1;
}

// The pages sit in a column (GuideScreen's): room for their whole names.
static const char* page_label(const char* title) {
    return title;
}

static void build_pages(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_guide* gd) {
    pageCount = 0;
    int areaCount;
    pd_areas_for(g, &areaCount);
    bool rom = pd_guide_rom_ok(g);
    // a Game Boy game: HERE where its wild tables and area data are generated (Gen 1)
    if ((areaCount || rom) && (!g->cfg->gb || areaCount)) pages[pageCount++] = (struct page_ref) { PAGE_HERE, 0, "HERE" };
    if (rom && s->flagsOk && gd->bossCount) pages[pageCount++] = (struct page_ref) { PAGE_BOSS, 0, "NEXT BOSS" };
    bool whereIs = false;
    for (int i = 0; i < gd->pageCount && pageCount < MAX_PAGES; i++) {
        pages[pageCount++] = (struct page_ref) { PAGE_STATIC, i, page_label(gd->pages[i].title) };
        whereIs |= !strcmp(gd->pages[i].title, "WHERE IS");
    }
    // No hand-written WHERE IS (the ROM hacks): one built from the area data, as the app's GuideScreen does.
    if (!whereIs && areaCount && pageCount < MAX_PAGES) pages[pageCount++] = (struct page_ref) { PAGE_WHERE, 0, "WHERE IS" };
}

// --- the pages ---

static void static_entry(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_guide_entry* src) {
    struct view_entry* e = add_entry();
    if (!e) return;
    snprintf(e->title, sizeof(e->title), "%s", src->title);
    e->hint = src->hint;
    e->answer = src->answer;
    e->owned = pd_guide_has(g, s, src);
}

static void build_static(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_guide_page* p) {
    for (int i = 0; i < p->count; i++) {
        const struct pd_guide_section* sec = &p->sections[i];
        add_section(sec->heading, sec->note);
        for (int k = 0; k < sec->count; k++) static_entry(g, s, &sec->entries[k]);
    }
}

static bool seen_species(const struct pd_game* g, const struct pd_snapshot* s, int species) {
    int nat = pd_dex_national(g, species);
    return nat && pd_dex_seen(s, nat);
}

static bool caught_species(const struct pd_game* g, const struct pd_snapshot* s, int species) {
    int nat = pd_dex_national(g, species);
    return nat && pd_dex_caught(s, nat);
}

// "around PALLET TOWN" or "in the GYM".
static void where_text(const struct pd_area_thing* t, const char* place, char* out, size_t len) {
    if (t->where && *t->where) {
        snprintf(out, len, "in the %s", t->where);
    } else {
        if (*place) snprintf(out, len, "around %s", place);
        else snprintf(out, len, "nearby");  // a hack's map sections have no names here
    }
}

static void area_entry(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_area_thing* t,
                       const char* place) {
    struct view_entry* e = add_entry();
    if (!e) return;
    char at[64], name[40], buf[40];
    where_text(t, place, at, sizeof(at));
    e->owned = pd_area_done(g, s, t);
    switch (t->kind) {
    case PD_AREA_MON:
        snprintf(e->title, sizeof(e->title), "%s", pd_species_name(g, t->id, name, sizeof(name)));
        snprintf(e->answerBuf, sizeof(e->answerBuf), "Someone %s gives you this POKéMON.", at);
        break;
    case PD_AREA_EGG:
        snprintf(e->title, sizeof(e->title), "AN EGG");
        snprintf(e->answerBuf, sizeof(e->answerBuf), "Someone %s gives you an EGG: %s.", at,
                 pd_species_name(g, t->id, name, sizeof(name)));
        break;
    case PD_AREA_TRADE:
        snprintf(e->title, sizeof(e->title), "TRADE FOR %s", pd_species_name(g, t->id, name, sizeof(name)));
        snprintf(e->answerBuf, sizeof(e->answerBuf), "Someone %s trades it for your %s.", at,
                 pd_species_name(g, t->wants, buf, sizeof(buf)));
        break;
    case PD_AREA_HIDDEN:
        if (!e->owned) {
            snprintf(e->title, sizeof(e->title), "HIDDEN ITEM");
            snprintf(e->hintBuf, sizeof(e->hintBuf), "Buried %s.", at);
            e->hint = e->hintBuf;
            snprintf(e->answerBuf, sizeof(e->answerBuf), "%s", pd_item_name(g, t->id));
            break;
        }
        // Found: named like any item.
        // fall through
    default: {
        const char* item = pd_item_name(g, t->id);
        if (t->qty > 1) {
            snprintf(e->title, sizeof(e->title), "%s x%d", item, t->qty);
        } else {
            snprintf(e->title, sizeof(e->title), "%s", item);
        }
        if (t->kind == PD_AREA_ITEM || t->kind == PD_AREA_HIDDEN) {
            snprintf(e->answerBuf, sizeof(e->answerBuf), "Lying %s.", at);
        } else {
            snprintf(e->answerBuf, sizeof(e->answerBuf), "Someone %s gives it to you.", at);
        }
        break;
    }
    }
    if (!e->answer[0]) e->answer = e->answerBuf;
}

static void build_here(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_guide* gd) {
    const char* place = pd_mapsec_name(g, s->mapsec);
    char key[64];
    pd_area_key(place, key, sizeof(key));
    struct view_section* intro = add_section(*place ? place : "THIS MAP", NULL);
    int before = entryCount;

    // TO DO: the hand-written entries tagged with this area (not TIPS).
    add_section("TO DO", NULL);
    for (int p = 0; p < gd->pageCount; p++) {
        if (!strcmp(gd->pages[p].title, "TIPS")) continue;
        for (int i = 0; i < gd->pages[p].count; i++) {
            const struct pd_guide_section* sec = &gd->pages[p].sections[i];
            for (int k = 0; k < sec->count; k++) {
                if (!pd_entry_in_area(&sec->entries[k], key)) continue;
                bool dup = false;
                for (int e = sections[sectionCount - 1].first; e < entryCount && !dup; e++) {
                    dup = !strcmp(entries[e].title, sec->entries[k].title);
                }
                if (!dup) static_entry(g, s, &sec->entries[k]);
            }
        }
    }
    if (!sections[sectionCount - 1].count) sectionCount--;

    // The wild POKéMON: unseen ones stay "???" until revealed.
    static struct pd_wild_set wild;
    bool hasWild = pd_wild_here(g, s, &wild);
    for (int m = 0; hasWild && m < PD_WILD_METHODS; m++) {
        if (!wild.count[m]) continue;
        add_section(pd_wild_method_name(m), NULL);
        for (int i = 0; i < wild.count[m]; i++) {
            const struct pd_wild* w = &wild.mons[m][i];
            struct view_entry* e = add_entry();
            if (!e) break;
            char name[40];
            pd_species_name(g, w->species, name, sizeof(name));
            if (w->minLv == w->maxLv) {
                snprintf(e->detail, sizeof(e->detail), "LV%d  %d%%", w->minLv, w->pct);
            } else {
                snprintf(e->detail, sizeof(e->detail), "LV%d-%d  %d%%", w->minLv, w->maxLv, w->pct);
            }
            e->owned = caught_species(g, s, w->species);
            if (seen_species(g, s, w->species)) {
                snprintf(e->title, sizeof(e->title), "%s", name);
            } else {
                snprintf(e->title, sizeof(e->title), "???");
                snprintf(e->answerBuf, sizeof(e->answerBuf), "%s", name);
                e->answer = e->answerBuf;
            }
        }
    }

    // PEOPLE and ITEMS of this area, what's done last.
    int n;
    const struct pd_area_thing* things = pd_areas_for(g, &n);
    for (int group = 0; group < 2; group++) {
        bool people = group == 0;
        add_section(people ? "PEOPLE" : "ITEMS", people ? "Some want something done first." : NULL);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                const struct pd_area_thing* t = &things[i];
                bool isItem = t->kind == PD_AREA_ITEM || t->kind == PD_AREA_HIDDEN;
                if (t->mapsec != s->mapsec || isItem == people) continue;
                if (pd_area_done(g, s, t) != (pass == 1)) continue;
                area_entry(g, s, t, place);
            }
        }
        if (!sections[sectionCount - 1].count) sectionCount--;
    }

    if (intro) {
        snprintf(intro->note, sizeof(intro->note), "%s",
                 entryCount == before ? "Nothing listed for this area." : "Its buildings included. A ball = done.");
    }
}

// WHERE IS from the area data (GuideAreas.kt generatedWhereIs): every HM, key item and gift POKéMON someone hands
// out, the area it's in as the hint and where in it as the answer; each named once (its first area), by name.
static bool is_hm(const char* name) {
    return !strncmp(name, "HM", 2) && name[2] >= '0' && name[2] <= '9';
}

static int by_title(const void* a, const void* b) {
    return strcmp(((const struct view_entry*) a)->title, ((const struct view_entry*) b)->title);
}

static void build_where(const struct pd_game* g, const struct pd_snapshot* s) {
    int n;
    const struct pd_area_thing* things = pd_areas_for(g, &n);
    static const char* const HEADINGS[3] = { "HMs", "KEY ITEMS", "GIFT POKéMON" };
    for (int sec = 0; sec < 3; sec++) {
        add_section(HEADINGS[sec], NULL);
        int first = entryCount;
        for (int i = 0; i < n; i++) {
            const struct pd_area_thing* t = &things[i];
            bool gift = t->kind == PD_AREA_GIFT || t->kind == PD_AREA_KEY, mon = t->kind == PD_AREA_MON || t->kind == PD_AREA_EGG;
            char name[48], sp[40];
            if (mon) {
                if (sec != 2) continue;
                snprintf(name, sizeof(name), "%s%s", pd_species_name(g, t->id, sp, sizeof(sp)),
                         t->kind == PD_AREA_EGG ? " (egg)" : "");
            } else {
                if (!gift && t->kind != PD_AREA_ITEM) continue;
                const char* item = pd_item_name(g, t->id);
                bool hm = is_hm(item);
                if (sec == 2 || (sec == 0 && !hm) || (sec == 1 && (hm || t->kind != PD_AREA_KEY))) continue;
                snprintf(name, sizeof(name), "%s", item);
            }
            if (!name[0] || name[0] == '#' || !strncmp(name, "Item#", 5)) continue;  // no name to show
            bool dup = false;
            for (int k = first; k < entryCount && !dup; k++) dup = !strcmp(entries[k].title, name);
            if (dup) continue;
            struct view_entry* e = add_entry();
            if (!e) break;
            const char* area = pd_mapsec_name(g, t->mapsec);
            snprintf(e->title, sizeof(e->title), "%s", name);
            const char* lying = t->kind == PD_AREA_ITEM ? "Lying" : "Someone";
            if (!area[0]) {  // a hack's map sections have no names here: say only what the data knows
                if (t->where[0]) snprintf(e->answerBuf, sizeof(e->answerBuf), "%s in the %s.", lying, t->where);
                else snprintf(e->answerBuf, sizeof(e->answerBuf), t->kind == PD_AREA_ITEM ? "Lying around." : "Someone has it.");
            } else {
                snprintf(e->hintBuf, sizeof(e->hintBuf), "In %s.", area);
                e->hint = e->hintBuf;
                if (t->kind == PD_AREA_ITEM)
                    snprintf(e->answerBuf, sizeof(e->answerBuf), t->where[0] ? "Lying in the %s (%s)." : "Lying around %s.",
                             t->where[0] ? t->where : area, area);
                else
                    snprintf(e->answerBuf, sizeof(e->answerBuf), t->where[0] ? "Someone in the %s (%s)." : "Someone around %s.",
                             t->where[0] ? t->where : area, area);
            }
            e->answer = e->answerBuf;
            e->owned = pd_area_done(g, s, t);
        }
        qsort(entries + first, (size_t) (entryCount - first), sizeof(*entries), by_title);
        if (!sections[sectionCount - 1].count) sectionCount--;
    }
}

static void build_bosses(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_guide* gd) {
    int best = 0;
    for (int i = 0; i < s->partyCount; i++) {
        if (s->party[i].species != PD_SPECIES_EGG && s->party[i].level > best) best = s->party[i].level;
    }
    int shown = 0;
    int order[256];
    int count = pd_boss_order(gd, s, order, 256);
    for (int oi = 0; oi < count && shown < MAX_BOSSES_SHOWN; oi++) {
        const struct pd_boss* boss = &gd->bosses[order[oi]];
        if (pd_boss_done(boss, s)) continue;
        const struct pd_variant* variants = pd_boss_variants(gd, boss, s);
        if (boss->kind == PD_BOSS_TEAMVAR) {  // Gen 1's champion: the save's team byte picks one, else every team shows
            for (const uint16_t* p = boss->alts; p && p[0]; p += 2)
                if (p[0] == pd_var(s, 0x4000)) variants = NULL;
        }
        int nv = 1;
        while (variants && variants[nv].label) nv++;
        for (int vi = 0; vi < nv; vi++) {
        const struct pd_variant* var = variants ? &variants[vi] : NULL;
        struct pd_team_mon team[PD_TEAM_MAX];
        int n = pd_trainer_team(g, var ? (int) var->trainer : pd_boss_trainer(gd, boss, s), team);
        int ace = 0, aceSpecies = 0;
        for (int i = 0; i < n; i++) {
            if (team[i].level > ace) { ace = team[i].level; aceSpecies = team[i].species; }
        }
        char heading[56], note[120];
        snprintf(heading, sizeof(heading), shown || vi ? "%s" : "NEXT: %s", boss->title);
        if (var) {
            size_t hl = strlen(heading);
            char with[40];
            if (var->label[0]) snprintf(heading + hl, sizeof(heading) - hl, " (%s)", var->label);
            else snprintf(heading + hl, sizeof(heading) - hl, " (WITH %s)", pd_species_name(g, aceSpecies, with, sizeof(with)));
        }
        snprintf(note, sizeof(note), "%s - %d POKéMON - UP TO LV%d - YOUR BEST LV%d", boss->where, n, ace, best);
        add_section(heading, note);
        for (int i = 0; i < n; i++) {
            struct view_entry* e = add_entry();
            if (!e) break;
            snprintf(e->title, sizeof(e->title), "POKéMON %d", i + 1);
            snprintf(e->detail, sizeof(e->detail), "LV%d", team[i].level);
            int t1, t2;
            pd_species_types(g, team[i].species, &t1, &t2);
            if (t1 == 255) {  // no base stats to read (a Game Boy game): no types line
            } else if (t2 != t1 && t2 != PD_TYPE_NONE) {
                snprintf(e->hintBuf, sizeof(e->hintBuf), "%s / %s", pd_type_name(g, t1), pd_type_name(g, t2));
                e->hint = e->hintBuf;
            } else {
                snprintf(e->hintBuf, sizeof(e->hintBuf), "%s", pd_type_name(g, t1));
                e->hint = e->hintBuf;
            }
            char name[40], move[32];
            int len = snprintf(e->answerBuf, sizeof(e->answerBuf), "%s:",
                               pd_species_name(g, team[i].species, name, sizeof(name)));
            bool first = true;
            for (int k = 0; k < 4 && len < (int) sizeof(e->answerBuf); k++) {
                if (!team[i].moves[k]) continue;
                len += snprintf(e->answerBuf + len, sizeof(e->answerBuf) - (size_t) len, "%s %s", first ? "" : ",",
                                pd_move_name(g, team[i].moves[k], move, sizeof(move)));
                first = false;
            }
            if (team[i].item && len < (int) sizeof(e->answerBuf)) {
                snprintf(e->answerBuf + len, sizeof(e->answerBuf) - (size_t) len, ". HOLDS %s",
                         pd_item_name(g, team[i].item));
            }
            e->answer = e->answerBuf;
        }
        }
        shown++;
    }
    if (!shown) add_section("ALL BEATEN", "Every gym leader and the League are beaten.");
}

// --- drawing ---

static int note_lines(const char* note, int w) {
    return *note ? pd_text_wrap(NULL, 0, 0, w, LINE, 3, note, 0, 0) : 0;
}

static int entry_height(const struct pd_ui* ui, int i, int w) {
    int h = ROW;
    if (ui->guideOpen != i) return h;
    const struct view_entry* e = &entries[i];
    if (ui->guideLevel >= 1 && e->hint) h += LINE * pd_text_wrap(NULL, 0, 0, w, LINE, 4, e->hint, 0, 0);
    if (ui->guideLevel >= steps(e)) h += LINE * pd_text_wrap(NULL, 0, 0, w, LINE, 6, e->answer, 0, 0);
    return h + (h > ROW ? 4 : 0);
}

static void draw_list(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h) {
    int total = 0, focusTop = -1, focusH = 0;
    for (int si = 0; si < sectionCount; si++) {
        total += ROW + LINE * note_lines(sections[si].note, w - 12);
        for (int i = sections[si].first; i < sections[si].first + sections[si].count; i++) {
            int eh = entry_height(ui, i, w - 30);
            if (ui->focusId == HIT_GUIDE_ROW + i) focusTop = total, focusH = eh;
            total += eh;
        }
        total += 4;
    }
    // The entry a host's D-pad cursor moves onto comes wholly into view (one taller than the window: from its top).
    static int seenFocus = -1;
    if (focusTop >= 0 && ui->focusId != seenFocus) ui->guideScroll = ui_scroll_to(ui->guideScroll, focusTop, focusH, h);
    seenFocus = ui->focusId;
    ui->guideScroll = ui_clamp_scroll(ui->guideScroll, total, h);
    ui_add_scroll_hit(ui, x, y, w, h, HIT_GUIDE_LIST);
    pd_clip(c, x, y, w, h);
    int cy = y - ui->guideScroll;
    for (int si = 0; si < sectionCount; si++) {
        const struct view_section* sec = &sections[si];
        pd_text_fit(c, x + 6, pd_text_y(cy, ROW), w - 12, sec->heading, PD_VALUE, PD_VALUE_SHADOW);
        cy += ROW;
        if (*sec->note) {
            cy += LINE * pd_text_wrap(c, x + 6, cy, w - 12, LINE, 3, sec->note, PD_MUTED, PD_MUTED_SHADOW);
        }
        for (int i = sec->first; i < sec->first + sec->count; i++) {
            const struct view_entry* e = &entries[i];
            int eh = entry_height(ui, i, w - 30);
            if (cy + eh > y && cy < y + h) {
                bool open = ui->guideOpen == i;
                if (open || ui->pressedId == HIT_GUIDE_ROW + i) pd_fill(c, x, cy, w, eh, PD_ROW_SELECTED);
                int ty = pd_text_y(cy, ROW);
                if (e->owned) ui_ball(c, x + 6, cy + 4, true);
                // What the next tap shows: the HINT, then the ANSWER.
                int st = steps(e), level = open ? ui->guideLevel : 0;
                const char* tag = level >= st ? "" : (e->hint && level == 0) ? "HINT" : "ANSWER";
                int right = x + w - 8;
                if (*tag) right -= pd_text_width(tag) + 8;
                if (*tag) pd_text(c, right + 8, ty, tag, PD_MUTED, PD_MUTED_SHADOW);
                if (*e->detail) {
                    pd_text_right(c, right, ty, e->detail, PD_LABEL, PD_LABEL_SHADOW);
                    right -= pd_text_width(e->detail) + 8;
                }
                pd_text_fit(c, x + 20, ty, right - x - 20, e->title, PD_LABEL, PD_LABEL_SHADOW);
                int ly = cy + ROW;
                if (open && level >= 1 && e->hint) {
                    char hint[120];
                    snprintf(hint, sizeof(hint), "HINT: %s", e->hint);
                    ly += LINE * pd_text_wrap(c, x + 20, ly, w - 30, LINE, 4, hint, PD_MUTED, PD_MUTED_SHADOW);
                }
                if (open && level >= st && st) {
                    pd_text_wrap(c, x + 20, ly, w - 30, LINE, 6, e->answer, PD_VALUE, PD_VALUE_SHADOW);
                }
                int top = cy < y ? y : cy;
                int bottom = cy + eh > y + h ? y + h : cy + eh;
                ui_add_scroll_hit(ui, x, top, w, bottom - top, HIT_GUIDE_ROW + i);
            }
            cy += eh;
        }
        cy += 4;
    }
    pd_unclip(c);
    ui_scroll_bar(c, x + w - 4, y, h, ui->guideScroll, total, PD_MUTED);
}

void ui_guide_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    const struct pd_guide* gd = pd_guide_for(g);
    if (!gd) {
        ui_notice(c, "NO GUIDE", "There's no guide for this game yet.", NULL);
        return;
    }
    // The first visit per game: the notice first.
    // (A bit per game kind, as saved settings have it; the guides past Emerald's get their own.)
    lastKindBit = g->guide >= PD_GUIDE_RUBY ? 1 << (3 + g->guide) : 1 << g->kind;  // kinds use bits 0-3, guides from 7 up (bit 26 at most)
    if (!(ui->settings->guideNotice & lastKindBit) && ui->overlay == PD_OVERLAY_NONE) {
        ui->overlay = PD_OVERLAY_GUIDE_NOTICE;
    }
    build_pages(g, s, gd);
    if (ui->guidePage < 0 || ui->guidePage >= pageCount) ui->guidePage = 0;

    // The pages, in a column on the left like GuideScreen's: a GUIDE title
    // window over a list window, the open page the white row.
    int colW = c->w * 30 / 100;
    int tf = pd_title_box(c, MARGIN, CONTENT_Y, colW, CHIP_H, PD_TITLE_FILL);
    pd_text(c, MARGIN + tf + 6, pd_text_y(CONTENT_Y, CHIP_H), "GUIDE", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    int py = CONTENT_Y + CHIP_H + 4;
    int pf = pd_list_box(c, MARGIN, py, colW, c->h - py - MARGIN);
    int rowH = (c->h - py - MARGIN - 2 * pf) / (pageCount > 0 ? pageCount : 1);
    if (rowH > 26) rowH = 26;
    for (int i = 0; i < pageCount; i++) {
        int rx = MARGIN + pf, ry = py + pf + i * rowH, rw = colW - 2 * pf;
        bool open = ui->guidePage == i;
        if (open || ui->pressedId == HIT_GUIDE_PAGE + i) pd_fill(c, rx, ry, rw, rowH, PD_ROW_SELECTED);
        pd_text_fit(c, rx + 6, pd_text_y(ry, rowH), rw - 10, pages[i].label, open ? PD_VALUE : PD_LABEL,
                    open ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
        if (i < pageCount - 1) {
            for (int dx = 4; dx < rw - 4; dx += 4) pd_fill(c, rx + dx, ry + rowH - 1, 2, 1, PD_DIVIDER);
        }
        ui_add_hit(ui, rx, ry, rw, rowH, HIT_GUIDE_PAGE + i);
    }

    entryCount = sectionCount = 0;
    const struct page_ref* p = &pages[ui->guidePage];
    if (p->kind == PAGE_HERE) build_here(g, s, gd);
    else if (p->kind == PAGE_BOSS) build_bosses(g, s, gd);
    else if (p->kind == PAGE_WHERE) build_where(g, s);
    else build_static(g, s, &gd->pages[p->index]);
    if (ui->guideOpen >= entryCount) ui->guideOpen = -1;

    int x = MARGIN + colW + 4, lw = c->w - MARGIN - x;
    int f = pd_list_box(c, x, CONTENT_Y, lw, c->h - CONTENT_Y - MARGIN);
    draw_list(ui, c, x + f, CONTENT_Y + f + 1, lw - 2 * f, c->h - CONTENT_Y - MARGIN - 2 * f - 2);
}

void ui_guide_notice(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g) {
    const struct pd_guide* gd = pd_guide_for(g);
    pd_dim(c, 0xE6);
    ui_add_hit(ui, 0, 0, c->w, c->h, HIT_SCRIM);
    int x = MARGIN, w = c->w - 2 * MARGIN, y = CONTENT_Y + 4;
    int f = pd_title_box(c, x, y, w, 24, PD_TITLE_FILL);
    pd_text(c, x + f + 6, pd_text_y(y, 24), "BEFORE YOU READ", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    y += 28;
    char text[400];
    snprintf(text, sizeof(text),
             "This guide was written with the help of AI, from the game's own data. It can still get details wrong "
             "or leave things out - double-check anything that matters before relying on it.%s",
             gd && !gd->verified ? " This game's guide hasn't been checked in-game yet." : "");
    int h = 150;
    int lf = pd_list_box(c, x, y, w, h);
    int ty = y + lf + 2;
    ty += LINE * pd_text_wrap(c, x + lf + 6, ty, w - 2 * (lf + 6), LINE, 6, text, PD_LABEL, PD_LABEL_SHADOW) + 4;
    pd_text_wrap(c, x + lf + 6, ty, w - 2 * (lf + 6), LINE, 2,
                 "HERE and BOSS come straight from the game's own data.", PD_MUTED, PD_MUTED_SHADOW);
    y += h + 4;
    ui_button(ui, c, x, y, 120, 24, "GO BACK", HIT_NOTICE_BACK);
    ui_button(ui, c, x + w - 140, y, 140, 24, "I UNDERSTAND", HIT_NOTICE_OK);
}

bool ui_guide_act(struct pd_ui* ui, int id) {
    if (ui->overlay == PD_OVERLAY_GUIDE_NOTICE) {
        if (id == HIT_NOTICE_OK) {
            ui->settings->guideNotice |= lastKindBit;
            ui->overlay = PD_OVERLAY_NONE;
            ui->action = PD_ACTION_SETTINGS_CHANGED;
        } else if (id == HIT_NOTICE_BACK) {
            ui->overlay = PD_OVERLAY_NONE;
            ui->tab = PD_TAB_PARTY;
        }
        // Taps beside the window don't dismiss it: it wants an answer.
        return true;
    }
    if (ui->tab != PD_TAB_GUIDE || ui->overlay != PD_OVERLAY_NONE) return false;
    if (id >= HIT_GUIDE_PAGE && id < HIT_GUIDE_PAGE + MAX_PAGES) {
        ui->guidePage = id - HIT_GUIDE_PAGE;
        ui->guideScroll = 0;
        ui->guideOpen = -1;
        ui->guideLevel = 0;
        return true;
    }
    if (id >= HIT_GUIDE_ROW && id < HIT_GUIDE_ROW + MAX_ENTRIES) {
        int i = id - HIT_GUIDE_ROW;
        int st = i < entryCount ? steps(&entries[i]) : 0;
        if (!st) return true;
        if (ui->guideOpen == i && ui->guideLevel >= st) {
            ui->guideOpen = -1;
            ui->guideLevel = 0;
        } else if (ui->guideOpen == i) {
            ui->guideLevel++;
        } else {
            ui->guideOpen = i;
            ui->guideLevel = 1;
        }
        return true;
    }
    return false;
}
