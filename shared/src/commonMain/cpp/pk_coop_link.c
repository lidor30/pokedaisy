// PokeDaisy — the co-op link bridge on retail English Emerald (plan 6; see pk_coop.h): the game's own link multi battle between
// two devices. link.c's block layer is replaced while a link battle runs (scripts/coop_link_emerald.s): SendBlock queues each block
// in the bridge's outbox (pk_coop_link_take hands it to the app) and the app's blocks from the other player go into the game's
// own receive buffer and received flag (pk_coop_link_give), as the cable's serial interrupt would. Plain C over `struct mCore*`,
// no JNI: native-capture/mgba_dump.c compiles this very file in and runs two cores against each other (`pair`, `linkrun`).
//
// The battle is the tower's link multi (cable_club.c's USING_BATTLE_TOWER flags: two linked players against two AI trainers, the
// master - the host, multiplayer id 0 - running the AI): it is the one link battle with AI trainers on the master, which is what
// a joined battle with trainers needs, and it needs no fake link players. The facility's own rules it brings (it counts as a
// Frontier battle: no EXP, no money, the enemies' levels set, its end flow) are undone one by one by the later tasks.
//
// Everything goes through pk_cheats (the code block and the head patches), so the Poller's hash and RetroAchievements' still see
// the file's bytes, and reset / uninstall put each byte back. Install is all or nothing: every function the bridge replaces or
// calls is CRC-checked against the bus first, then the code area (0x08FFD000-0x08FFDFFF) must be free of other blocks.

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <mgba/core/core.h>

#include "pk_cheats.h"
#include "pk_coop.h"
#include "pk_coop_emerald.h"

#define LINK_CODE_AREA      0x08FFD000u
#define LINK_CODE_AREA_SIZE 0x1000u
#define LINK_ACTION_RUN 3   // pokeemerald's B_ACTION_RUN: the highest action a battler can choose (USE_MOVE 0, USE_ITEM 1, SWITCH 2, RUN 3)

static const struct { const char* name; uint32_t addr, crc; } sLinkCrcs[] = {
#define LINK_FN(f) { #f, COOP_EM_##f, COOP_EM_CRC_##f }
    LINK_FN(SendBlock),
    LINK_FN(IsLinkTaskFinished),
    LINK_FN(GetMultiplayerId),
    LINK_FN(HandleLinkConnection),
    LINK_FN(OpenLink),
    LINK_FN(CloseLink),
    LINK_FN(SetCloseLinkCallback),
    LINK_FN(HasLinkErrorOccurred),
    LINK_FN(CreateNPCTrainerParty),
    LINK_FN(CleanupOverworldWindowsAndTilemaps),
    LINK_FN(SetMainCallback2),
    LINK_FN(ArePlayerFieldControlsLocked),
    LINK_FN(CB2_InitBattle),
    LINK_FN(CB2_HandleStartMultiPartnerBattle),
    LINK_FN(CB2_Overworld),
    LINK_FN(CB2_ReturnToField),
    LINK_FN(SaveLinkPlayers),
    LINK_FN(GetFrontierTrainerName),   /* task 2 */
    LINK_FN(GetFrontierTrainerFrontSpriteId),
    LINK_FN(GetFrontierOpponentClass),
    LINK_FN(GetBattleTowerTrainerLanguage),
    LINK_FN(CopyFrontierTrainerText),
    LINK_FN(TrySetLinkBattleTowerEnemyPartyLevel),
    LINK_FN(TaskDummy),
    LINK_FN(BattleAI_SetupAIData),
    LINK_FN(FindLinkBattleMaster),   // task 3
    LINK_FN(PlayBattleBGM),
    LINK_FN(DestroyTask),
    LINK_FN(Cmd_endlinkbattle),
    LINK_FN(HandleEndTurn_FinishBattle),
    LINK_FN(HandleEndTurn_RanFromBattle),
    LINK_FN(HandleEndTurn_MonFled),
    LINK_FN(IsRunningFromBattleImpossible),
    LINK_FN(HandleAction_Run),
    LINK_FN(InitLinkBattleVsScreen),
    LINK_FN(BattleIntroDrawTrainersOrMonsSprites),
    LINK_FN(TryRunFromBattle),
    LINK_FN(CB2_EndWildBattle),        // task 5: the joined battle's savedCallback (field return, or the white-out)
    LINK_FN(CB2_InitEndLinkBattle),    // task 5: its beq is patched
#undef LINK_FN
};

// The functions the bridge replaces: each one's first 8 bytes as the file has them, ours, and the routine it jumps to.
static const struct { const char* name; uint32_t fn, orig0, orig1, new0, new1; } sLinkHooks[] = {
#define LINK_HOOK(f) { #f, COOP_EM_##f, COOP_EM_LINK_HEAD_##f##_ORIG0, COOP_EM_LINK_HEAD_##f##_ORIG1, \
                       COOP_EM_LINK_HEAD_##f##_NEW0, COOP_EM_LINK_HEAD_##f##_NEW1 }
    LINK_HOOK(SendBlock),
    LINK_HOOK(IsLinkTaskFinished),
    LINK_HOOK(GetMultiplayerId),
    LINK_HOOK(HandleLinkConnection),
    LINK_HOOK(OpenLink),
    LINK_HOOK(CloseLink),
    LINK_HOOK(SetCloseLinkCallback),
    LINK_HOOK(HasLinkErrorOccurred),
    LINK_HOOK(GetFrontierTrainerName),   /* task 2 */
    LINK_HOOK(GetFrontierTrainerFrontSpriteId),
    LINK_HOOK(GetFrontierOpponentClass),
    LINK_HOOK(GetBattleTowerTrainerLanguage),
    LINK_HOOK(CopyFrontierTrainerText),
    LINK_HOOK(TrySetLinkBattleTowerEnemyPartyLevel),
    LINK_HOOK(FindLinkBattleMaster),   // task 3
#undef LINK_HOOK
};
#define LINK_HOOK_COUNT ((int) (sizeof(sLinkHooks) / sizeof(sLinkHooks[0])))

// The code was assembled for this RAM layout (scripts/coop_link_emerald.s, carried in the generated header).
_Static_assert(COOP_EM_ASM_LINK_RAM == PK_COOP_LINK_RAM, "coop_link_emerald.s: ram address");
_Static_assert(COOP_EM_ASM_LINK_ACTIVE == offsetof(struct pk_coop_link_ram, active), "coop_link_emerald.s: active");
_Static_assert(COOP_EM_ASM_LINK_MYID == offsetof(struct pk_coop_link_ram, myId), "coop_link_emerald.s: myId");
_Static_assert(COOP_EM_ASM_LINK_ENDED == offsetof(struct pk_coop_link_ram, ended), "coop_link_emerald.s: ended");
_Static_assert(COOP_EM_ASM_LINK_HEAD == offsetof(struct pk_coop_link_ram, head), "coop_link_emerald.s: head");
_Static_assert(COOP_EM_ASM_LINK_KIND == offsetof(struct pk_coop_link_ram, kind), "coop_link_emerald.s: kind");
_Static_assert(COOP_EM_ASM_LINK_TAIL == offsetof(struct pk_coop_link_ram, tail), "coop_link_emerald.s: tail");
_Static_assert(COOP_EM_ASM_LINK_OVERRUN == offsetof(struct pk_coop_link_ram, overrun), "coop_link_emerald.s: overrun");
_Static_assert(COOP_EM_ASM_LINK_OUT == PK_COOP_LINK_OUT, "coop_link_emerald.s: outbox offset");
_Static_assert(COOP_EM_ASM_LINK_ESIZE == PK_COOP_LINK_ENTRY, "coop_link_emerald.s: entry size");
_Static_assert(COOP_EM_ASM_LINK_OUT_N == PK_COOP_LINK_OUT_N, "coop_link_emerald.s: outbox entries");
_Static_assert(COOP_EM_ASM_LINK_BLOCK_MAX == PK_COOP_LINK_BLOCK_MAX, "coop_link_emerald.s: block max");
_Static_assert(COOP_EM_BLOCK_BUFFER_SIZE == PK_COOP_LINK_BLOCK_MAX, "the game's block buffers are BLOCK_BUFFER_SIZE bytes");
_Static_assert(sizeof(struct pk_coop_link_ram) <= PK_COOP_LINK_OUT, "the bridge's header runs into its outbox");
_Static_assert(PK_COOP_LINK_OUT + PK_COOP_LINK_OUT_N * PK_COOP_LINK_ENTRY <= PK_COOP_LINK_BACKUP, "the outbox runs into the party backup");
_Static_assert(PK_COOP_LINK_BACKUP + 6 * 100 <= PK_COOP_LINK_RAM_SIZE, "the party backup is past the bridge's RAM");
_Static_assert(PK_COOP_LINK_RAM + PK_COOP_LINK_RAM_SIZE <= PK_COOP_RAM, "the bridge's RAM runs into co-op's");
_Static_assert(COOP_EM_OFF_MON_SIZE == 100, "the link's party blocks are struct Pokemon");
_Static_assert(sizeof(COOP_EM_LINK_CODE) <= LINK_CODE_AREA_SIZE, "the bridge's block is bigger than its code area");
_Static_assert(COOP_EM_LINK_CODE_ADDR >= LINK_CODE_AREA && COOP_EM_LINK_CODE_ADDR + sizeof(COOP_EM_LINK_CODE) <= LINK_CODE_AREA + LINK_CODE_AREA_SIZE,
               "the bridge's block is outside its code area");

// The bridge's state outside the game (mgba_dump's pair mode keeps one per core).
struct pk_link_ctx {
    pk_coop_call_fn call;
    int installed;       // the code block is in
    int patchesIn;       // head patch halves in (two per hook)
    int begun;           // pk_coop_link_begin went through, reset hasn't run
    int wildPatches;     // task 3: the wild kind's code patches in (pk_coop_link_wild.c)
    int endPatchIn;      // task 5: CB2_InitEndLinkBattle's beq is a nop
    struct pk_coop_link_setup setup;   // task 5: what begin was given (the end's prize and flags read the trainers from it)
    const char* refused;
    char refusedBuf[32];
};
static struct pk_link_ctx sLink = { .refused = "" };

#define LRAM(field) (PK_COOP_LINK_RAM + (uint32_t) offsetof(struct pk_coop_link_ram, field))
// The count of malformed blocks `give` refused: a u16 in the bridge's header past struct pk_coop_link_ram (which ends before 0x30; the outbox starts at 0x40).
#define LRAM_MALFORMED (PK_COOP_LINK_RAM + 0x30u)
_Static_assert(sizeof(struct pk_coop_link_ram) <= 0x30, "the malformed counter sits past the bridge's struct");

