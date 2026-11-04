// GUIDE: HERE (the area: TO DO, wild POKéMON, people, items), BOSS (the
// next bosses' teams) and the hand-written TIPS / WHERE IS / STUCK? pages -
// the app's GuideScreen. Hint first: a tap shows the hint, the next the
// answer; one entry open at a time. The first visit per game shows the app's
// "written with AI" notice.
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "pd_dex.h"
#include "pd_gb_guide.h"
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

// An HM by its name in the game's language: HM01, CS01 (French), VM01 (German), MN01 (Italian), MO01 (Spanish).
static bool is_hm(const char* name) {
    static const char* const WORDS[] = { "HM", "CS", "VM", "MN", "MO" };
    for (int i = 0; i < 5; i++) {
        if (strncasecmp(name, WORDS[i], 2)) continue;
        const char* p = name + 2;
        if (*p == ' ') p++;
        if (isdigit((unsigned char) *p)) return true;
    }
    return false;
}

enum where_section { WHERE_HM, WHERE_KEY, WHERE_MON, WHERE_NONE };

// Which WHERE IS section a thing goes in.
static enum where_section where_of(const struct pd_game* g, const struct pd_area_thing* t) {
    if (t->kind == PD_AREA_MON || t->kind == PD_AREA_EGG) return WHERE_MON;
    bool gift = t->kind == PD_AREA_GIFT || t->kind == PD_AREA_KEY;
    if (!gift && t->kind != PD_AREA_ITEM) return WHERE_NONE;
    bool hm = is_hm(pd_item_name(g, t->id));
    if (hm) return WHERE_HM;
    return t->kind == PD_AREA_KEY ? WHERE_KEY : WHERE_NONE;
}

// No hand-written WHERE IS (the hacks, the Game Boy games): one is built from the area data (generatedWhereIs).
static bool has_where(const struct pd_game* g, const struct pd_guide* gd) {
    for (int i = 0; i < gd->pageCount; i++)
        if (!strcmp(gd->pages[i].title, "WHERE IS")) return false;
    int n;
    const struct pd_area_thing* things = pd_areas_for(g, &n);
    for (int i = 0; i < n; i++)
        if (where_of(g, &things[i]) != WHERE_NONE) return true;
    return false;
}

