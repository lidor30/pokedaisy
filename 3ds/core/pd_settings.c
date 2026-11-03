#include "pd_settings.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void pd_settings_defaults(struct pd_settings* s) {
    s->screenMode = PD_SCREEN_SHARP;
    s->ffSpeed = PD_FF_MIN;
    s->guideNotice = 0;
    s->aspect = PD_ASPECT_ORIGINAL;
    s->autoZoom = true;
    s->wildOff = false;
    s->tabBar = PD_TAB_BAR_DEFAULT;
    s->hud = true;
    s->hudHidden = 0;
    s->hideHints = false;
    s->oneTapMoves = false;
    s->gameSpeed = 0;
    s->gameVolume = 100;
    s->gameMuted = false;
    s->autoRun = false;
    s->runIndoors = false;
    s->darkMode = false;
    s->ffNormal = false;
    s->cheats = false;
    s->theme = 8; // POKéDAISY
}

static bool int_in(const char* v, int lo, int hi, int* out) {
    char* end;
    long n = strtol(v, &end, 10);
    if (end == v || n < lo || n > hi) return false;
    *out = (int) n;
    return true;
}

bool pd_settings_load(struct pd_settings* s, const char* path) {
    pd_settings_defaults(s);
    FILE* f = fopen(path, "r");
    if (!f) return false;
    char line[128];
    while (fgets(line, sizeof(line), f)) {
        char* eq = strchr(line, '=');
        if (!eq) continue;
        *eq = 0;
        const char* key = line;
        const char* value = eq + 1;
        if (!strcmp(key, "screen")) int_in(value, 0, PD_SCREEN_MODES - 1, &s->screenMode);
        else if (!strcmp(key, "dark")) s->darkMode = atoi(value) != 0;
        else if (!strcmp(key, "ffnormal")) s->ffNormal = atoi(value) != 0;
        else if (!strcmp(key, "cheats")) s->cheats = atoi(value) != 0;
        else if (!strcmp(key, "theme")) int_in(value, 0, 99, &s->theme);
        else if (!strcmp(key, "ff")) int_in(value, PD_FF_MIN, PD_FF_MAX, &s->ffSpeed);
        else if (!strcmp(key, "guide")) int_in(value, 0, 0xFFFF, &s->guideNotice);
        else if (!strcmp(key, "aspect")) int_in(value, 0, PD_ASPECTS - 1, &s->aspect);
        else if (!strcmp(key, "autozoom")) {
            int on;
            if (int_in(value, 0, 1, &on)) s->autoZoom = on;
        } else if (!strcmp(key, "perf")) {
            int on;
            if (int_in(value, 0, 1, &on)) s->showPerf = on;
        } else if (!strcmp(key, "wildoff")) {
            int on;
            if (int_in(value, 0, 1, &on)) s->wildOff = on;
        } else if (!strcmp(key, "tabs")) {
            int bits;
            if (int_in(value, 1, (1 << PD_TAB_COUNT) - 1, &bits)) s->tabBar = bits;
        } else if (!strcmp(key, "hud")) {
            int on;
            if (int_in(value, 0, 1, &on)) s->hud = on;
        } else if (!strcmp(key, "hudhide")) {
            int bits;
            if (int_in(value, 0, (1 << PD_HUD_PARTS) - 1, &bits)) s->hudHidden = bits;
        } else if (!strcmp(key, "speed")) {
            int_in(value, 0, PD_GAME_SPEED_COUNT - 1, &s->gameSpeed);
        } else if (!strcmp(key, "volume")) {
            int_in(value, 20, 100, &s->gameVolume);
        } else {
            static const char* const FLAGS[] = { "nohints", "onetap", "mute", "autorun", "runindoors" };
            bool* const out[] = { &s->hideHints, &s->oneTapMoves, &s->gameMuted, &s->autoRun, &s->runIndoors };
            for (int i = 0; i < 5; i++) {
                int on;
                if (!strcmp(key, FLAGS[i]) && int_in(value, 0, 1, &on)) *out[i] = on;
            }
        }
    }
    fclose(f);
    return true;
}

bool pd_settings_save(const struct pd_settings* s, const char* path) {
    FILE* f = fopen(path, "w");
    if (!f) return false;
    fprintf(f, "screen=%d\nff=%d\nguide=%d\naspect=%d\nautozoom=%d\nwildoff=%d\ntabs=%d\nhud=%d\nhudhide=%d\n",
            s->screenMode, s->ffSpeed, s->guideNotice, s->aspect, s->autoZoom ? 1 : 0, s->wildOff ? 1 : 0, s->tabBar,
            s->hud ? 1 : 0, s->hudHidden);
    fprintf(f, "dark=%d\ntheme=%d\nffnormal=%d\ncheats=%d\n", s->darkMode, s->theme, s->ffNormal, s->cheats);
    fprintf(f, "speed=%d\nvolume=%d\nnohints=%d\nonetap=%d\nmute=%d\nautorun=%d\nrunindoors=%d\n", s->gameSpeed,
            s->gameVolume, s->hideHints, s->oneTapMoves, s->gameMuted, s->autoRun, s->runIndoors);
    fprintf(f, "perf=%d\n", s->showPerf ? 1 : 0);
    return fclose(f) == 0;
}