// The last blocks the bridge refused as malformed (give) or could not classify (take), kept for the app's log: the check that said no
// is the source line of its `return LBAD` (sLinkWhy), 0 when a check without one did. Cleared by the next begin only, never by a reset.
// BATTLE_TYPE_TOWER_LINK_MULTI (1 << 23): the Frontier's link multi against two AI trainers, which is what this battle is. Without it HasNoMonsToSwitch /
// checkteamslost look at the wrong half of gEnemyParty for the foes (battler 1 reads slots 3-5, the AI's own loops read 0-2), so a foe whose
// own Pokemon were all down was sent a replacement from the other foe's half ("sent out ???" with party slot 6) and the battle hung.
#define LINK_TOWER_LINK_MULTI 0x00800000u

#define LINK_BAD_N 4
static uint16_t sLinkWhy;
#define LBAD (sLinkWhy = (uint16_t) __LINE__, 0)
static struct { uint16_t why; uint8_t where, kind; uint16_t len; uint8_t d[PK_COOP_LINK_BLOCK_MAX + PK_COOP_LINK_WIRE_HEADER]; } sBad[LINK_BAD_N];
static int sBadCount;

static void link_note_bad(int where, int kind, const uint8_t* d, int len) {
    int i = sBadCount++ % LINK_BAD_N;
    if (len > (int) sizeof(sBad[i].d)) len = (int) sizeof(sBad[i].d);
    sBad[i].why = sLinkWhy; sBad[i].where = (uint8_t) where; sBad[i].kind = (uint8_t) kind; sBad[i].len = (uint16_t) len;
    if (d && len > 0) memcpy(sBad[i].d, d, (size_t) len);
}

int pk_coop_link_bad_blocks(uint8_t* out, int cap) {
    int n = sBadCount < LINK_BAD_N ? sBadCount : LINK_BAD_N, w = 0;
    for (int k = 0; k < n; k++) {
        int i = (sBadCount - n + k) % LINK_BAD_N;
        if (w + 6 + sBad[i].len > cap) break;
        out[w++] = (uint8_t) sBad[i].why; out[w++] = (uint8_t) (sBad[i].why >> 8); out[w++] = sBad[i].where; out[w++] = sBad[i].kind;
        out[w++] = (uint8_t) sBad[i].len; out[w++] = (uint8_t) (sBad[i].len >> 8);
        memcpy(out + w, sBad[i].d, sBad[i].len);
        w += sBad[i].len;
    }
    return w;
}

static uint32_t link_crc32(const uint8_t* p, int len) {
    uint32_t c = 0xFFFFFFFFu;
    for (int i = 0; i < len; i++) {
        c ^= p[i];
        for (int k = 0; k < 8; k++) {
            c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
        }
    }
    return ~c;
}

static void link_put32(uint8_t* p, uint32_t v) {
    for (int b = 0; b < 4; b++) p[b] = (uint8_t) (v >> (8 * b));
}

static void link_zero_ram(struct mCore* core) {
    for (uint32_t a = PK_COOP_LINK_RAM; a < PK_COOP_LINK_RAM + PK_COOP_LINK_RAM_SIZE; a += 4) {
        core->busWrite32(core, a, 0);
    }
}

static uint32_t link_read(struct mCore* core, uint32_t addr, int len) {
    uint32_t v = 0;
    for (int b = 0; b < len; b++) v |= (uint32_t) core->busRead8(core, addr + (uint32_t) b) << (8 * b);
    return v;
}

static void link_write(struct mCore* core, uint32_t addr, uint32_t v, int len) {
    for (int b = 0; b < len; b++) core->busWrite8(core, addr + (uint32_t) b, (uint8_t) (v >> (8 * b)));
}

// --- the head patches -------------------------------------------------------------------------------------------------------

// One half (4 bytes) of a hook's head patch: where the file holds the original word, put ours. Another option's patch on these
// bytes is a refusal (pk_cheats_patch would answer 1 without ours going in, and taking ours out by range would take theirs out).
static int link_patch_in(struct mCore* core, int hook, int half) {
    uint8_t expect[4], value[4];
    link_put32(expect, half ? sLinkHooks[hook].orig1 : sLinkHooks[hook].orig0);
    link_put32(value, half ? sLinkHooks[hook].new1 : sLinkHooks[hook].new0);
    uint32_t addr = sLinkHooks[hook].fn + 4u * (uint32_t) half;
    return !pk_cheats_patch_overlaps(addr, 4) && pk_cheats_patch(core, addr, expect, 4, addr, value, 4);
}

// task 5: CB2_InitEndLinkBattle's frontier-branch beq (0xD011) becomes a nop: every link end is the game's normal end.
#define LINK_END_BEQ_NEW 0x46C0u

static int link_end_patch_in(struct mCore* core) {
    uint8_t expect[2] = { (uint8_t) COOP_EM_LINK_END_BEQ_INSN, (uint8_t) (COOP_EM_LINK_END_BEQ_INSN >> 8) };
    uint8_t value[2] = { (uint8_t) LINK_END_BEQ_NEW, (uint8_t) (LINK_END_BEQ_NEW >> 8) };
    if (pk_cheats_patch_overlaps(COOP_EM_LINK_END_BEQ, 2) || !pk_cheats_patch(core, COOP_EM_LINK_END_BEQ, expect, 2, COOP_EM_LINK_END_BEQ, value, 2)) {
        return 0;
    }
    sLink.endPatchIn = 1;
    return 1;
}

static void link_patches_out(struct mCore* core) {
    pk_coop_link_start_patches_out(core);   // task 2
    if (sLink.endPatchIn) {
        pk_cheats_unpatch_range(core, COOP_EM_LINK_END_BEQ, 2);
        sLink.endPatchIn = 0;
    }
    for (int i = sLink.patchesIn - 1; i >= 0; i--) {
        pk_cheats_unpatch_range(core, sLinkHooks[i / 2].fn + 4u * (uint32_t) (i % 2), 4);
    }
    sLink.patchesIn = 0;
}

static int link_patches_in(struct mCore* core) {
    sLink.patchesIn = 0;
    for (int i = 0; i < LINK_HOOK_COUNT * 2; i++) {
        if (!link_patch_in(core, i / 2, i % 2)) {
            link_patches_out(core);
            snprintf(sLink.refusedBuf, sizeof(sLink.refusedBuf), "patch %08X", (unsigned) (sLinkHooks[i / 2].fn + 4u * (uint32_t) (i % 2)));
            sLink.refused = sLink.refusedBuf;
            return 0;
        }
        sLink.patchesIn = i + 1;
    }
    if (!pk_coop_link_start_patches_in(core)) {   // task 2: the AI flag choice
        link_patches_out(core);
        sLink.refused = "patch ai";
        return 0;
    }
    if (!link_end_patch_in(core)) {
        link_patches_out(core);
        snprintf(sLink.refusedBuf, sizeof(sLink.refusedBuf), "patch %08X", (unsigned) COOP_EM_LINK_END_BEQ);
        sLink.refused = sLink.refusedBuf;
        return 0;
    }
    return 1;
}

void pk_coop_link_original_bytes(uint32_t addr, uint8_t* dst, int len) {
    for (int h = 0; h < sLink.patchesIn / 2 + (sLink.patchesIn % 2); h++) {
        uint8_t orig[8];
        link_put32(orig, sLinkHooks[h].orig0);
        link_put32(orig + 4, sLinkHooks[h].orig1);
        for (uint32_t b = 0; b < 8; b++) {
            uint32_t a = sLinkHooks[h].fn + b;
            if (a >= addr && a - addr < (uint32_t) len) {
                dst[a - addr] = orig[b];
            }
        }
    }
    if (sLink.patchesIn) {
        pk_coop_link_rewards_original_bytes(addr, dst, len);   // task 4: its head patches go in with ours
    }
    if (sLink.endPatchIn) {   // task 5
        uint8_t orig[2] = { (uint8_t) COOP_EM_LINK_END_BEQ_INSN, (uint8_t) (COOP_EM_LINK_END_BEQ_INSN >> 8) };
        for (uint32_t b = 0; b < 2; b++) {
            uint32_t a = COOP_EM_LINK_END_BEQ + b;
            if (a >= addr && a - addr < (uint32_t) len) {
                dst[a - addr] = orig[b];
            }
        }
    }
}

// --- install ----------------------------------------------------------------------------------------------------------------

static int link_in_place(void) {
    return sLink.installed && pk_cheats_block_overlaps(COOP_EM_LINK_CODE_ADDR, (uint32_t) sizeof(COOP_EM_LINK_CODE));
}

int pk_coop_link_install(struct mCore* core, pk_coop_call_fn call) {
    if (sLink.installed) {
        // Already in - unless the app's all-out ROM calls took the block out since: then it goes in from scratch.
        if (core && link_in_place()) {
            sLink.call = call;
            return 1;
        }
        if (core) {
            pk_coop_link_reset(core);
            pk_cheats_unblock_range(core, LINK_CODE_AREA, LINK_CODE_AREA_SIZE);
        }
        sLink.installed = 0;
        sLink.begun = 0;
        sLink.patchesIn = 0;
        sLink.call = NULL;
    }
    if (!core || core->platform(core) != mPLATFORM_GBA) {
        sLink.refused = "not a GBA game";
        return 0;
    }
    for (size_t i = 0; i < sizeof(sLinkCrcs) / sizeof(sLinkCrcs[0]); i++) {
        uint8_t code[32];
        for (int b = 0; b < 32; b++) code[b] = (uint8_t) core->busRead8(core, sLinkCrcs[i].addr + (uint32_t) b);
        if (link_crc32(code, 32) != sLinkCrcs[i].crc) {
            sLink.refused = sLinkCrcs[i].name;
            return 0;
        }
    }
    if (pk_cheats_block_overlaps(LINK_CODE_AREA, LINK_CODE_AREA_SIZE)) {
        sLink.refused = "block overlap";
        return 0;
    }
    {
        static uint8_t blank[sizeof(COOP_EM_LINK_CODE)];
        memset(blank, 0xFF, sizeof(blank));
        if (!pk_cheats_block(core, COOP_EM_LINK_CODE_ADDR, COOP_EM_LINK_CODE, (int) sizeof(COOP_EM_LINK_CODE),
                             link_crc32(blank, (int) sizeof(blank)))) {
            sLink.refused = "code block";
            return 0;
        }
    }
    link_zero_ram(core);
    sLink.call = call;
    sLink.installed = 1;
    sLink.begun = 0;
    sLink.patchesIn = 0;
    sLink.refused = "";
    return 1;
}

void pk_coop_link_uninstall(struct mCore* core) {
    if (!sLink.installed || !core) {
        return;
    }
    pk_coop_story_disarm(core);   // task 6: its head patch jumps into the code block that goes out here
    pk_coop_link_reset(core);
    pk_cheats_unblock_range(core, LINK_CODE_AREA, LINK_CODE_AREA_SIZE);
    link_zero_ram(core);
    sLink.installed = 0;
    sLink.call = NULL;
}