static void build_pages(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_guide* gd) {
    pageCount = 0;
    int areaCount;
    pd_areas_for(g, &areaCount);
    bool rom = pd_guide_rom_ok(g);
    if (areaCount || rom) pages[pageCount++] = (struct page_ref) { PAGE_HERE, 0, "HERE" };
    if (rom && s->flagsOk && (gd->bossCount || pd_gb_boss_count(g))) pages[pageCount++] = (struct page_ref) { PAGE_BOSS, 0, "NEXT BOSS" };
    for (int i = 0; i < gd->pageCount && pageCount < MAX_PAGES; i++) {
        pages[pageCount++] = (struct page_ref) { PAGE_STATIC, i, page_label(gd->pages[i].title) };
    }
    if (pageCount < MAX_PAGES && has_where(g, gd)) pages[pageCount++] = (struct page_ref) { PAGE_WHERE, 0, "WHERE IS" };
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
        snprintf(out, len, "around %s", place);
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
        snprintf(e->title, sizeof(e->title), "%s", pd_species_name(t->id, name, sizeof(name)));
        snprintf(e->answerBuf, sizeof(e->answerBuf), "Someone %s gives you this POKéMON.", at);
        break;
    case PD_AREA_EGG:
        snprintf(e->title, sizeof(e->title), "AN EGG");
        snprintf(e->answerBuf, sizeof(e->answerBuf), "Someone %s gives you an EGG: %s.", at,
                 pd_species_name(t->id, name, sizeof(name)));
        break;
    case PD_AREA_TRADE:
        snprintf(e->title, sizeof(e->title), "TRADE FOR %s", pd_species_name(t->id, name, sizeof(name)));
        snprintf(e->answerBuf, sizeof(e->answerBuf), "Someone %s trades it for your %s.", at,
                 pd_species_name(t->wants, buf, sizeof(buf)));
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
        // Grass follows the game's clock: which list this is.
        const char* clock = m == 0 ? pd_gb_clock(g, s) : NULL;
        char note[48];
        if (clock) snprintf(note, sizeof(note), "For your game's clock: %s.", clock);
        add_section(pd_wild_method_name(m), clock ? note : NULL);
        for (int i = 0; i < wild.count[m]; i++) {
            const struct pd_wild* w = &wild.mons[m][i];
            struct view_entry* e = add_entry();
            if (!e) break;
            char buf[40];
            const char* name = pd_species_name(w->species, buf, sizeof(buf));
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
                if (!pd_area_here(g, s, t) || isItem == people) continue;
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

// A boss's team under its heading: each POKéMON an entry, its type the hint, its moves the answer.
static void team_section(const struct pd_game* g, const char* title, const char* where, bool first, int best,
                         const struct pd_team_mon* team, int n) {
    int ace = 0;
    for (int i = 0; i < n; i++) ace = team[i].level > ace ? team[i].level : ace;
    char heading[72], note[120];
    snprintf(heading, sizeof(heading), first ? "NEXT: %s" : "%s", title);
    snprintf(note, sizeof(note), "%s - %d POKéMON - UP TO LV%d - YOUR BEST LV%d", where, n, ace, best);
    add_section(heading, note);
    for (int i = 0; i < n; i++) {
        struct view_entry* e = add_entry();
        if (!e) break;
        snprintf(e->title, sizeof(e->title), "POKéMON %d", i + 1);
        snprintf(e->detail, sizeof(e->detail), "LV%d", team[i].level);
        int t1, t2;
        pd_species_types(g, team[i].species, &t1, &t2);
        if (t2 != t1 && t2 != PD_TYPE_NONE) {
            snprintf(e->hintBuf, sizeof(e->hintBuf), "%s / %s", pd_type_name(t1), pd_type_name(t2));
        } else {
            snprintf(e->hintBuf, sizeof(e->hintBuf), "%s", pd_type_name(t1));
        }
        e->hint = e->hintBuf;
        char name[40], move[32];
        int len = snprintf(e->answerBuf, sizeof(e->answerBuf), "%s:", pd_species_name(team[i].species, name, sizeof(name)));
        bool none = true;
        for (int k = 0; k < 4 && len < (int) sizeof(e->answerBuf); k++) {
            if (!team[i].moves[k]) continue;
            len += snprintf(e->answerBuf + len, sizeof(e->answerBuf) - (size_t) len, "%s %s", none ? "" : ",",
                            pd_move_name(team[i].moves[k], move, sizeof(move)));
            none = false;
        }
        if (team[i].item && len < (int) sizeof(e->answerBuf)) {
            snprintf(e->answerBuf + len, sizeof(e->answerBuf) - (size_t) len, ". HOLDS %s", pd_item_name(g, team[i].item));
        }
        e->answer = e->answerBuf;
    }
}

// A Game Boy guide's bosses: what's ahead in the game's order; the champion's teams side by side until the save picks one.
static void build_gb_bosses(const struct pd_game* g, const struct pd_snapshot* s, int best) {
    int shown = 0;
    for (int b = 0; b < pd_gb_boss_count(g) && shown < MAX_BOSSES_SHOWN; b++) {
        const struct pd_gb_boss* boss = pd_gb_boss_at(g, b);
        if (pd_gb_boss_done(s, boss)) continue;
        int teams = pd_gb_boss_teams(s, boss);
        for (int k = 0; k < teams; k++) {
            struct pd_team_mon team[PD_TEAM_MAX];
            int n = pd_gb_boss_team(g, s, boss, k, team);
            char where[72], name[40];
            int top = 0;
            for (int i = 1; i < n; i++) top = team[i].level > team[top].level ? i : top;
            // The heading is long enough as it is: what tells the teams apart goes first under it.
            if (teams > 1) snprintf(where, sizeof(where), "WITH %s - %s", pd_species_name(team[top].species, name, sizeof(name)), boss->where);
            else snprintf(where, sizeof(where), "%s", boss->where);
            team_section(g, boss->title, where, !shown, best, team, n);
            shown++;
        }
    }
    if (!shown) add_section("ALL DONE", "Every boss is beaten.");
}

static void build_bosses(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_guide* gd) {
    int best = 0;
    for (int i = 0; i < s->partyCount; i++) {
        if (!s->party[i].egg && s->party[i].level > best) best = s->party[i].level;
    }
    if (g->gb) {
        build_gb_bosses(g, s, best);
        return;
    }
    int shown = 0;
    for (int b = 0; b < gd->bossCount && shown < MAX_BOSSES_SHOWN; b++) {
        const struct pd_boss* boss = &gd->bosses[b];
        if (pd_flag(s, boss->doneFlag)) continue;
        struct pd_team_mon team[PD_TEAM_MAX];
        int n = pd_trainer_team(g, pd_boss_trainer(gd, boss, s), team);
        team_section(g, boss->title, boss->where, !shown, best, team, n);
        shown++;
    }
    if (!shown) add_section("ALL BEATEN", "Every gym leader and the League are beaten.");
}

// WHERE IS: every HM, key item and gift POKéMON someone hands out, the area the hint, the building the answer.
#define MAX_WHERE 120
struct where_row {
    const struct pd_area_thing* t;
    char title[48];
};

static int where_compare(const void* a, const void* b) {
    return strcmp(((const struct where_row*) a)->title, ((const struct where_row*) b)->title);
}

static void build_where(const struct pd_game* g, const struct pd_snapshot* s) {
    static const char* const HEADINGS[3] = { "HMs", "KEY ITEMS", "GIFT POKéMON" };
    static struct where_row rows[MAX_WHERE];
    int n;
    const struct pd_area_thing* things = pd_areas_for(g, &n);
    for (int sec = 0; sec < 3; sec++) {
        int count = 0;
        // Handed out in one place, lying in another: the first area.
        for (int i = 0; i < n && count < MAX_WHERE; i++) {
            const struct pd_area_thing* t = &things[i];
            if (where_of(g, t) != (enum where_section) sec) continue;
            struct where_row r = { t, "" };
            char name[40];
            if (sec == WHERE_MON) {
                snprintf(r.title, sizeof(r.title), "%s%s", pd_species_name(t->id, name, sizeof(name)), t->kind == PD_AREA_EGG ? " (egg)" : "");
            } else {
                snprintf(r.title, sizeof(r.title), "%s", pd_item_name(g, t->id));
            }
            if (!*r.title || !strncmp(r.title, "Item#", 5) || r.title[0] == '#') continue; // no name to show
            if (strlen(r.title) <= 4 && strspn(r.title, "0123456789abcdefABCDEF") == strlen(r.title)) continue; // a hack's unused slot
            bool dup = false;
            for (int k = 0; k < count && !dup; k++) dup = !strcmp(rows[k].title, r.title);
            if (!dup) rows[count++] = r;
        }
        if (!count) continue;
        qsort(rows, (size_t) count, sizeof(*rows), where_compare);
        add_section(HEADINGS[sec], NULL);
        for (int i = 0; i < count; i++) {
            struct view_entry* e = add_entry();
            if (!e) break;
            const struct pd_area_thing* t = rows[i].t;
            char area[40];
            const char* place = pd_mapsec_name(g, t->mapsec);
            if (*place) snprintf(area, sizeof(area), "%s", place);
            else snprintf(area, sizeof(area), "AREA %d", t->mapsec);
            snprintf(e->title, sizeof(e->title), "%s", rows[i].title);
            snprintf(e->hintBuf, sizeof(e->hintBuf), "In %s.", area);
            e->hint = e->hintBuf;
            bool lying = t->kind == PD_AREA_ITEM;
            if (t->where && *t->where) {
                snprintf(e->answerBuf, sizeof(e->answerBuf), "%s in the %s (%s).", lying ? "Lying" : "Someone", t->where, area);
            } else {
                snprintf(e->answerBuf, sizeof(e->answerBuf), "%s around %s.", lying ? "Lying" : "Someone", area);
            }
            e->answer = e->answerBuf;
            e->owned = pd_area_done(g, s, t);
        }
    }
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
    int total = 0;
    for (int si = 0; si < sectionCount; si++) {
        total += ROW + LINE * note_lines(sections[si].note, w - 12);
        for (int i = sections[si].first; i < sections[si].first + sections[si].count; i++) {
            total += entry_height(ui, i, w - 30);
        }
        total += 4;
    }
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
    lastKindBit = 1 << g->kind;
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
