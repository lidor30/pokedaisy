// What a game looked like when it was last left, for the library's top screen (the app's SnapshotCache): the trainer, the
// place, the party with its icons as the game drew them. The host writes it when a session ends.
#include <stdio.h>
#include <string.h>

#include "pd_game.h"
#include "pd_icon.h"
#include "pd_library.h"
#include "pd_snapshot.h"

bool pd_game_card_fill(struct pd_game_card* card, const struct pd_game* g, const struct pd_snapshot* s, uint32_t now) {
    memset(card, 0, sizeof(*card));
    if (!s || !s->valid || s->partyCount <= 0) return false;
    card->magic = PD_CARD_MAGIC;
    card->savedAt = now;
    if (s->cardOk) pd_gen3_text(s->playerName, sizeof s->playerName, card->trainer, sizeof card->trainer);
    card->gender = s->gender;
    card->trainerId = s->cardOk ? s->trainerId : -1;
    card->playHours = s->cardOk ? s->playHours : -1;
    card->playMinutes = s->cardOk ? s->playMinutes : 0;
    card->money = s->money >= 0 ? (int32_t) s->money : -1;
    const char* place = pd_mapsec_name(g, s->mapsec);
    snprintf(card->place, sizeof card->place, "%s", place ? place : "");
    card->badges = 0;
    card->badgeCount = -1;
    if (g->cfg && g->cfg->badgeFlag > 0 && s->flagsOk) {
        card->badgeCount = 0;
        for (int i = 0; i < 8; i++)
            if (pd_flag(s, g->cfg->badgeFlag + i)) card->badges |= 1 << i, card->badgeCount++;
    }
    card->dexCaught = -1;
    if (s->dexOk) {
        card->dexCaught = 0;
        for (int i = 0; i < s->dexCount && i / 8 < PD_DEX_BYTES; i++) card->dexCaught += s->dexCaught[i / 8] >> (i % 8) & 1;
    }
    card->partyCount = s->partyCount > 6 ? 6 : s->partyCount;
    for (int i = 0; i < card->partyCount; i++) {
        const struct pd_mon* m = &s->party[i];
        card->party[i].species = (uint16_t) m->species;
        card->party[i].level = (uint8_t) m->level;
        card->party[i].shiny = m->shiny;
        card->party[i].egg = m->egg;
        snprintf(card->party[i].name, sizeof card->party[i].name, "%s", m->nickname);
        const uint32_t* px = pd_mon_icon_pixels(g, m->species, m->shiny);
        for (int k = 0; px && k < PD_CARD_ICON * PD_CARD_ICON; k++)
            card->icons[i][k] = (px[k] & 0xFF000000u) == 0xFF000000u ? 0 : (px[k] | 0xFF000000u);
    }
    return true;
}
