// pd_save.c on real saves: each reads, writes back the game's way (and reads back), moves a Pokémon (PC and party),
// changes the money, the bag and a Pokémon's page (level, IVs, EVs, nature / shiny / gender, nickname, item, moves), sorts
// and renames a box, marks the Pokédex, gives an event ticket, and trades (PC and party). Saves are given on the command line as CODE=path (BPRE=..., BPEE=..., AXVE=...); none are in the repo.
//
//   build/pd_save_test BPRE=<FireRed .sav> BPEE=<Emerald .sav> [AXVE=<Ruby .sav>]
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../core/pd_save.h"

static int failures;
#define CHECK(c, ...) do { if (!(c)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static uint8_t bytes[PD_SAVE_MAX_SIZE], out[PD_SAVE_MAX_SIZE];

static size_t load(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return 0;
    size_t n = fread(bytes, 1, sizeof bytes, f);
    fclose(f);
    return n;
}

static struct pd_save saves[3];
static char codes[3][5];

static void summary(struct pd_save* s, const char* code) {
    char name[16];
    pd_save_trainer_name(s, name, sizeof name);
    printf("%s: %s, %d:%02d, $%ld, %d coins, %d badges, %d caught, party %d, national %d, slot %d counter %lu\n", code, name,
           pd_save_play_hours(s), pd_save_play_minutes(s), pd_save_money(s), pd_save_coins(s), pd_save_badges(s), pd_save_dex_caught(s),
           pd_save_party_count(s), pd_save_national_dex(s), s->slot, (unsigned long) s->counter);
    for (int i = 0; i < pd_save_party_count(s); i++) {
        struct pd_mon m;
        if (pd_save_party_mon(s, i, &m)) printf("  party %d: %s species %d Lv%d\n", i, m.nickname, m.species, m.level);
    }
    int boxed = 0;
    for (int b = 0; b < PD_SAVE_BOXES; b++) boxed += pd_save_box_count(s, b);
    printf("  %d in the PC\n", boxed);
}

// Writes the working copies, then parses the result as the game would: the new slot, with exactly these blocks.
static void round_trip(struct pd_save* s, const char* code) {
    const char* why = pd_save_build(s, out);
    CHECK(!why, "%s: build: %s", code, why ? why : "");
    if (why) return;
    static struct pd_save again;
    why = pd_save_parse(&again, out, s->rawLen, s->f);
    CHECK(!why, "%s: reparse: %s", code, why ? why : "");
    CHECK(again.slot == 1 - s->slot && again.counter == s->counter + 1, "%s: slot %d counter %lu", code, again.slot, (unsigned long) again.counter);
    CHECK(!memcmp(again.block1, s->block1, sizeof again.block1) && !memcmp(again.storage, s->storage, sizeof again.storage), "%s: blocks", code);
}

static bool find_mon(struct pd_save* s, int* box, int* slot, bool empty) {
    for (int b = 0; b < PD_SAVE_BOXES; b++) {
        for (int i = 0; i < PD_SAVE_BOX_SLOTS; i++) {
            struct pd_box_mon m;
            pd_box_mon_read(&m, s->storage + 4 + (b * PD_SAVE_BOX_SLOTS + i) * PD_SAVE_RECORD);
            if (empty ? pd_box_mon_empty(&m) : (!pd_box_mon_empty(&m) && !pd_box_mon_bad_egg(&m))) {
                *box = b;
                *slot = i;
                return true;
            }
        }
    }
    return false;
}