const char* pk_coop_link_refused(void) {
    return sLink.refused;
}

int pk_coop_link_installed(void) {
    return sLink.installed;
}

void pk_coop_link_forget(void) {
    memset(&sLink, 0, sizeof(sLink));
    sLink.refused = "";
}

// --- begin ------------------------------------------------------------------------------------------------------------------

#define LINK_PARTY_BYTES (6 * 100)
#define LINK_NAME_LEN 8   // PLAYER_NAME_LENGTH + 1
#define LINK_FACILITY_TRAINER_A 0u   // Frontier trainer ids (below FRONTIER_TRAINERS_COUNT, 300) the tower flow reads the opponents' names and pictures by
#define LINK_FACILITY_TRAINER_B 1u

static uint32_t link_call(struct mCore* core, uint32_t fn, uint32_t a, uint32_t b, uint32_t c) {
    const uint32_t args[4] = { a, b, c, 0 };
    return sLink.call ? sLink.call(core, fn, args) : 0xFFFFFFFFu;
}

// A Pokémon the battle can send out: a species, not an egg, HP left (the box flags and the HP are plaintext in the struct).
static int link_able(struct mCore* core, uint32_t mon) {
    int flags = core->busRead8(core, mon + COOP_EM_OFF_BOX_MON_FLAGS);
    int hp = (int) link_read(core, mon + COOP_EM_OFF_MON_HP, 2);
    return (flags & 0x02) && !(flags & 0x04) && !(flags & 0x01) && hp > 0;
}

// The party the link battle sends: the first three able Pokémon (backed up; reset puts the whole party back).
static int link_trim_party(struct mCore* core, const uint8_t* picks) {
    uint8_t mons[LINK_PARTY_BYTES];
    int count = core->busRead8(core, COOP_EM_gPlayerPartyCount);
    if (count > 6) count = 6;
    for (uint32_t i = 0; i < LINK_PARTY_BYTES; i++) {
        uint8_t b = (uint8_t) core->busRead8(core, COOP_EM_gPlayerParty + i);
        mons[i] = b;
        core->busWrite8(core, PK_COOP_LINK_RAM + PK_COOP_LINK_BACKUP + i, b);
    }
    core->busWrite8(core, LRAM(backupCount), (uint8_t) count);
    core->busWrite8(core, LRAM(backupValid), 1);
    int n = 0;
    uint8_t trimmed[LINK_PARTY_BYTES];
    memset(trimmed, 0, sizeof(trimmed));
    if (picks && picks[0]) {
        // task 6 (a story battle): the party screen's picks, in order (1-based slots), each one that can still fight, once
        for (int p = 0; p < 3 && picks[p]; p++) {
            int i = picks[p] - 1, dup = 0;
            for (int q = 0; q < p; q++) dup |= picks[q] == picks[p];
            if (i < count && !dup && link_able(core, COOP_EM_gPlayerParty + (uint32_t) i * 100)) {
                memcpy(trimmed + n * 100, mons + i * 100, 100);
                n++;
            }
        }
    } else
    for (int i = 0; i < count && n < 3; i++) {
        if (link_able(core, COOP_EM_gPlayerParty + (uint32_t) i * 100)) {
            memcpy(trimmed + n * 100, mons + i * 100, 100);
            n++;
        }
    }
    for (uint32_t i = 0; i < LINK_PARTY_BYTES; i++) {
        core->busWrite8(core, COOP_EM_gPlayerParty + i, trimmed[i]);
    }
    core->busWrite8(core, COOP_EM_gPlayerPartyCount, (uint8_t) n);
    return n;
}

// task 5: the party back, as a MERGE. The battle's party (host slots 0-2, partner 3-5 on both games, reordered by switches) holds what
// the battle did to the player's own Pokemon (HP, status, PP, EXP, level, moves, a new species by evolution): each pre-battle Pokemon
// (the backup, in its own order) is found in the battle's party by (personality, OT id) and replaced by the battle's 100 bytes; one
// the battle's party doesn't hold (the set-aside reserves, in the backup only) comes back byte for byte, and the other player's
// Pokemon (in the battle's party only) go. Idempotent (backupValid goes 0): the second call changes nothing. `out` (optional): which
// original slots the battle took a level in (bit i), whether any restored slot can fight, and how many came out of the battle.
static void link_restore_party(struct mCore* core, struct pk_coop_link_merge* out) {
    if (out) {
        memset(out, 0, sizeof(*out));
    }
    if (!core->busRead8(core, LRAM(backupValid))) {
        return;
    }
    uint8_t backup[LINK_PARTY_BYTES], battle[LINK_PARTY_BYTES], merged[LINK_PARTY_BYTES];
    for (uint32_t i = 0; i < LINK_PARTY_BYTES; i++) {
        backup[i] = (uint8_t) core->busRead8(core, PK_COOP_LINK_RAM + PK_COOP_LINK_BACKUP + i);
        battle[i] = (uint8_t) core->busRead8(core, COOP_EM_gPlayerParty + i);
    }
    int count = core->busRead8(core, LRAM(backupCount));
    if (count > 6) count = 6;
    memcpy(merged, backup, sizeof(merged));
    int used[6] = { 0, 0, 0, 0, 0, 0 };
    for (int i = 0; i < count; i++) {
        for (int j = 0; j < 6; j++) {
            // personality + OT id (the first 8 bytes of a struct Pokemon); a slot the battle left empty has neither
            if (!used[j] && !memcmp(backup + i * 100, battle + j * 100, 8) && (battle[j * 100 + COOP_EM_OFF_BOX_MON_FLAGS] & 0x02)) {
                used[j] = 1;
                memcpy(merged + i * 100, battle + j * 100, 100);
                if (out) {
                    out->matched++;
                    if (battle[j * 100 + 0x54] > backup[i * 100 + 0x54]) {   // struct Pokemon.level
                        out->leveled |= (uint8_t) (1u << i);
                    }
                }
                break;
            }
        }
    }
    for (uint32_t i = 0; i < LINK_PARTY_BYTES; i++) {
        core->busWrite8(core, COOP_EM_gPlayerParty + i, merged[i]);
    }
    core->busWrite8(core, COOP_EM_gPlayerPartyCount, (uint8_t) count);
    core->busWrite8(core, LRAM(backupValid), 0);
    if (out) {
        out->count = (uint8_t) count;
        for (int i = 0; i < count; i++) {
            out->able |= (uint8_t) link_able(core, COOP_EM_gPlayerParty + (uint32_t) i * 100);
        }
    }
}

void pk_coop_link_restore(struct mCore* core, struct pk_coop_link_merge* out) {
    if (!core) {
        if (out) memset(out, 0, sizeof(*out));
        return;
    }
    link_restore_party(core, out);
}

const struct pk_coop_link_setup* pk_coop_link_setup_get(void) {
    return sLink.begun ? &sLink.setup : NULL;
}

int pk_coop_link_drained(struct mCore* core) {
    return !core || (core->busRead8(core, LRAM(head)) & 15) == (core->busRead8(core, LRAM(tail)) & 15);
}

// One struct LinkPlayer at `at` for a player with this name (8 bytes), gender and OT id.
static void link_player(struct mCore* core, uint32_t at, const uint8_t* name, int gender, uint32_t otId) {
    for (int b = 0; b < COOP_EM_OFF_LINK_PLAYER_SIZE; b++) core->busWrite8(core, at + (uint32_t) b, 0);
    link_write(core, at + COOP_EM_OFF_LP_VERSION, COOP_EM_VERSION_EMERALD + 0x4000u, 2);
    link_write(core, at + COOP_EM_OFF_LP_FIELD_2, 0x8000u, 2);
    link_write(core, at + COOP_EM_OFF_LP_TRAINER_ID, otId, 4);
    {   // the name came over the network: it ends at the first terminator or byte outside the game's charset, and always ends
        int end = 0;
        while (end < LINK_NAME_LEN - 1 && name[end] != 0xFF && name[end] < 0xF5) end++;
        for (int b = 0; b < LINK_NAME_LEN; b++) core->busWrite8(core, at + COOP_EM_OFF_LP_NAME + (uint32_t) b, b < end ? name[b] : 0xFF);
    }
    core->busWrite8(core, at + COOP_EM_OFF_LP_GENDER, (uint8_t) gender);
    link_write(core, at + COOP_EM_OFF_LP_LINK_TYPE, COOP_EM_LINKTYPE_BATTLE, 4);
    link_write(core, at + COOP_EM_OFF_LP_LANGUAGE, COOP_EM_LANGUAGE_ENGLISH, 2);
}

// What a begin changed in the game's RAM, put back (a refused begin, or reset): the link flags, the party, the battle flags.
static void link_undo_game(struct mCore* core) {
    link_restore_party(core, NULL);
    link_write(core, COOP_EM_gBattleTypeFlags, 0, 4);   // task 5: the link battle's flags stay after its end; the next begin needs the field free of a battle
    link_write(core, COOP_EM_gReceivedRemoteLinkPlayers, 0, 1);
    link_write(core, COOP_EM_gLinkStatus, 0, 4);
    link_write(core, COOP_EM_gLinkType, 0, 2);
    link_write(core, COOP_EM_gSavedLinkPlayerCount, 0, 1);
    for (uint32_t b = 0; b < 4u * COOP_EM_OFF_LINK_PLAYER_SIZE; b++) core->busWrite8(core, COOP_EM_gLinkPlayers + b, 0);
    for (uint32_t b = 0; b < (uint32_t) COOP_EM_OFF_LINK_PLAYER_SIZE; b++) core->busWrite8(core, COOP_EM_gLocalLinkPlayer + b, 0);
    for (int p = 0; p < 4; p++) core->busWrite8(core, COOP_EM_gBlockReceivedStatus + (uint32_t) p, 0);
}

