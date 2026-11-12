// pd_save.c on real saves: each reads, writes back the game's way (and reads back), moves a Pokémon, changes the money,
// and trades. Saves are given on the command line as CODE=path (BPRE=..., BPEE=..., AXVE=...); none are in the repo.
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
        int b, k, eb, ek;
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
            const char* no = pd_trade(&a, box, slot, &bb, eb, ek, why, sizeof why);
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
        }
    }
    printf(failures ? "%d failures\n" : "save: all checks passed\n", failures);
    return failures != 0;
}
