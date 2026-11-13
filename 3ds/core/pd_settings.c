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
    s->hudLayout = PD_HUD_CORNERS;
    s->hudLook = PD_HUD_GLASS;
    s->hudSize = PD_HUD_NORMAL;
    s->scaling = PD_SCALE_INTEGER;
    s->ffAudio = false;
    s->rewind = false;
    s->runIndoors = false;
    s->quickGrid = false;
    s->lcdFilter = false;
    s->quickBtn = PD_BTN_Y;
    s->companionBtn = PD_BTN_X;
    s->hudBtn = PD_BTN_NONE;
    s->hideHints = false;
    s->oneTapMoves = false;
    s->battleGen5 = false;
    s->noBattleInfo = false;
    s->battleInfoFirst = false;
    s->flyClassic = false;
    s->flightAnywhere = false;
    s->flightVehicle = 0;
    s->gameSpeed = 0;
    s->gameVolume = 100;
    s->gameMuted = false;
    s->autoRun = false;
    s->darkMode = false;
    s->ffNormal = false;
    s->cheats = false;
    s->theme = 8; // POKéDAISY
    s->libraryGrid = false;
    s->libraryRecent = false;
}

const int PD_FF_SPEEDS[PD_FF_CHOICES] = {2, 3, 4, 5, 6, 8, 10, 0};

int pd_ff_index(int speed) {
    for (int i = 0; i < PD_FF_CHOICES; i++)
        if (PD_FF_SPEEDS[i] == speed) return i;
    return 0;
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
        else if (!strcmp(key, "ff")) {
            int n;
            if (int_in(value, 0, 10, &n) && PD_FF_SPEEDS[pd_ff_index(n)] == n) s->ffSpeed = n;
        }
        else if (!strcmp(key, "guide")) int_in(value, 0, 0x7FFFFFFF, &s->guideNotice);
        else if (!strcmp(key, "dark")) s->darkMode = atoi(value) != 0;
        else if (!strcmp(key, "ffnormal")) s->ffNormal = atoi(value) != 0;
        else if (!strcmp(key, "cheats")) s->cheats = atoi(value) != 0;
        else if (!strcmp(key, "theme")) int_in(value, 0, 99, &s->theme);
        else if (!strcmp(key, "librarygrid")) s->libraryGrid = atoi(value) != 0;
        else if (!strcmp(key, "libraryrecent")) s->libraryRecent = atoi(value) != 0;
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
        } else if (!strcmp(key, "lcd")) {
            int on;
            if (int_in(value, 0, 1, &on)) s->lcdFilter = on;
        } else if (!strcmp(key, "quickgrid")) {
            int on;
            if (int_in(value, 0, 1, &on)) s->quickGrid = on;
        } else if (!strcmp(key, "hudhide")) {
            int bits;
            if (int_in(value, 0, (1 << PD_HUD_PARTS) - 1, &bits)) s->hudHidden = bits;
        } else if (!strcmp(key, "hudlayout")) int_in(value, 0, PD_HUD_LAYOUTS - 1, &s->hudLayout);
        else if (!strcmp(key, "hudlook")) int_in(value, 0, PD_HUD_LOOKS - 1, &s->hudLook);
        else if (!strcmp(key, "hudsize")) int_in(value, 0, PD_HUD_SIZES - 1, &s->hudSize);
        else if (!strcmp(key, "scaling")) int_in(value, 0, PD_SCALINGS - 1, &s->scaling);
        else if (!strcmp(key, "quickbtn")) int_in(value, 0, PD_BTN_L2 + 1, &s->quickBtn);
        else if (!strcmp(key, "companionbtn")) int_in(value, 0, PD_BTN_L2 + 1, &s->companionBtn);
        else if (!strcmp(key, "hudbtn")) int_in(value, 0, PD_BUTTONS - 1, &s->hudBtn);
        else if (!strcmp(key, "speed")) int_in(value, 0, PD_GAME_SPEED_COUNT - 1, &s->gameSpeed);
        else if (!strcmp(key, "volume")) int_in(value, 20, 100, &s->gameVolume);
        else if (!strcmp(key, "flyon")) int_in(value, 0, 3, &s->flightVehicle);
        else {
            int on;
            bool* b = !strcmp(key, "ffaudio") ? &s->ffAudio : !strcmp(key, "rewind") ? &s->rewind
                    : !strcmp(key, "runindoors") ? &s->runIndoors : !strcmp(key, "nohints") ? &s->hideHints
                    : !strcmp(key, "onetap") ? &s->oneTapMoves : !strcmp(key, "mute") ? &s->gameMuted
                    : !strcmp(key, "autorun") ? &s->autoRun : !strcmp(key, "gen5") ? &s->battleGen5
                    : !strcmp(key, "noinfo") ? &s->noBattleInfo : !strcmp(key, "infofirst") ? &s->battleInfoFirst
                    : !strcmp(key, "flyclassic") ? &s->flyClassic
                    // FLIGHT AREA: "flightanywhere" as the Linux / libretro hosts write it, "flyanywhere" as 3DS builds did
                    : !strcmp(key, "flightanywhere") || !strcmp(key, "flyanywhere") ? &s->flightAnywhere : NULL;
            if (b && int_in(value, 0, 1, &on)) *b = on;
        }
    }
    fclose(f);
    return true;
}

