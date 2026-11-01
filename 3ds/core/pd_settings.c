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
        else if (!strcmp(key, "guide")) int_in(value, 0, 0xFFFF, &s->guideNotice);
        else if (!strcmp(key, "aspect")) int_in(value, 0, PD_ASPECTS - 1, &s->aspect);
        else if (!strcmp(key, "autozoom")) {
            int on;
            if (int_in(value, 0, 1, &on)) s->autoZoom = on;
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
        } else if (!strcmp(key, "hudlayout")) int_in(value, 0, PD_HUD_LAYOUTS - 1, &s->hudLayout);
        else if (!strcmp(key, "hudlook")) int_in(value, 0, PD_HUD_LOOKS - 1, &s->hudLook);
        else if (!strcmp(key, "hudsize")) int_in(value, 0, PD_HUD_SIZES - 1, &s->hudSize);
        else if (!strcmp(key, "scaling")) int_in(value, 0, PD_SCALINGS - 1, &s->scaling);
        else {
            int on;
            bool* b = !strcmp(key, "ffaudio") ? &s->ffAudio : !strcmp(key, "rewind") ? &s->rewind
                    : !strcmp(key, "runindoors") ? &s->runIndoors : NULL;
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
    fprintf(f, "screen=%d\nff=%d\nguide=%d\naspect=%d\nautozoom=%d\nwildoff=%d\ntabs=%d\nhud=%d\nhudhide=%d\n",
            s->screenMode, s->ffSpeed, s->guideNotice, s->aspect, s->autoZoom ? 1 : 0, s->wildOff ? 1 : 0, s->tabBar,
            s->hud ? 1 : 0, s->hudHidden);
    fprintf(f, "hudlayout=%d\nhudlook=%d\nhudsize=%d\n", s->hudLayout, s->hudLook, s->hudSize);
    fprintf(f, "scaling=%d\nffaudio=%d\nrewind=%d\nrunindoors=%d\n", s->scaling, s->ffAudio ? 1 : 0,
            s->rewind ? 1 : 0, s->runIndoors ? 1 : 0);
    if (fclose(f) != 0) return false;
    return rename(tmp, path) == 0;
}