int pk_coop_link_begin(struct mCore* core, const struct pk_coop_link_setup* setup) {
    if (!core || !setup || !sLink.installed || !sLink.call) {
        sLink.refused = "not installed";
        return 0;
    }
    if (sLink.begun) {
        sLink.refused = "already begun";
        return 0;
    }
    sLink.refused = "";
    if (setup->kind < PK_COOP_LINK_WILD || setup->kind > PK_COOP_LINK_STORY || setup->master > 1) {
        sLink.refused = "bad setup";
        return 0;
    }
    if (setup->trainerA >= COOP_EM_TRAINERS_COUNT || setup->trainerB >= COOP_EM_TRAINERS_COUNT) {   // ids from the network reach game memory: bounded
        sLink.refused = "trainer id out of range";
        return 0;
    }
    if (setup->kind == PK_COOP_LINK_TRAINERS && (!setup->trainerA || !setup->trainerB)) {
        sLink.refused = "two trainers needed";
        return 0;
    }
    if (setup->kind == PK_COOP_LINK_SINGLE && !setup->trainerA) {   // task 2
        sLink.refused = "a trainer needed";
        return 0;
    }
    // On the field, no battle, no link up: the game's callback2 is the overworld's and gMain.inBattle (bit 1 of the byte at 0x439)
    // is clear. gBattleTypeFlags is NOT part of the gate: the game only zeroes it when the next battle starts, so after any
    // battle (a vanilla one, or a joined one that was left) it is stale and says nothing about the field.
    if (link_read(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_CALLBACK2, 4) != (COOP_EM_CB2_Overworld | 1u)
        || (core->busRead8(core, COOP_EM_gMain + 0x439) & 0x02)
        || link_read(core, COOP_EM_gReceivedRemoteLinkPlayers, 1) != 0) {
        sLink.refused = "not on the field";
        return 0;
    }
    // A pk_call-safe frame (nothing changes if there is none).
    if (link_call(core, COOP_EM_ArePlayerFieldControlsLocked, 0, 0, 0) == 0xFFFFFFFFu) {
        sLink.refused = "no safe frame";
        return 0;
    }
    {   // at least one Pokemon that can fight, else the battle has nobody to send out (nothing changed so far)
        int count = core->busRead8(core, COOP_EM_gPlayerPartyCount), able = 0;
        for (int i = 0; i < count && i < 6; i++) able += link_able(core, COOP_EM_gPlayerParty + (uint32_t) i * COOP_EM_OFF_MON_SIZE);
        if (!able) {
            sLink.refused = "no able Pokemon";
            return 0;
        }
    }
    uint32_t sb2 = link_read(core, COOP_EM_gSaveBlock2Ptr, 4);
    if (sb2 < 0x02000000u || sb2 >= 0x02040000u) {
        sLink.refused = "no save block";
        return 0;
    }

    link_zero_ram(core);
    core->busWrite8(core, LRAM(active), 1);
    core->busWrite8(core, LRAM(myId), setup->master ? 0 : 1);
    core->busWrite8(core, LRAM(kind), setup->kind);
    if (!link_patches_in(core)) {
        link_zero_ram(core);
        return 0;
    }
    sLink.begun = 1;
    sBadCount = 0;
    pk_coop_link_start_note(core);   // task 2: what a refusal / the end puts back
    if (!pk_coop_link_rewards_in(core)) {   // task 4: EXP, the BAG and catching (pk_coop_link_rewards.c)
        link_patches_out(core);
        link_zero_ram(core);
        sLink.begun = 0;
        sLink.refused = pk_coop_link_rewards_refused();
        return 0;
    }
    sLink.setup = *setup;   // task 5

    // The players: gLinkPlayers[0] is the host, [1] the partner, whichever game this is; [2] and [3] are the AI trainers (the
    // game fills their names itself).
    uint8_t ownName[LINK_NAME_LEN];
    for (int b = 0; b < LINK_NAME_LEN; b++) ownName[b] = (uint8_t) core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_NAME + (uint32_t) b);
    int ownGender = core->busRead8(core, sb2 + COOP_EM_OFF_SB2_PLAYER_GENDER);
    uint32_t ownOt = link_read(core, sb2 + COOP_EM_OFF_SB2_PLAYER_TRAINER_ID, 4);
    for (uint32_t b = 0; b < 4u * COOP_EM_OFF_LINK_PLAYER_SIZE; b++) core->busWrite8(core, COOP_EM_gLinkPlayers + b, 0);
    int me = setup->master ? 0 : 1;
    pk_coop_link_rewards_players(core, setup->master, ownOt, setup->partnerOtId);   // task 4
    link_player(core, COOP_EM_gLinkPlayers + (uint32_t) me * COOP_EM_OFF_LINK_PLAYER_SIZE, ownName, ownGender, ownOt);
    link_player(core, COOP_EM_gLinkPlayers + (uint32_t) (1 - me) * COOP_EM_OFF_LINK_PLAYER_SIZE, setup->partnerName,
                setup->partnerGender, setup->partnerOtId);
    link_player(core, COOP_EM_gLocalLinkPlayer, ownName, ownGender, ownOt);
    for (int p = 0; p < 4; p++) core->busWrite8(core, COOP_EM_gBlockReceivedStatus + (uint32_t) p, 0);
    link_write(core, COOP_EM_gLinkType, COOP_EM_LINKTYPE_BATTLE, 2);
    // gLinkStatus: two players, the master bit on the host, the connection up, the local id.
    link_write(core, COOP_EM_gLinkStatus, (2u << 2) | (setup->master ? 0x20u : 0u) | 0x40u | (uint32_t) me, 4);
    link_write(core, COOP_EM_gReceivedRemoteLinkPlayers, 1, 1);
    // The game's own check that nobody dropped out (Task_WaitForLinkPlayerConnection: DoesLinkPlayerCountMatchSaved) compares
    // against the players saved when the link room was entered: save these.
    link_call(core, COOP_EM_SaveLinkPlayers, 2, 0, 0);   // void: r0 says nothing about whether it ran, its effect does
    if (core->busRead8(core, COOP_EM_gSavedLinkPlayerCount) != 2) {
        goto fail;
    }

    if (link_trim_party(core, setup->picks) < 1) {
        sLink.refused = "no able Pokemon";
        goto fail;
    }

    // The tower's link multi: both games set the same flags and the same two AI trainers; the master's gEnemyParty holds their
    // parties (the first trainer's three at 0, the second's at 3), which the game sends to the partner itself.
    uint32_t flags = COOP_EM_BATTLE_TYPE_DOUBLE | COOP_EM_BATTLE_TYPE_LINK | COOP_EM_BATTLE_TYPE_TRAINER | COOP_EM_BATTLE_TYPE_MULTI;
    if (setup->kind == PK_COOP_LINK_WILD) {   // task 3: no TRAINER - the game itself makes the intro, sprites, AI and escape wild ones
        flags &= ~COOP_EM_BATTLE_TYPE_TRAINER;
        if (!pk_coop_link_wild_patches_in(core, &sLink.wildPatches)) {
            extern uint32_t pk_coop_link_wild_failed_at;
            snprintf(sLink.refusedBuf, sizeof(sLink.refusedBuf), "wild patch %08X", (unsigned) pk_coop_link_wild_failed_at);
            sLink.refused = sLink.refusedBuf;
            goto fail;
        }
    }
    link_write(core, COOP_EM_gBattleTypeFlags, flags, 4);   // the tower flag comes after the parties: CreateNPCTrainerParty makes none for a Frontier battle
    if (setup->master && setup->kind == PK_COOP_LINK_WILD) {   // task 3: the two wild Pokemon, slots 0 and 3
        if (!pk_coop_link_wild_roll(core, sLink.call)) {
            sLink.refused = "no wild Pokemon here";
            goto fail;
        }
    } else if (setup->master && setup->kind == PK_COOP_LINK_SINGLE) {   // task 2: one trainer's Pokemon shared out between the two foes
        if (!pk_coop_link_start_single_party(core, sLink.call, setup->trainerA)) {
            goto fail;
        }
    } else if (setup->master) {
        // task 2: TWO_OPPONENTS only around the two calls: it makes each trainer's party stop at 3 (else a 4+ Pokemon trainer
        // runs into the other's half), and it is off again before anything else reads the flags (AI, text, money)
        link_write(core, COOP_EM_gBattleTypeFlags, flags | COOP_EM_BATTLE_TYPE_TWO_OPPONENTS, 4);
        int built = link_call(core, COOP_EM_CreateNPCTrainerParty, COOP_EM_gEnemyParty, setup->trainerA, 1) != 0xFFFFFFFFu
            && link_call(core, COOP_EM_CreateNPCTrainerParty, COOP_EM_gEnemyParty + 3u * COOP_EM_OFF_MON_SIZE, setup->trainerB, 0)
                   != 0xFFFFFFFFu;
        link_write(core, COOP_EM_gBattleTypeFlags, flags, 4);
        if (!built) {
            goto fail;
        }
    } else {
        for (uint32_t b = 0; b < 6u * COOP_EM_OFF_MON_SIZE; b++) core->busWrite8(core, COOP_EM_gEnemyParty + b, 0);
    }
    link_write(core, COOP_EM_gBattleTypeFlags, flags | COOP_EM_BATTLE_TYPE_BATTLE_TOWER | LINK_TOWER_LINK_MULTI, 4);
    // The tower's names and trainer pictures come from the Frontier's own trainer table by these two ids (GetFrontierTrainerName /
    // GetFrontierTrainerFrontSpriteId; an id of a route trainer, 300 and up, indexes the record-mixing tables with a junk number and
    // the name copy runs over other EWRAM): two Frontier trainers stand in until the later tasks put the real trainers' names and
    // pictures in. The parties above are the real trainers' (setup->trainerA / B, gTrainers ids).
    if (setup->kind == PK_COOP_LINK_TRAINERS || setup->kind == PK_COOP_LINK_SINGLE) {
        // task 2: trainer battles show the real trainers: the ids go in as they are (the tower flow's lookups are answered from
        // gTrainers while the hooks are in, scripts/coop_link_emerald.s), so the host's script flags the right ones at the end.
        pk_coop_link_start_ids(core, setup);
    } else {
        link_write(core, COOP_EM_gTrainerBattleOpponent_A, LINK_FACILITY_TRAINER_A, 2);
        link_write(core, COOP_EM_gTrainerBattleOpponent_B, LINK_FACILITY_TRAINER_B, 2);
    }

    if (pk_coop_link_test_fail) {   // task 2: a headless switch to test a refusal after everything above was written
        goto fail;
    }

    // Into the battle: the overworld's windows go as the cable club's own start does, then CB2_InitBattle, coming back to the field.
    link_call(core, COOP_EM_CleanupOverworldWindowsAndTilemaps, 0, 0, 0);   // void: nothing to check its result by
    // task 5: the battle ends as a wild one does (CB2_EndWildBattle: the field again, or CB2_WhiteOut on a LOST / DREW outcome); the
    // trainers' flags, the prize and the rest are the app's (pk_coop_link_tick), a later task puts the host's script's callback here.
    link_write(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_SAVED_CALLBACK, COOP_EM_CB2_EndWildBattle | 1u, 4);
    link_call(core, COOP_EM_SetMainCallback2, COOP_EM_CB2_InitBattle | 1u, 0, 0);   // void
    if (link_read(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_CALLBACK2, 4) != (COOP_EM_CB2_InitBattle | 1u)) {
        goto fail;
    }
    if (setup->kind == PK_COOP_LINK_WILD) {
        // The music, last (nothing can refuse the begin after it, so a refused begin never leaves the battle theme on the field):
        // GetBattleBGM says the trainer song for any LINK flag, so it is chosen with the flags at 0, a wild battle's, and put back.
        link_write(core, COOP_EM_gBattleTypeFlags, 0, 4);
        link_call(core, COOP_EM_PlayBattleBGM, 0, 0, 0);   // void
        link_write(core, COOP_EM_gBattleTypeFlags, flags | COOP_EM_BATTLE_TYPE_BATTLE_TOWER | LINK_TOWER_LINK_MULTI, 4);
    }
    sLink.refused = "";
    pk_coop_link_drop_arm(core);   // task 7: the heap's blocks as the field holds them
    return 1;