// plorpOS: through a temporary file and a rename, as plorpOS writes it, so neither reads half a file.
bool pd_settings_save(const struct pd_settings* s, const char* path) {
    char tmp[1100];
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    FILE* f = fopen(tmp, "w");
    if (!f) return false;
    fprintf(f, "screen=%d\nff=%d\nguide=%d\naspect=%d\nautozoom=%d\nwildoff=%d\ntabs=%d\nhud=%d\nhudhide=%d\nquickgrid=%d\nlcd=%d\n",
            s->screenMode, s->ffSpeed, s->guideNotice, s->aspect, s->autoZoom ? 1 : 0, s->wildOff ? 1 : 0, s->tabBar,
            s->hud ? 1 : 0, s->hudHidden, s->quickGrid ? 1 : 0, s->lcdFilter ? 1 : 0);
    fprintf(f, "hudlayout=%d\nhudlook=%d\nhudsize=%d\n", s->hudLayout, s->hudLook, s->hudSize);
    fprintf(f, "scaling=%d\nffaudio=%d\nrewind=%d\nrunindoors=%d\n", s->scaling, s->ffAudio ? 1 : 0,
            s->rewind ? 1 : 0, s->runIndoors ? 1 : 0);
    fprintf(f, "quickbtn=%d\ncompanionbtn=%d\nhudbtn=%d\n", s->quickBtn, s->companionBtn, s->hudBtn);
    fprintf(f, "dark=%d\ntheme=%d\nffnormal=%d\ncheats=%d\n", s->darkMode, s->theme, s->ffNormal, s->cheats);
    fprintf(f, "librarygrid=%d\nlibraryrecent=%d\n", s->libraryGrid, s->libraryRecent);
    fprintf(f, "speed=%d\nvolume=%d\nnohints=%d\nonetap=%d\nmute=%d\nautorun=%d\nperf=%d\n", s->gameSpeed,
            s->gameVolume, s->hideHints, s->oneTapMoves, s->gameMuted, s->autoRun, s->showPerf ? 1 : 0);
    fprintf(f, "gen5=%d\nnoinfo=%d\ninfofirst=%d\n", s->battleGen5 ? 1 : 0, s->noBattleInfo ? 1 : 0, s->battleInfoFirst ? 1 : 0);
    fprintf(f, "flyclassic=%d\nflightanywhere=%d\nflyon=%d\n", s->flyClassic ? 1 : 0, s->flightAnywhere ? 1 : 0, s->flightVehicle);
    if (fclose(f) != 0) return false;
    return rename(tmp, path) == 0;
}