int main(int argc, char** argv) {
    int n = 0;
    for (int i = 1; i < argc && n < 3; i++) {
        const char* eq = strchr(argv[i], '=');
        if (!eq || eq - argv[i] != 4) continue;
        memcpy(codes[n], argv[i], 4);
        codes[n][4] = 0;
        size_t len = load(eq + 1);
        if (!len) {
            printf("skip %s: no %s\n", codes[n], eq + 1);
            continue;
        }
        struct pd_save* s = &saves[n];
        const char* why = pd_save_parse(s, bytes, len, pd_save_layout_for(codes[n]));
        CHECK(!why, "%s: parse: %s", codes[n], why ? why : "");
        if (why) continue;
        const char* odd = pd_save_mismatch(s);
        CHECK(!odd, "%s: mismatch: %s", codes[n], odd ? odd : "");
        summary(s, codes[n]);

        // Unchanged: the box records encode back to their own bytes.
        for (int b = 0; b < PD_SAVE_BOXES; b++) {
            for (int k = 0; k < PD_SAVE_BOX_SLOTS; k++) {
                const uint8_t* r = s->storage + 4 + (b * PD_SAVE_BOX_SLOTS + k) * PD_SAVE_RECORD;
                struct pd_box_mon m;
                uint8_t again[PD_SAVE_RECORD];
                pd_box_mon_read(&m, r);
                pd_box_mon_encode(&m, again);
                CHECK(!memcmp(again, r, PD_SAVE_RECORD), "%s: box %d slot %d doesn't encode back", codes[n], b, k);
            }
        }
        CHECK(!pd_save_changed(s), "%s: changed before an edit", codes[n]);
        round_trip(s, codes[n]);

        // A move and its way back.
        int b, k, eb = 0, ek = 0;
        if (find_mon(s, &b, &k, false) && find_mon(s, &eb, &ek, true)) {
            struct pd_mon before, after;
            pd_save_box_mon(s, b, k, &before);
            CHECK(!pd_save_move(s, b, k, eb, ek), "%s: move", codes[n]);
            CHECK(pd_save_box_mon(s, eb, ek, &after) && after.species == before.species && after.level == before.level, "%s: moved mon", codes[n]);
            CHECK(pd_save_changed(s), "%s: unchanged after a move", codes[n]);
            round_trip(s, codes[n]);
            pd_save_move(s, eb, ek, b, k);
            CHECK(!pd_save_changed(s), "%s: moved back but changed", codes[n]);
        }

        // Party <-> PC: a PC Pokémon joins the party (when there's room) and goes back.
        if (pd_save_party_count(s) < 6 && find_mon(s, &b, &k, false)) {
            int count = pd_save_party_count(s);
            struct pd_mon before, after;
            pd_save_box_mon(s, b, k, &before);
            CHECK(!pd_save_move_at(s, PD_AT_BOX(b, k), PD_AT_PARTY(count)), "%s: withdraw", codes[n]);
            CHECK(pd_save_party_count(s) == count + 1 && pd_save_party_mon(s, count, &after) && after.species == before.species &&
                  after.hp == after.maxHp && after.maxHp > 0, "%s: withdrawn mon", codes[n]);
            round_trip(s, codes[n]);
            CHECK(!pd_save_move_at(s, PD_AT_PARTY(count), PD_AT_BOX(b, k)), "%s: deposit", codes[n]);
            CHECK(pd_save_party_count(s) == count, "%s: party count after deposit", codes[n]);
            CHECK(!pd_save_changed(s), "%s: withdraw + deposit changed the save", codes[n]);
            pd_save_revert(s);
        }
        // A full party: its last Pokémon to an empty PC place and back (DEPOSIT, then WITHDRAW).
        if (pd_save_party_count(s) == 6 && find_mon(s, &eb, &ek, true)) {
            struct pd_mon before, after;
            pd_save_party_mon(s, 5, &before);
            CHECK(!pd_save_move_at(s, PD_AT_PARTY(5), PD_AT_BOX(eb, ek)), "%s: deposit", codes[n]);
            CHECK(pd_save_party_count(s) == 5 && pd_save_box_mon(s, eb, ek, &after) && after.species == before.species, "%s: deposited", codes[n]);
            CHECK(pd_save_move_at(s, PD_AT_BOX(eb, ek), PD_AT_PARTY(5)) == NULL, "%s: withdraw", codes[n]);
            CHECK(pd_save_party_count(s) == 6 && pd_save_party_mon(s, 5, &after) && after.species == before.species && after.level == before.level,
                  "%s: withdrawn", codes[n]);
            round_trip(s, codes[n]);
            pd_save_revert(s);
            // Party order: the first two swap.
            struct pd_mon p0, p1;
            pd_save_party_mon(s, 0, &p0);
            CHECK(!pd_save_move_at(s, PD_AT_PARTY(0), PD_AT_PARTY(1)) && pd_save_party_mon(s, 1, &p1) && p1.species == p0.species, "%s: party swap", codes[n]);
            pd_save_revert(s);
        }
        if (pd_save_party_count(s) == 1) CHECK(pd_save_move_at(s, PD_AT_PARTY(0), PD_AT_BOX(eb, ek)) != NULL, "%s: last one deposited", codes[n]);

        // A Pokémon's page on the party lead: every value the editor offers, then the stats follow.
        if (pd_save_party_count(s) > 0 && !pd_save_editable(s, PD_AT_PARTY(0))) {
            int at = PD_AT_PARTY(0), lv = pd_save_level_at(s, at), ivs[6] = { 31, 31, 31, 31, 31, 31 }, evs[6] = { 252, 252, 6, 0, 0, 0 };
            CHECK(pd_save_set_level(s, at, lv - 1) != NULL || lv == 1, "%s: a level below what it was", codes[n]);
            if (lv < 100) CHECK(!pd_save_set_level(s, at, lv + 1) && pd_save_level_at(s, at) == lv + 1, "%s: level up", codes[n]);
            CHECK(!pd_save_set_ivs(s, at, ivs), "%s: IVs", codes[n]);
            CHECK(!pd_save_set_evs(s, at, evs), "%s: EVs", codes[n]);
            int tooMany[6] = { 255, 255, 1, 0, 0, 0 };
            CHECK(pd_save_set_evs(s, at, tooMany) != NULL, "%s: 511 EVs", codes[n]);
            CHECK(!pd_save_set_friendship(s, at, 255), "%s: friendship", codes[n]);
            struct pd_box_mon m;
            pd_save_record_at(s, at, &m);
            int species = pd_box_mon_species(&m), got[6], want[6], hp, gotEvs[6];
            pd_box_mon_evs(&m, gotEvs);
            CHECK(gotEvs[0] == 252 && pd_box_mon_friendship(&m) == 255, "%s: EVs / friendship stored", codes[n]);
            pd_save_stats_at(s, at, got, &hp);
            pd_save_stats(species, pd_save_level_at(s, at), ivs, evs, (int) (pd_box_mon_personality(&m) % 25), want);
            CHECK(!memcmp(got, want, sizeof got) && hp >= 1 && hp <= got[0], "%s: party stats follow", codes[n]);
            // Nature 3 (ADAMANT), shiny: a personality that keeps the gender (and the ability bit).
            enum pd_save_gender g = pd_save_gender_of(species, pd_box_mon_personality(&m));
            const char* why = pd_save_reroll(s, at, 3, 1, -1, -1);
            CHECK(!why, "%s: reroll: %s", codes[n], why ? why : "");
            pd_save_record_at(s, at, &m);
            CHECK(pd_box_mon_personality(&m) % 25 == 3 && pd_save_is_shiny(pd_box_mon_personality(&m), pd_box_mon_ot_id(&m)) &&
                  pd_save_gender_of(species, pd_box_mon_personality(&m)) == g && pd_box_mon_species(&m) == species, "%s: rerolled", codes[n]);
            int ivsNow[6];
            pd_box_mon_ivs(&m, ivsNow);
            CHECK(!memcmp(ivsNow, ivs, sizeof ivs), "%s: IVs kept through a reroll", codes[n]);
            if (pd_save_is_own(s, at)) {
                CHECK(!pd_save_set_nickname(s, at, "Daisy"), "%s: nickname", codes[n]);
                struct pd_mon named;
                pd_save_mon_at(s, at, &named);
                CHECK(!strcmp(named.nickname, "Daisy"), "%s: nickname reads %s", codes[n], named.nickname);
            }
            CHECK(pd_save_set_nickname(s, at, "Ünicode") != NULL, "%s: a name the games can't write", codes[n]);
            CHECK(!pd_save_set_held_item(s, at, 13), "%s: a Potion to hold", codes[n]);
            CHECK(pd_save_set_held_item(s, at, 339) != NULL, "%s: an HM to hold", codes[n]);
            uint8_t learn[(PD_SAVE_LAST_MOVE + 8) / 8];
            int nl = pd_save_learnable(s, at, learn);
            CHECK(nl > 0, "%s: learnable", codes[n]);
            int keep = pd_box_mon_move(&m, 0), add = 0;
            for (int mv = 1; mv <= PD_SAVE_LAST_MOVE && !add; mv++)
                if ((learn[mv / 8] >> (mv % 8) & 1) && mv != keep) add = mv;
            int moves[2] = { keep, add };
            CHECK(!pd_save_set_moves(s, at, moves, add ? 2 : 1), "%s: moves", codes[n]);
            pd_save_record_at(s, at, &m);
            CHECK(pd_box_mon_move(&m, 0) == keep && pd_box_mon_move(&m, 1) == add && pd_box_mon_move(&m, 2) == 0 &&
                  (!add || m.data[21] == pd_save_move_pp[add]), "%s: moves stored", codes[n]);
            int bad[1] = { 354 };
            if (!(learn[354 / 8] >> (354 % 8) & 1) && keep != 354) CHECK(pd_save_set_moves(s, at, bad, 1) != NULL, "%s: a move it can't learn", codes[n]);
            round_trip(s, codes[n]);
            pd_save_revert(s);
        }

        // Boxes: sort, rename, release.
        if (find_mon(s, &b, &k, false)) {
            int before = pd_save_box_count(s, b);
            pd_save_sort_box(s, b, PD_SORT_LEVEL);
            CHECK(pd_save_box_count(s, b) == before, "%s: sort kept the box's Pokémon", codes[n]);
            int last = 101;
            for (int i = 0; i < before; i++) {
                struct pd_box_mon m;
                pd_box_mon_read(&m, s->storage + 4 + (b * PD_SAVE_BOX_SLOTS + i) * PD_SAVE_RECORD);
                CHECK(!pd_box_mon_empty(&m), "%s: a hole after sorting", codes[n]);
                if (pd_box_mon_egg(&m) || pd_box_mon_bad_egg(&m)) continue;
                int lv = pd_save_level_for(pd_box_mon_species(&m), pd_box_mon_exp(&m));
                CHECK(lv <= last, "%s: not sorted by level", codes[n]);
                last = lv;
            }
            CHECK(!pd_save_rename_box(s, b, "DAISY 1"), "%s: rename", codes[n]);
            char name[16];
            pd_save_box_name(s, b, name, sizeof name);
            CHECK(!strcmp(name, "DAISY 1"), "%s: box name %s", codes[n], name);
            CHECK(pd_save_rename_box(s, b, "TOO LONG NAME") != NULL, "%s: a long box name", codes[n]);
            find_mon(s, &b, &k, false);
            struct pd_box_mon m;
            pd_box_mon_read(&m, s->storage + 4 + (b * PD_SAVE_BOX_SLOTS + k) * PD_SAVE_RECORD);
            if (!pd_box_mon_egg(&m)) CHECK(!pd_save_release(s, PD_AT_BOX(b, k)) && pd_save_box_count(s, b) == before - 1, "%s: release", codes[n]);
            round_trip(s, codes[n]);
            pd_save_revert(s);
        }

        // The bag: a quantity changed, an item added and taken out, key items and HMs kept.
        for (int p = 0; p < PD_BAG_POCKETS; p++) {
            struct pd_stack st[64];
            int count = pd_save_pocket(s, (enum pd_save_pocket) p, st, 64);
            uint16_t add[400];
            int na = pd_save_addable(s, (enum pd_save_pocket) p, add, 400);
            if (p == PD_BAG_KEY) {
                CHECK(na == 0, "%s: key items offered", codes[n]);
                if (count) CHECK(pd_save_set_quantity(s, PD_BAG_KEY, 0, 0) != NULL, "%s: a key item taken out", codes[n]);
                continue;
            }
            if (count && !pd_save_stack_fixed((enum pd_save_pocket) p, st[0].item)) {
                CHECK(!pd_save_set_quantity(s, (enum pd_save_pocket) p, 0, 5), "%s: pocket %d quantity", codes[n], p);
                CHECK(pd_save_set_quantity(s, (enum pd_save_pocket) p, 0, 1000) != NULL, "%s: 1000 in a stack", codes[n]);
            }
            if (na && count < pd_save_pocket_slots(s, (enum pd_save_pocket) p)) {
                CHECK(!pd_save_add_item(s, (enum pd_save_pocket) p, add[0], 1), "%s: pocket %d add %d", codes[n], p, add[0]);
                CHECK(pd_save_add_item(s, (enum pd_save_pocket) p, add[0], 1) != NULL, "%s: added twice", codes[n]);
                int again = pd_save_pocket(s, (enum pd_save_pocket) p, st, 64);
                CHECK(again == count + 1, "%s: pocket %d count after adding", codes[n], p);
            }
            round_trip(s, codes[n]);
            pd_save_revert(s);
        }

        // The Pokédex and the events.
        {
            struct pd_save_dex d0, d1;
            pd_save_dex_counts(s, &d0);
            pd_save_register_owned(s);
            pd_save_dex_counts(s, &d1);
            CHECK(d1.caught >= d0.caught, "%s: registering lost a mark", codes[n]);
            pd_save_mark_all_dex(s, false);
            pd_save_dex_counts(s, &d1);
            CHECK(d1.regionalSeen == d1.regionalSize, "%s: all seen %d of %d", codes[n], d1.regionalSeen, d1.regionalSize);
            printf("  dex %s %d/%d seen %d caught, national %d; all seen: %d\n", d0.region, d0.regionalSeen, d0.regionalSize, d0.regionalCaught,
                   d0.national, d1.seen);
            round_trip(s, codes[n]);
            pd_save_revert(s);
            for (int e = 0; e < PD_EVENTS; e++) {
                enum pd_event_state st = pd_save_event_state(s, (enum pd_save_event) e);
                if (st == PD_EVENT_AVAILABLE) {
                    CHECK(!pd_save_give_event(s, (enum pd_save_event) e), "%s: give %s", codes[n], pd_save_event_name((enum pd_save_event) e));
                    CHECK(pd_save_event_state(s, (enum pd_save_event) e) == PD_EVENT_HAS, "%s: %s not held", codes[n], pd_save_event_name((enum pd_save_event) e));
                    round_trip(s, codes[n]);
                }
                printf("  %s: %d\n", pd_save_event_name((enum pd_save_event) e), st);
            }
            pd_save_revert(s);
        }

        long money = pd_save_money(s);
        CHECK(pd_save_set_money(s, 123456) && pd_save_money(s) == 123456, "%s: money", codes[n]);
        CHECK(!pd_save_set_money(s, 1000000), "%s: money past the most", codes[n]);
        CHECK(pd_save_set_coins(s, 42) && pd_save_coins(s) == 42, "%s: coins", codes[n]);
        round_trip(s, codes[n]);
        pd_save_revert(s);
        CHECK(pd_save_money(s) == money, "%s: revert", codes[n]);
        n++;
    }

    // Trades between every pair: the first tradable Pokémon of one into an empty slot of the other, or why not.
    for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
            if (i == j) continue;
            struct pd_trade_side a = { &saves[i], codes[i], { 0 } }, bb = { &saves[j], codes[j], { 0 } };
            memcpy(a.code, codes[i], 5);
            memcpy(bb.code, codes[j], 5);
            int box, slot, eb, ek;
            if (!find_mon(&saves[i], &box, &slot, false) || !find_mon(&saves[j], &eb, &ek, true)) continue;
            char why[160];
            struct pd_mon m;
            pd_save_box_mon(&saves[i], box, slot, &m);
            const char* no = pd_trade(&a, PD_AT_BOX(box, slot), &bb, PD_AT_BOX(eb, ek), true, why, sizeof why);
            printf("trade %s -> %s: %s (species %d): %s\n", codes[i], codes[j], m.nickname, m.species, no ? no : "done");
            if (!no) {
                struct pd_mon got;
                CHECK(pd_save_box_mon(&saves[j], eb, ek, &got), "%s -> %s: nothing arrived", codes[i], codes[j]);
                struct pd_mon gone;
                CHECK(!pd_save_box_mon(&saves[i], box, slot, &gone), "%s -> %s: still there", codes[i], codes[j]);
                round_trip(&saves[i], codes[i]);
                round_trip(&saves[j], codes[j]);
            }
            pd_save_revert(&saves[i]);
            pd_save_revert(&saves[j]);
            // A trade evolution: declined (the game's B) it arrives as it is, else as what it becomes.
            for (int at = PD_AT_PARTY(5); at < PD_SAVE_BOXES * PD_SAVE_BOX_SLOTS; at++) {
                int into = pd_trade_evolution(&a, at, &bb);
                if (!into || pd_trade_blocker(&a, at, &bb, why, sizeof why) || !find_mon(&saves[j], &eb, &ek, true)) continue;
                struct pd_mon before, got;
                pd_save_mon_at(&saves[i], at, &before);
                for (int evolve = 0; evolve < 2; evolve++) {
                    no = pd_trade(&a, at, &bb, PD_AT_BOX(eb, ek), evolve, why, sizeof why);
                    CHECK(!no && pd_save_box_mon(&saves[j], eb, ek, &got) && got.species == (evolve ? into : before.species),
                          "%s -> %s: evolve %d gave species %d", codes[i], codes[j], evolve, got.species);
                    printf("trade evolution %s -> %s: %s, evolve %d: species %d\n", codes[i], codes[j], before.nickname, evolve, got.species);
                    pd_save_revert(&saves[i]);
                    pd_save_revert(&saves[j]);
                }
                break;
            }
            // The party's last Pokémon to the other's party (when there's room): refused if it's the only one.
            int ci = pd_save_party_count(&saves[i]), cj = pd_save_party_count(&saves[j]);
            // Party for party: the second of each.
            if (ci > 1 && cj > 1) {
                struct pd_mon mi, mj, gi, gj;
                pd_save_party_mon(&saves[i], 1, &mi);
                pd_save_party_mon(&saves[j], 1, &mj);
                no = pd_trade(&a, PD_AT_PARTY(1), &bb, PD_AT_PARTY(1), true, why, sizeof why);
                printf("party swap %s <-> %s: %s for %s: %s\n", codes[i], codes[j], mi.nickname, mj.nickname, no ? no : "done");
                if (!no) {
                    CHECK(pd_save_party_mon(&saves[i], 1, &gi) && pd_save_party_mon(&saves[j], 1, &gj) && gj.personality == mi.personality &&
                          gi.personality == mj.personality, "party swap: the Pokémon");
                    // Their HP kept, moved only by as much as a stat the game would work out again changed (CalculateMonStats).
                    printf("  HP %d/%d -> %d/%d, %d/%d -> %d/%d\n", mi.hp, mi.maxHp, gj.hp, gj.maxHp, mj.hp, mj.maxHp, gi.hp, gi.maxHp);
                    CHECK(gj.maxHp - gj.hp == mi.maxHp - mi.hp && gi.maxHp - gi.hp == mj.maxHp - mj.hp, "party swap: HP lost kept");
                    round_trip(&saves[i], codes[i]);
                    round_trip(&saves[j], codes[j]);
                }
                pd_save_revert(&saves[i]);
                pd_save_revert(&saves[j]);
            }
            if (ci > 0 && cj < 6) {
                no = pd_trade(&a, PD_AT_PARTY(ci - 1), &bb, PD_AT_PARTY(cj), true, why, sizeof why);
                printf("party trade %s -> %s: %s\n", codes[i], codes[j], no ? no : "done");
                if (ci == 1) CHECK(no != NULL, "%s: its only Pokémon traded away", codes[i]);
                if (!no) {
                    CHECK(pd_save_party_count(&saves[i]) == ci - 1 && pd_save_party_count(&saves[j]) == cj + 1, "party counts after a trade");
                    round_trip(&saves[i], codes[i]);
                    round_trip(&saves[j], codes[j]);
                }
            }
            pd_save_revert(&saves[i]);
            pd_save_revert(&saves[j]);
        }
    }
    printf(failures ? "%d failures\n" : "save: all checks passed\n", failures);
    return failures != 0;
}