fail:
    link_write(core, COOP_EM_gBattleTypeFlags, 0, 4);
    pk_coop_link_start_unwind(core);   // task 2: the ids, savedCallback and enemy party as they were
    link_undo_game(core);
    pk_coop_link_wild_patches_out(core, &sLink.wildPatches);
    pk_coop_link_rewards_out(core);   // task 4
    link_patches_out(core);
    link_zero_ram(core);
    sLink.begun = 0;
    if (strcmp(sLink.refused, "no able Pokemon") && strncmp(sLink.refused, "wild patch", 10) && strcmp(sLink.refused, "no wild Pokemon here"))
        sLink.refused = "no safe frame";
    return 0;
}

// --- the blocks -------------------------------------------------------------------------------------------------------------

// What the game sent: from where in the game the block came (its source address) and how big it is.
static int link_classify(struct mCore* core, uint32_t src, int size) {
    uint32_t bs = link_read(core, COOP_EM_gBattleStruct, 4);
    if (bs && src == bs + COOP_EM_OFF_BATTLE_STRUCT_MULTIBUFFER && size == COOP_EM_LINK_BATTLER_HEADER_SIZE) {
        return PK_COOP_LINKB_HEADER;
    }
    if (src >= COOP_EM_gPlayerParty && src + (uint32_t) size <= COOP_EM_gPlayerParty + LINK_PARTY_BYTES && size % 100 == 0 && size > 0) {
        return PK_COOP_LINKB_PARTY;
    }
    if (src >= COOP_EM_gEnemyParty && src + (uint32_t) size <= COOP_EM_gEnemyParty + LINK_PARTY_BYTES && size % 100 == 0 && size > 0) {
        return PK_COOP_LINKB_ENEMY;
    }
    if (src == COOP_EM_gRecordedBattleRngSeed && size == 4) {
        return PK_COOP_LINKB_SEED;
    }
    uint32_t mp = link_read(core, COOP_EM_sMultiPartnerPartyBuffer, 4);
    if (mp && src == mp && size == COOP_EM_MULTI_PARTY_SIZE * COOP_EM_OFF_MULTI_PARTNER_MON_SIZE) {
        return PK_COOP_LINKB_MPARTY;
    }
    uint32_t lb = link_read(core, COOP_EM_gLinkBattleSendBuffer, 4);
    if (lb && src >= lb && src < lb + COOP_EM_BATTLE_BUFFER_LINK_SIZE && size >= 8) {
        return PK_COOP_LINKB_BUFFER;
    }
    return 0;
}

int pk_coop_link_take(struct mCore* core, uint8_t* out, int cap) {
    pk_coop_link_drop_tick(core);   // task 7 (the app pumps take every frame)
    if (!core || !out || !sLink.begun || !core->busRead8(core, LRAM(active))) {
        return 0;
    }
    for (;;) {
        int head = core->busRead8(core, LRAM(head)) & 15, tail = core->busRead8(core, LRAM(tail)) & 15;
        if (head == tail) {
            return 0;
        }
        uint32_t entry = PK_COOP_LINK_RAM + PK_COOP_LINK_OUT + (uint32_t) tail * PK_COOP_LINK_ENTRY;
        uint32_t src = link_read(core, entry, 4);
        int size = (int) link_read(core, entry + 4, 2);
        int kind = size <= (int) PK_COOP_LINK_BLOCK_MAX ? link_classify(core, src, size) : 0;
        if (kind && cap < PK_COOP_LINK_WIRE_HEADER + size) {
            return 0;   // no room for it: it stays
        }
        core->busWrite8(core, LRAM(tail), (uint8_t) ((tail + 1) & 15));
        if (kind == PK_COOP_LINKB_BUFFER && core->busRead8(core, LRAM(myId)) != 0
            && core->busRead8(core, entry + 8u) == COOP_EM_B_COMM_TO_CONTROLLER) {
            // task 4: a command the PARTNER's own game made for its own controller (a medicine used in its bag asks its Pokemon's
            // controller for the battle data, as the engine would): it ran here through the own-slot copy of SendBlock and is
            // nobody else's - only the master commands the partner's controller, the master's game never takes one from the partner
            continue;
        }
        if (!kind) {
            // A block of a kind the bridge doesn't know: never sent (the game's own end flow, a later task, has its own kinds).
            core->busWrite16(core, LRAM(dropped), (uint16_t) (link_read(core, LRAM(dropped), 2) + 1));
            sLinkWhy = 0;
            link_note_bad(2, 0, (const uint8_t*) &src, 4);   // the source address (host byte order) is all there is to show
            core->busWrite8(core, LRAM(error), 1);   // pk_coop_link_state says PK_COOP_LINK_ERROR from now on
            continue;
        }
        uint8_t seq = core->busRead8(core, LRAM(txSeq));
        core->busWrite8(core, LRAM(txSeq), (uint8_t) (seq + 1));
        core->busWrite16(core, LRAM(sent), (uint16_t) (link_read(core, LRAM(sent), 2) + 1));
        out[0] = (uint8_t) kind;
        out[1] = seq;
        out[2] = (uint8_t) size;
        out[3] = (uint8_t) (size >> 8);
        for (int b = 0; b < size; b++) out[PK_COOP_LINK_WIRE_HEADER + b] = (uint8_t) core->busRead8(core, entry + 8u + (uint32_t) b);
        return PK_COOP_LINK_WIRE_HEADER + size;
    }
}

// A struct Pokemon on the wire: all zero (an empty slot), or one that decrypts to something the game could hold: the substructures'
// checksum matches, a real species (never an egg: the party blocks carry battlers), moves and a held item that exist, each move's PP
// within what its move allows (base PP + the PP Up bonuses), a status the game can set, level and HP sane. The game's own link code
// checks none of this, so it is ours: a forged Pokemon could otherwise index the species, move and item tables out of bounds.
static const uint8_t sSubstructOrder[24][4] = {   // pokemon.c's GetSubstruct cases: for personality % 24, the position of Growth, Attacks, EVs, Misc
    {0,1,2,3}, {0,1,3,2}, {0,2,1,3}, {0,3,1,2}, {0,2,3,1}, {0,3,2,1}, {1,0,2,3}, {1,0,3,2}, {2,0,1,3}, {3,0,1,2}, {2,0,3,1}, {3,0,2,1},
    {1,2,0,3}, {1,3,0,2}, {2,1,0,3}, {3,1,0,2}, {2,3,0,1}, {3,2,0,1}, {1,2,3,0}, {1,3,2,0}, {2,1,3,0}, {3,1,2,0}, {2,3,1,0}, {3,2,1,0},
};

static int link_mon_ok(struct mCore* core, const uint8_t* m) {
    int zero = 1;
    for (int b = 0; b < 100; b++) {
        if (m[b]) { zero = 0; break; }
    }
    if (zero) {
        return 1;
    }
    uint32_t pid = m[0] | m[1] << 8 | m[2] << 16 | (uint32_t) m[3] << 24;
    uint32_t ot = m[4] | m[5] << 8 | m[6] << 16 | (uint32_t) m[7] << 24;
    uint32_t key = pid ^ ot, sum = 0;
    uint8_t dec[48];
    for (int w = 0; w < 12; w++) {
        const uint8_t* p = m + 0x20 + 4 * w;
        uint32_t v = (p[0] | p[1] << 8 | p[2] << 16 | (uint32_t) p[3] << 24) ^ key;
        sum += (v & 0xFFFF) + (v >> 16);
        for (int b = 0; b < 4; b++) dec[4 * w + b] = (uint8_t) (v >> (8 * b));
    }
    int level = m[0x54], hp = m[0x56] | m[0x57] << 8, maxHp = m[0x58] | m[0x59] << 8;
    uint32_t status = m[0x50] | m[0x51] << 8 | m[0x52] << 16 | (uint32_t) m[0x53] << 24;
    int flags = m[COOP_EM_OFF_BOX_MON_FLAGS];
    if (!(flags & 0x03)) {
        // An empty slot the game cleared field by field (no species, not a bad egg, no level, no HP, no ailment): bytes of a
        // cleared Pokemon are left over, which is not a forged one.
        return (sum & 0xFFFF) == (uint32_t) (m[0x1C] | m[0x1D] << 8) && !level && !hp && !maxHp && !status;
    }
    if ((sum & 0xFFFF) != (uint32_t) (m[0x1C] | m[0x1D] << 8) || level < 1 || level > 100 || hp > maxHp || maxHp > 999
        || (status & ~0xFFFu)) {   // sleep turns, the five ailments, the toxic counter: nothing above bit 11
        return LBAD;
    }
    if (!(flags & 0x02) || (flags & 0x05)) {   // hasSpecies, and neither an egg nor a bad egg
        return LBAD;
    }
    const uint8_t* order = sSubstructOrder[pid % 24];
    const uint8_t *g = dec + 12 * order[0], *a = dec + 12 * order[1];   // the table gives each type's position: G, A, E, M
    int species = g[0] | g[1] << 8, item = g[2] | g[3] << 8, bonuses = g[8];
    if (species < 1 || species >= COOP_EM_SPECIES_EGG || item >= COOP_EM_ITEMS_COUNT) {
        return LBAD;
    }
    for (int i = 0; i < 4; i++) {
        int move = a[2 * i] | a[2 * i + 1] << 8, pp = a[8 + i];
        if (move >= COOP_EM_MOVES_COUNT) {
            return LBAD;
        }
        int base = move ? core->busRead8(core, COOP_EM_gBattleMoves + (uint32_t) move * COOP_EM_OFF_BATTLE_MOVE_SIZE + COOP_EM_OFF_BATTLE_MOVE_PP) : 0;
        if (pp > base + base * ((bonuses >> (2 * i)) & 3) / 5) {
            return LBAD;
        }
    }
    return 1;
}

// task 5: the held item of the struct Pokemon at `mon` (its Growth substruct decrypted in place of a GetMonData call: no safe frame needed).
int pk_coop_link_mon_item(struct mCore* core, uint32_t mon) {
    uint32_t pid = (uint32_t) core->busRead32(core, mon), ot = (uint32_t) core->busRead32(core, mon + 4);
    uint32_t at = mon + 0x20 + 12u * sSubstructOrder[pid % 24][0];
    return (int) ((((uint32_t) core->busRead32(core, at)) ^ pid ^ ot) >> 16);   // Growth: species u16, then heldItem u16
}

static int link_u16(const uint8_t* p);
static int link_status_ok(const uint8_t* p);
// A struct BattlePokemon, as a controller's reply to REQUEST_ALL_BATTLE carries it (88 bytes; the master loads it into gBattleMons[2]):
// every field CopyPlayerMonData really fills, against what the game itself could have made of that Pokemon. Offsets: battle.h's struct
// BattlePokemon (species 0, moves 0x0C, the egg bit and ability slot at 0x17, pp 0x24, hp 0x28, level 0x2A, maxHP 0x2C, item 0x2E,
// ppBonuses 0x3B, status1 0x4C). The fields it does NOT fill - ability, types, stat stages, status2 - are stack junk in a real reply;
// link_battle_mon_fix puts the species' own values there (what the engine sets again at switch-in), so no junk and no forged value
// gets into gBattleMons.
static int link_battle_mon_ok(struct mCore* core, const uint8_t* m) {
    int species = link_u16(m + COOP_EM_OFF_BATTLE_MON_SPECIES);
    int hp = link_u16(m + COOP_EM_OFF_BATTLE_MON_HP), maxHp = link_u16(m + 0x2C), item = link_u16(m + 0x2E);
    int level = m[COOP_EM_OFF_BATTLE_MON_LEVEL];
    if (species < 1 || species >= COOP_EM_SPECIES_EGG || level < 1 || level > 100 || hp > maxHp || maxHp > 999 || item >= COOP_EM_ITEMS_COUNT
        || (m[0x17] & 0x40) /* isEgg */ || !link_status_ok(m + 0x4C)) {
        return LBAD;
    }
    for (int i = 0; i < 4; i++) {
        int move = link_u16(m + COOP_EM_OFF_BATTLE_MON_MOVES + 2 * i), pp = m[COOP_EM_OFF_BATTLE_MON_PP + i];
        if (move >= COOP_EM_MOVES_COUNT) {
            return LBAD;
        }
        int base = move ? core->busRead8(core, COOP_EM_gBattleMoves + (uint32_t) move * COOP_EM_OFF_BATTLE_MOVE_SIZE + COOP_EM_OFF_BATTLE_MOVE_PP) : 0;
        if (pp > base + base * ((m[0x3B] >> (2 * i)) & 3) / 5) {
            return LBAD;
        }
    }
    return 1;
}

// m (88 bytes, already link_battle_mon_ok): the ability, types, stat stages and status2 the reply leaves unset become the species' own
// (gSpeciesInfo: types at 6, abilities at 22), the stages neutral (6), status2 clear.
static void link_battle_mon_fix(struct mCore* core, uint8_t* m) {
    uint32_t info = COOP_EM_gSpeciesInfo + (uint32_t) link_u16(m + COOP_EM_OFF_BATTLE_MON_SPECIES) * COOP_EM_OFF_SPECIES_INFO_SIZE;
    int a0 = core->busRead8(core, info + 22), a1 = core->busRead8(core, info + 23);
    m[0x20] = (uint8_t) (((m[0x17] >> 7) & 1) && a1 ? a1 : a0);
    m[0x21] = core->busRead8(core, info + 6);
    m[0x22] = core->busRead8(core, info + 7);
    m[0x23] = 0;
    for (int i = 0; i < 8; i++) m[0x18 + i] = 6;
    for (int i = 0; i < 4; i++) m[0x50 + i] = 0;
}

// A Pokemon that can fight: a species, not an egg, HP left (the box flags and the HP are plaintext in the struct).
static int link_able(struct mCore* core, uint32_t mon);

// --- what the master may send to a controller ---------------------------------------------------------------------------------
// The partner's game runs the master's commands on its own controllers (battle_controller_player.c, _link_partner.c, _link_opponent.c),
// which index tables, the party and the sprites by what a command carries. Per command: the payload's size as
// PrepareBufferDataTransferLink aligns it (size - size % 4 + 4) and every field the handler uses as an index or a count, in range.
// Commands the master never sends (the raw mon data, DMA3 transfer, BGM, pause, palette fade and the other unused ones), and the
// replies (which only travel as TO_ENGINE), are refused. Tables: battle_controllers.h's enum; the ids that the generated header
// also names are checked against it below.
enum {
    LCMD_GETMONDATA = 0, LCMD_SETMONDATA = 2, LCMD_LOADMONSPRITE = 4, LCMD_SWITCHINANIM, LCMD_RETURNMONTOBALL, LCMD_DRAWTRAINERPIC,
    LCMD_TRAINERSLIDE, LCMD_TRAINERSLIDEBACK, LCMD_FAINTANIMATION, LCMD_BALLTHROWANIM = 13, LCMD_MOVEANIMATION = 15, LCMD_PRINTSTRING,
    LCMD_PRINTSTRINGPLAYERONLY, LCMD_CHOOSEACTION, LCMD_YESNOBOX, LCMD_CHOOSEMOVE, LCMD_OPENBAG, LCMD_CHOOSEPOKEMON,
    LCMD_HEALTHBARUPDATE = 24, LCMD_EXPUPDATE, LCMD_STATUSICONUPDATE, LCMD_STATUSANIMATION,
    LCMD_HITANIMATION = 41, LCMD_CANTSWITCH, LCMD_PLAYSE, LCMD_PLAYFANFAREORBGM, LCMD_FAINTINGCRY, LCMD_INTROSLIDE,
    LCMD_INTROTRAINERBALLTHROW, LCMD_DRAWPARTYSTATUSSUMMARY, LCMD_HIDEPARTYSTATUSSUMMARY, LCMD_ENDBOUNCE, LCMD_SPRITEINVISIBILITY,
    LCMD_BATTLEANIMATION, LCMD_LINKSTANDBYMSG, LCMD_RESETACTIONMOVESELECTION, LCMD_ENDLINKBATTLE,
};
_Static_assert(COOP_EM_CONTROLLER_CMDS_COUNT == 57 && COOP_EM_CONTROLLER_DATATRANSFER == 29 && COOP_EM_CONTROLLER_TWORETURNVALUES == 33,
               "pk_coop_link.c's controller command ids are battle_controllers.h's");
// SetMonData's requests the master sends (battle.h's REQUEST_*): the status, HP and held item, one move's PP, every move's PP.
enum { LREQ_MOVES_PP = 3, LREQ_HELDITEM = 2, LREQ_PPMOVE1 = 9, LREQ_PPMOVE4 = 12, LREQ_STATUS = 40, LREQ_HP = 42 };

#define LINK_ALIGN4(n) (((n) & ~3) + 4)
#define LINK_MAX_PP 64   // 40 PP + 3 PP Ups (the most any move has: 61)
#define LINK_MAX_SONG 558   // MUS_RG_TEACHY_TV_MENU, songs.h's last
#define LINK_ABILITIES 78   // ABILITIES_COUNT
#define LINK_STRING_FIRST 12   // STRINGID_TRAINER1LOSETEXT: BATTLESTRINGS_TABLE_START
#define LINK_STRINGS 381       // BATTLESTRINGS_COUNT

static int link_u16(const uint8_t* p) {
    return p[0] | p[1] << 8;
}

// A status word the game can hold in a battler / a party Pokemon: sleep turns, the ailments, the toxic counter.
static int link_status_ok(const uint8_t* p) {
    return !(((uint32_t) (p[0] | p[1] << 8 | p[2] << 16 | (uint32_t) p[3] << 24)) & ~0xFFFu);
}

static int link_order_ok(const uint8_t* p) {   // gBattlePartyCurrentOrder: three bytes of two party slots each
    for (int i = 0; i < 3; i++) {
        if ((p[i] & 0xF) > 5 || (p[i] >> 4) > 5) return LBAD;
    }
    return 1;
}

// One of BattleMsgData's text buffers (16 bytes): plain text up to the terminator, or the placeholder language
// (B_BUFF_*: a string id, a number, a move, a type, a nickname, a stat, a species, a flavour, an ability, an item) with its operands in range.
static int link_text_buff_ok(const uint8_t* b, int last) {
    int i = 0;
    if (b[0] != 0xFD) {
        int clear = 1;   // a buffer the master never used is 16 zero bytes
        for (int k = 0; k < 16; k++) clear &= b[k] == 0;
        if (clear) return 1;
        while (i < 16 && b[i] != 0xFF) {
            if (b[i++] > 0xF6) return LBAD;   // the text control codes (0xF7 and up) read ahead: only the placeholders may use them
        }
        // plain text with no terminator in its 16 bytes (a stale buffer, 01 00 00 ...) runs on into the next buffer, whose own check ends it; the
        // last one's would run past the struct, so it needs its terminator
        return i < 16 || !last;
    }
    for (i = 1; i < 16 && b[i] != 0xFF;) {
        int need;
        switch (b[i]) {
        case 0:  need = 3; if (i + need > 15 || link_u16(b + i + 1) < LINK_STRING_FIRST || link_u16(b + i + 1) >= LINK_STRINGS) return LBAD; break;
        case 1:  need = 3 + b[i + 1];   // a number: its size (1, 2, 4), its digit count
                 if (i + 2 > 15 || (b[i + 1] != 1 && b[i + 1] != 2 && b[i + 1] != 4) || b[i + 2] > 10 || i + need > 15) return LBAD;
                 break;
        case 2:  need = 3; if (i + need > 15 || link_u16(b + i + 1) >= COOP_EM_MOVES_COUNT) return LBAD; break;
        case 3:  need = 2; if (i + need > 15 || b[i + 1] >= 18) return LBAD; break;
        case 4: case 7:  need = 3; if (i + need > 15 || b[i + 1] > 3 || b[i + 2] >= 6) return LBAD; break;   // battler, party slot
        case 5:  need = 2; if (i + need > 15 || b[i + 1] >= 8) return LBAD; break;
        case 6:  need = 3; if (i + need > 15 || link_u16(b + i + 1) >= COOP_EM_SPECIES_EGG) return LBAD; break;
        case 8:  need = 2; if (i + need > 15 || b[i + 1] > 4) return LBAD; break;
        case 9:  need = 2; if (i + need > 15 || b[i + 1] >= LINK_ABILITIES) return LBAD; break;
        case 10: need = 3; if (i + need > 15 || link_u16(b + i + 1) >= COOP_EM_ITEMS_COUNT) return LBAD; break;
        default: return LBAD;
        }
        i += need;
    }
    return i < 16;
}

// A struct BattleMsgData (64 bytes) as PrintString sends it. (hpScale, m[9], is only ever compared with 0 / 1 / 2 by the text expander, and a
// joined wild battle's "prevents escape" message carries 0x28 there: any byte is harmless, so it is not checked; itemEffectBattler, m[10], is 4 = MAX_BATTLERS_COUNT when no battler holds the effect.)
static int link_msg_data_ok(const uint8_t* m) {
    if (link_u16(m) >= COOP_EM_MOVES_COUNT || link_u16(m + 2) >= COOP_EM_MOVES_COUNT || link_u16(m + 4) >= COOP_EM_ITEMS_COUNT
        || m[6] >= LINK_ABILITIES || m[7] > 3 || m[8] >= 6 || m[10] > 4 || m[11] >= 18) {
        return LBAD;
    }
    for (int i = 0; i < 4; i++) {
        if (m[12 + i] >= LINK_ABILITIES) return LBAD;
    }
    return link_text_buff_ok(m + 16, 0) && link_text_buff_ok(m + 32, 0) && link_text_buff_ok(m + 48, 1);
}

// One TO_CONTROLLER command from the master, as the partner's game would run it. d = the 8-byte buffer header then the payload.
static int link_command_ok(const uint8_t* d, int dataSize) {
    const uint8_t* p = d + 8;
    switch (p[0]) {
    case LCMD_GETMONDATA:   // [request][which Pokemon, a bit mask]
        return dataSize == 8;
    case LCMD_SETMONDATA: {   // [request][which, a bit mask][the value]
        int req = p[1], bytes;
        if (req == LREQ_STATUS) bytes = 4;
        else if (req == LREQ_HP || req == LREQ_HELDITEM) bytes = 2;
        else if (req >= LREQ_PPMOVE1 && req <= LREQ_PPMOVE4) bytes = 1;
        else if (req == LREQ_MOVES_PP) bytes = 16;
        else return LBAD;
        if (dataSize != LINK_ALIGN4(3 + bytes)) return LBAD;
        const uint8_t* v = p + 3;
        if (req == LREQ_STATUS) return link_status_ok(v);
        if (req == LREQ_HP) return link_u16(v) <= 999;
        if (req == LREQ_HELDITEM) return link_u16(v) < COOP_EM_ITEMS_COUNT;
        if (req == LREQ_MOVES_PP) {   // struct MovePPInfo: moves[4], pp[4], ppBonuses
            for (int i = 0; i < 4; i++) {
                if (link_u16(v + 2 * i) >= COOP_EM_MOVES_COUNT || v[8 + i] > LINK_MAX_PP) return LBAD;
            }
            return 1;
        }
        return v[0] <= LINK_MAX_PP;
    }
    case LCMD_LOADMONSPRITE: case LCMD_DRAWTRAINERPIC: case LCMD_TRAINERSLIDE: case LCMD_TRAINERSLIDEBACK: case LCMD_FAINTANIMATION:
    case LCMD_YESNOBOX: case LCMD_HITANIMATION: case LCMD_CANTSWITCH: case LCMD_FAINTINGCRY: case LCMD_INTROTRAINERBALLTHROW:
    case LCMD_HIDEPARTYSTATUSSUMMARY: case LCMD_ENDBOUNCE:
        return dataSize == 8;
    case LCMD_SWITCHINANIM:
        return dataSize == 8 && p[1] < 6 && p[2] <= 1;
    case LCMD_RETURNMONTOBALL: case LCMD_BALLTHROWANIM:
        return dataSize == 4 && p[1] <= 7;
    case LCMD_MOVEANIMATION:   // [move u16][turn][power u16][damage s32][friendship][multihit][weather u16]... + a struct DisableStruct
        return dataSize == LINK_ALIGN4(16 + 28) && link_u16(p + 1) < COOP_EM_MOVES_COUNT;
    case LCMD_PRINTSTRING: case LCMD_PRINTSTRINGPLAYERONLY:   // [outcome, or the command id again][string id u16] + a struct BattleMsgData
        return dataSize == LINK_ALIGN4(4 + 64) && link_u16(p + 2) < LINK_STRINGS && (p[0] == LCMD_PRINTSTRING ? p[1] <= 11 : p[1] == LCMD_PRINTSTRINGPLAYERONLY) && link_msg_data_ok(p + 4);
    case LCMD_CHOOSEACTION:
        // [the action the battler last chose, 0xFF before any][the last answer's bytes]: the master echoes what it holds, junk included. The
        // handler uses the u16 only as `itemId <= LAST_BALL` -> AddBagItem(itemId, 1) when a ball throw is backed out of; nothing indexes by it.
        return dataSize == 8;
    case LCMD_CHOOSEMOVE: {   // [double][no PP number][0] + struct ChooseMoveStruct: moves[4], currentPp[4], maxPp[4], species, types
        if (dataSize != LINK_ALIGN4(4 + 20) || p[1] > 1 || p[2] > 1) return LBAD;
        const uint8_t* c = p + 4;
        for (int i = 0; i < 4; i++) {
            if (link_u16(c + 2 * i) >= COOP_EM_MOVES_COUNT || c[8 + i] > LINK_MAX_PP || c[12 + i] > LINK_MAX_PP) return LBAD;
        }
        return link_u16(c + 16) < COOP_EM_SPECIES_EGG && c[18] < 18 && c[19] < 18;
    }
    case LCMD_OPENBAG:
        return dataSize == 8 && link_order_ok(p + 1);
    case LCMD_CHOOSEPOKEMON:   // [case][slot][ability][party order x3]
        return dataSize == LINK_ALIGN4(8) && p[1] <= 9 && p[2] <= 6 && p[3] < LINK_ABILITIES && link_order_ok(p + 4);
    case LCMD_HEALTHBARUPDATE: {   // [0][hp change s16]: a drop, a heal, or INSTANT_HP_BAR_DROP
        int v = (int16_t) link_u16(p + 2);
        return dataSize == 8 && p[1] == 0 && (v == 0x7FFF || (v >= -999 && v <= 999));
    }
    case LCMD_EXPUPDATE:
        return dataSize == 8 && p[1] < 6;
    case LCMD_STATUSICONUPDATE:
        return dataSize == LINK_ALIGN4(9) && link_status_ok(p + 1);
    case LCMD_STATUSANIMATION:
        return dataSize == LINK_ALIGN4(6) && p[1] <= 1;
    case LCMD_PLAYSE:
        return dataSize == 8 && link_u16(p + 1) <= LINK_MAX_SONG && p[3] == 0;
    case LCMD_PLAYFANFAREORBGM:
        return dataSize == 8 && link_u16(p + 1) <= LINK_MAX_SONG && p[3] <= 1;
    case LCMD_INTROSLIDE:
        return dataSize == 4 && p[1] <= 9;   // the battle environments
    case LCMD_DRAWPARTYSTATUSSUMMARY: {   // [flags][skip delay][x] + struct HpAndStatus x 6 (hp u16, status u32)
        if (dataSize != LINK_ALIGN4(4 + 6 * 8) || p[2] > 1) return LBAD;
        for (int i = 0; i < 6; i++) {
            const uint8_t* e = p + 4 + 8 * i;
            if ((link_u16(e) > 999 && link_u16(e) != 0xFFFF) || !link_status_ok(e + 4)) return LBAD;
        }
        return 1;
    }
    case LCMD_SPRITEINVISIBILITY:
        return dataSize == 8 && p[1] <= 1;
    case LCMD_BATTLEANIMATION:   // the general battle animations (B_ANIM_CASTFORM_CHANGE .. B_ANIM_WISH_HEAL)
        return dataSize == 8 && p[1] <= 22;
    case LCMD_LINKSTANDBYMSG:   // [mode][size][size] + the recorded-battle bytes of that size (a link battle records its battlers' data)
        return p[1] <= 2 && p[2] == p[3] && dataSize == LINK_ALIGN4(4 + p[2]);
    case LCMD_RESETACTIONMOVESELECTION:
        return dataSize == 4 && p[1] <= 2;
    case LCMD_ENDLINKBATTLE:   // [outcome][disable record][disable record][size][size] + the recorded battle's bytes
        return p[4] == p[5] && dataSize == LINK_ALIGN4(6 + p[4]) && p[1] <= 11 && p[2] <= 1 && p[3] <= 1;
    default:
        return LBAD;
    }
}

// One controller message. `fromPartner` = this game is the master and the block is the partner's: a partner may only answer for
// its own battler (2) - the reply to a question - and report its controllers done as player 1; it never commands a controller.
static int link_buffer_ok(struct mCore* core, const uint8_t* d, int size, int fromPartner) {
    // [buffer id][active battler][attacker][target][u16 size][absent flags][effect battler][data]: the game's own header, its size
    // field the data's (a multiple of 4: PrepareBufferDataTransferLink aligns it) - one controller command.
    if (size < 8 + 4 || size > (int) PK_COOP_LINK_BLOCK_MAX) {
        return LBAD;
    }
    int dataSize = d[4] | d[5] << 8;
    if (dataSize != size - 8 || dataSize % 4 != 0 || d[1] > 3 || d[2] > 3 || d[3] > 3) {
        return LBAD;
    }
    const uint8_t* p = d + 8;
    int id = p[0];
    switch (d[0]) {
    case COOP_EM_B_COMM_TO_CONTROLLER:
        return !fromPartner && link_command_ok(d, dataSize);
    case COOP_EM_B_COMM_CONTROLLER_IS_DONE:
        return dataSize == 8 && id == (fromPartner ? 1 : 0);   // the sender's own multiplayer id: the partner is 1, the master 0
    case COOP_EM_B_COMM_TO_ENGINE:
        break;
    default:
        return LBAD;
    }
    // A partner answers a question only for its own battler (2); the data replies (every controller of its game answers a request for a
    // Pokemon's data, whoever owns the battler) are checked by what they hold.
    if (fromPartner && id != COOP_EM_CONTROLLER_DATATRANSFER && d[1] != COOP_EM_B_POSITION_PLAYER_RIGHT) {
        return LBAD;
    }
    int ret16 = p[2] | p[3] << 8;
    switch (id) {
    case COOP_EM_CONTROLLER_TWORETURNVALUES:
        if (dataSize != 8) return LBAD;
        if (!fromPartner) return 1;   // the master's own controllers' answers (the AI's too) are the master's to make
        // task 4: the partner's controller says its Pokémon's EXP update made a level (the leftover EXP in ret16) - only while the
        // game waits for that (getexp's case 4)
        if (p[1] == COOP_EM_RET_VALUE_LEVELED_UP && core->busRead8(core, COOP_EM_gBattleScripting + COOP_EM_OFF_BATTLE_SCRIPTING_GETEXP_STATE) == 4) {
            return ret16 <= 0x7FFF;   // (the leftover is a positive s16)
        }
        // task 4: the item the partner's bag chose (the item in byte 1 and the low byte of ret16, a medicine's party slot 3-5 in
        // the high byte; item 0 = the bag's cancel is an action pick above) - only while the engine waits for an item from battler
        // 2 (the action pick said ITEM, STATE_WAIT_ACTION_CASE_CHOSEN = 3): it indexes tables by what it reads
        if (core->busRead8(core, COOP_EM_gChosenActionByBattler + COOP_EM_B_POSITION_PLAYER_RIGHT) == COOP_EM_B_ACTION_USE_ITEM
            && core->busRead8(core, COOP_EM_gBattleCommunication + COOP_EM_B_POSITION_PLAYER_RIGHT) == 3
            && (p[1] | p[2] << 8) < COOP_EM_ITEMS_COUNT && (p[3] == 0 || (p[3] >= 3 && p[3] <= 5))) {
            return 1;
        }
        if (p[1] <= LINK_ACTION_RUN) return ret16 == 0;   // an action pick (use move, use item, switch, run)
        // a move pick: the slot in the low byte, the target in the high one (0xFFFF = cancelled)
        return p[1] == COOP_EM_B_ACTION_EXEC_SCRIPT && (ret16 == 0xFFFF || ((ret16 & 0xFF) < 4 && (ret16 >> 8) < 4));
    case COOP_EM_CONTROLLER_CHOSENMONRETURNVALUE: {
        if (dataSize != 8) return LBAD;
        if (p[1] == COOP_EM_PARTY_SIZE) return 1;   // cancelled
        if (!fromPartner) return 1;
        for (int i = 0; i < 3; i++) {
            if ((p[2 + i] & 0xF) > 5 || (p[2 + i] >> 4) > 5) return LBAD;   // the party order's nibbles index the party
        }
        // the partner's own Pokemon are slots 3-5 of the battle party, and a Pokemon that can fight
        return p[1] >= 3 && p[1] < COOP_EM_PARTY_SIZE && link_able(core, COOP_EM_gPlayerParty + (uint32_t) p[1] * COOP_EM_OFF_MON_SIZE);
    }
    case COOP_EM_CONTROLLER_ONERETURNVALUE:
    case COOP_EM_CONTROLLER_ONERETURNVALUE_DUPLICATE:
        return dataSize == 8 && p[3] == 0 && (ret16 == 0xFFFF || (p[1] | p[2] << 8) < COOP_EM_ITEMS_COUNT);
    case COOP_EM_CONTROLLER_DATATRANSFER: {
        int n = ret16;   // BtlController_EmitDataTransfer: [id][id][u16 size][bytes]; the block's data is aligned up from size + 4
        if (p[1] != id || n > 100 || dataSize != (n + 4) - (n + 4) % 4 + 4) return LBAD;
        // A request whose mask names no Pokemon (a foe's controller asked for a dead or absent battler's data, e.g. when a foe faints) is
        // answered with no bytes at all: nothing in it to check, nothing the engine reads.
        if (n == 0) return 1;
        // The only data the game asks a controller for is REQUEST_ALL_BATTLE of one Pokemon: exactly one struct BattlePokemon.
        return n == COOP_EM_OFF_BATTLE_MON_SIZE && link_battle_mon_ok(core, p + 4);
    }
    default:
        return LBAD;   // no other reply exists
    }
}

static int link_shape_ok(struct mCore* core, int kind, const uint8_t* d, int size, int fromPartner) {
    switch (kind) {
    case PK_COOP_LINKB_HEADER:
        // the version signature 0x0300, and the Enigma berry's name (offset 4, 7 bytes) ending in a terminator after plain characters
        if (size != COOP_EM_LINK_BATTLER_HEADER_SIZE || d[0] != 0x00 || d[1] != 0x03) {
            return LBAD;
        }
        for (int i = 4; ; i++) {
            if (i >= 4 + 7) return LBAD;
            if (d[i] == 0xFF) return 1;
            if (d[i] > 0xF6) return LBAD;
        }
    case PK_COOP_LINKB_PARTY:
    case PK_COOP_LINKB_ENEMY:
        if (size != 100 && size != 200) {
            return LBAD;
        }
        for (int i = 0; i < size / 100; i++) {
            if (!link_mon_ok(core, d + 100 * i)) return LBAD;
        }
        return 1;
    case PK_COOP_LINKB_SEED:
        return size == 4;
    case PK_COOP_LINKB_MPARTY:
        // struct MultiPartnerMenuPokemon: species u16 @0, heldItem u16 @2, nickname @4 (0xFF-terminated), level u8 @0x0F, hp u16
        // @0x10, maxhp u16 @0x12, status u32 @0x14, language u8 @0x1D (a species of 0 = empty)
        if (size != COOP_EM_MULTI_PARTY_SIZE * COOP_EM_OFF_MULTI_PARTNER_MON_SIZE) {
            return LBAD;
        }
        for (int i = 0; i < COOP_EM_MULTI_PARTY_SIZE; i++) {
            const uint8_t* m = d + i * COOP_EM_OFF_MULTI_PARTNER_MON_SIZE;
            int species = m[0] | m[1] << 8, item = m[2] | m[3] << 8, hp = m[0x10] | m[0x11] << 8, maxHp = m[0x12] | m[0x13] << 8;
            uint32_t status = m[0x14] | m[0x15] << 8 | m[0x16] << 16 | (uint32_t) m[0x17] << 24;
            int term = 0;
            for (int b = 4; b < 15; b++) term |= m[b] == 0xFF;
            if (species >= COOP_EM_SPECIES_EGG || item >= COOP_EM_ITEMS_COUNT || m[0x0F] > 100 || hp > maxHp || maxHp > 999
                || (status & ~0xFFFu) || !term || m[0x1D] > 7) {
                return LBAD;
            }
        }
        return 1;
    case PK_COOP_LINKB_BUFFER:
        return link_buffer_ok(core, d, size, fromPartner);
    default:
        return LBAD;
    }
}

int pk_coop_link_state(struct mCore* core) {
    pk_coop_link_drop_tick(core);   // task 7: after a leave, tidies up once the game is back on the field
    if (!core || !sLink.begun) {
        return PK_COOP_LINK_IDLE;
    }
    if (core->busRead8(core, LRAM(error))) {
        return PK_COOP_LINK_ERROR;
    }
    if (core->busRead8(core, LRAM(ended))) {
        pk_coop_link_end_ids(core);   // task 2: the host script's ids and callback back before its end runs
        return PK_COOP_LINK_ENDED;
    }
    int in = (link_read(core, COOP_EM_gBattleTypeFlags, 4) & COOP_EM_BATTLE_TYPE_LINK_IN_BATTLE) != 0;
    if (in) {
        core->busWrite8(core, LRAM(seen), 1);
        return PK_COOP_LINK_BATTLE;
    }
    if (core->busRead8(core, LRAM(seen))) {
        core->busWrite8(core, LRAM(ended), 1);   // the game left the link battle
        pk_coop_link_end_ids(core);
        return PK_COOP_LINK_ENDED;
    }
    return PK_COOP_LINK_SETUP;
}

int pk_coop_link_give(struct mCore* core, const uint8_t* block, int size) {
    if (!core || !block || size < PK_COOP_LINK_WIRE_HEADER) {
        return 0;
    }
    int state = pk_coop_link_state(core);
    if (state != PK_COOP_LINK_SETUP && state != PK_COOP_LINK_BATTLE) {
        core->busWrite16(core, LRAM(refused), (uint16_t) (link_read(core, LRAM(refused), 2) + 1));
        return 0;
    }
    int kind = block[0], seq = block[1], len = block[2] | block[3] << 8;
    // A block that is no block the game could send (either side: the master's commands as much as the partner's answers) is
    // malformed: counted at LRAM_MALFORMED, and the bridge goes to PK_COOP_LINK_ERROR - a peer that sends one is hostile or broken.
    sLinkWhy = 0;
    if (len != size - PK_COOP_LINK_WIRE_HEADER || len > (int) PK_COOP_LINK_BLOCK_MAX || kind < 1 || kind >= PK_COOP_LINKB_COUNT
        || !link_shape_ok(core, kind, block + PK_COOP_LINK_WIRE_HEADER, len, core->busRead8(core, LRAM(myId)) == 0)) {
        link_note_bad(1, kind, block, size);
        core->busWrite16(core, LRAM_MALFORMED, (uint16_t) (link_read(core, LRAM_MALFORMED, 2) + 1));
        core->busWrite8(core, LRAM(error), 1);
        core->busWrite16(core, LRAM(refused), (uint16_t) (link_read(core, LRAM(refused), 2) + 1));
        return 0;
    }
    if (seq != (int) core->busRead8(core, LRAM(rxSeq))) {   // a stale or repeated block: refused, not malformed
        core->busWrite16(core, LRAM(refused), (uint16_t) (link_read(core, LRAM(refused), 2) + 1));
        return 0;
    }
    int other = 1 - core->busRead8(core, LRAM(myId));
    if (core->busRead8(core, COOP_EM_gBlockReceivedStatus + (uint32_t) other)) {
        return 0;   // the game hasn't read the last one: ask again (not a refusal)
    }
    if (kind == PK_COOP_LINKB_BUFFER && block[PK_COOP_LINK_WIRE_HEADER] == COOP_EM_B_COMM_TO_ENGINE
        && core->busRead8(core, LRAM(myId)) == 1) {
        // The master's controllers' own answers (the AI's picks, its data replies): the partner's game never reads them - only the
        // engine, which runs on the master, does - so they are taken in order (the seq moves on) and not written anywhere.
        core->busWrite8(core, LRAM(rxSeq), (uint8_t) (seq + 1));
        core->busWrite16(core, LRAM(received), (uint16_t) (link_read(core, LRAM(received), 2) + 1));
        return 1;
    }
    uint32_t dst = COOP_EM_gBlockRecvBuffer + (uint32_t) other * PK_COOP_LINK_BLOCK_MAX;
    uint8_t out[PK_COOP_LINK_BLOCK_MAX];
    memcpy(out, block + PK_COOP_LINK_WIRE_HEADER, (size_t) len);
    if (kind == PK_COOP_LINKB_BUFFER && out[0] == COOP_EM_B_COMM_TO_ENGINE && out[8] == COOP_EM_CONTROLLER_DATATRANSFER) {
        link_battle_mon_fix(core, out + 12);   // a Pokemon's data reply: the fields it leaves as junk are the species' own now
    }
    for (int b = 0; b < len; b++) core->busWrite8(core, dst + (uint32_t) b, out[b]);
    core->busWrite8(core, COOP_EM_gBlockReceivedStatus + (uint32_t) other, 1);
    core->busWrite8(core, LRAM(rxSeq), (uint8_t) (seq + 1));
    core->busWrite16(core, LRAM(received), (uint16_t) (link_read(core, LRAM(received), 2) + 1));
    return 1;
}

void pk_coop_link_end(struct mCore* core) {
    if (core && sLink.begun) {
        core->busWrite8(core, LRAM(ended), 1);
    }
}

void pk_coop_link_reset(struct mCore* core) {
    if (core) pk_coop_story_disarm(core);   // task 6: a story hook still in goes out with the bridge
    if (!core || !sLink.begun) {
        return;
    }
    pk_coop_link_wild_patches_out(core, &sLink.wildPatches);
    pk_coop_link_rewards_out(core);   // task 4
    link_patches_out(core);
    link_undo_game(core);
    link_zero_ram(core);
    sLink.begun = 0;
    pk_coop_link_start_forget();   // task 2
}
