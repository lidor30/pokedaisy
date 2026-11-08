// See pk_nds_call.h. Addresses from pret/pokeplatinum's xMAP (the decomp builds the retail ROM byte for byte); every
// function there is Thumb, so they're called with bit 0 set. Black's (Gen 5, no decomp) were traced in the running game:
// see BLACK below.

#include "pk_nds_call_memory.h"

#include <stddef.h>
#include <string.h>

#include <vector>

#include "pk_nds_ports_gen.h"
#include "pk_nds_stub_gen.h"

using u8 = uint8_t;
using u16 = uint16_t;
using u32 = uint32_t;

namespace {

// Platinum's functions for the jobs.
struct CallFns {
    uint32_t scriptManagerSet;          // ScriptManager_Set(fieldSystem, scriptID, object): a script task, not started
    uint32_t fieldTaskGetEnv;           // FieldTask_GetEnv(task): its ScriptManager
    uint32_t scriptContextCreateAndStart;   // (fieldSystem, scriptID): a context on that script's file and text bank
    uint32_t scriptContextJump;         // (ctx, ptr)
    uint32_t stringTemplateNew;         // (maxArgs, maxLen, heapID)
    uint32_t stringInit;                // (size, heapID)
    uint32_t scriptManagerGetMemberPtr;   // (scriptManager, member)
    uint32_t fieldMovesSetUsableMoves;  // (fieldSystem, FieldMoveContext*): what the party menu builds before its checks
    uint32_t fieldMovesCheckFly;        // (const FieldMoveContext*): FIELD_MOVE_ERROR_* (static in field_move_tasks.c)
    uint32_t checkFlyLocationUnlocked;  // (fieldSystem, spawn id 1..)
    uint32_t saveDataGetParty;          // (saveData)
    uint32_t partyGetPokemonBySlotIndex;   // (party, slot)
    uint32_t flyContextNew;             // (heapID, fieldSystem, mon, mapID, x, z): x / z stored after (4 register args)
    uint32_t fieldMovesFlyTask;         // the field task FLY runs
    uint32_t fieldSystemCreateTask;     // (fieldSystem, func, env)
    uint32_t journalCreateEventUsedMove;   // (fieldMove, mapID, heapID)
    uint32_t journalSaveData;           // (journalEntry, data, JOURNAL_LOCATION)
    int offSaveData, offJournalEntry;   // FieldSystem.saveData / .journalEntry
    int offFlyX, offFlyZ;               // FlyContext.x / .z (s16)
    int fieldMoveFly, journalLocation;
    uint32_t commonScriptBase;          // a common script id: its file's text bank (CommonStrings) is the one our scripts use
    int heapField2;                     // HEAP_ID_FIELD2
    int memberMainContext, memberNumActiveContexts, memberStrTemplate, memberMessageBuf, memberTemporaryBuf;
    int offScriptManagerState;          // ScriptManager.state (u8)
    // HeartGold / SoulSilver (script_manager.c): StartMapSceneScript's pieces by hand - a ScriptEnvironment, a context on
    // [hostScript]'s file and text bank, jumped to our bytes, then Task_RunScripts' state 0 done here and its task made.
    uint32_t scriptEnvironmentNew;      // ()
    uint32_t createScriptContext;       // (fieldSystem, script)
    uint32_t taskRunScripts;            // the field task that runs the environment's contexts
    int hostScript;                     // a std script whose file and text bank ours borrows
    int envState, envContextCount, envScriptNumber, envContexts, envMsgFmt, envString0, envString1;
    // HeartGold / SoulSilver's FLY (start_menu.c's Task_UseFlyInField after the Pokégear's pick): sub_02067BF8(heapID,
    // fieldSystem, mon, partySlot, mapID, x, z) - the last three on the stack, so stored after it at these offsets - then
    // sub_02067C30 run as a field task. Which towns are unlocked the app reads from the save's flypoint flags itself.
    int flyHasSlotArg, offFlyMap;
};

const CallFns PLATINUM = {
    0x0203E881, 0x02050A65, 0x0203EAB9, 0x0203E819, 0x0200B369, 0x02023791, 0x0203EF61,
    0x02070729, 0x02070951, 0x0203A921, 0x0207A269, 0x0207A0FD, 0x0207064D, 0x02070681, 0x02050905, 0x0202BE01, 0x0202B759,
    0x0C, 0x9C,
    0x0A, 0x0C,
    1, 1,
    2018,   // CommonScript_PC (scripts_common.s), used only for its file + text bank: our script replaces it at once
    11,
    13, 7, 15, 16, 17,
    4,
    0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0,
    0, 0,
};

// French Platinum (CPUF rev 0): the US cart's functions, found by their code (scripts/port_nds.py); the structs and the
// script ids are the US cart's.
const CallFns PLATINUM_FR = {
    0x0203E925, 0x02050B09, 0x0203EB5D, 0x0203E8BD, 0x0200B37D, 0x02023801, 0x0203F005,
    0x020707BD, 0x020709E5, 0x0203A9C5, 0x0207A309, 0x0207A19D, 0x020706E1, 0x02070715, 0x020509A9, 0x0202BE71, 0x0202B7C9,
    0x0C, 0x9C,
    0x0A, 0x0C,
    1, 1,
    2018,
    11,
    13, 7, 15, 16, 17,
    4,
    0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0,
    0, 0,
};

// pret/pokeheartgold's .xMAP: every address the same in the US HeartGold / SoulSilver builds and the German and French
// ones, rev 0 (checked against their ARM9s, including Task_RunScripts' calls and environment layout).
const CallFns HEARTGOLD = {
    0, 0, 0,
    0x0203FE0D,   // ScriptJump
    0x0200BD19,   // MessageFormat_New_Custom
    0x02026355,   // String_New
    0,
    0x02067E11,   // FieldMove_InitCheckData(fieldSystem, checkData)
    0x0206800D,   // FieldMove_CheckFly(checkData): FIELD_MOVE_RESPONSE_*, Platinum's numbers
    0,
    0x02074905,   // SaveArray_Party_Get
    0x02074645,   // Party_GetMonByIndex
    0x02067BF9,   // sub_02067BF8
    0x02067C31,   // sub_02067C30
    0x020504F1,   // FieldSystem_CreateTask
    0, 0,
    0x0C, 0,
    0x0A, 0x0C,
    0, 0,
    0,
    11,
    0, 0, 0, 0, 0,
    0,
    0x0204001D,   // ScriptEnvironment_New
    0x020400AD,   // CreateScriptContext
    0x0203FF45,   // Task_RunScripts
    2010,         // std_pokecenter_pc: scr_seq_0003 and msg_0040, the PC's own lines
    // ScriptEnvironment (script.h; checked against Task_RunScripts' code): state, activeScriptContextCount,
    // activeScriptNumber, scriptContexts[3], msgfmt, stringBuffer0 / 1
    4, 9, 0x0A, 0x38, 0x44, 0x48, 0x4C,
    1, 0x08,
};

// Origin HeartGold (nds/pk_nds_field.cpp's ORIGIN_HEARTGOLD): HeartGold's functions in the hack's build, each found by its
// US code (BLs and pool words masked) and its environment layout checked in Task_RunScripts' code; the script borrowed is
// the US one's (its file and text bank kept by the hack).
const CallFns ORIGIN_HEARTGOLD = {
    0, 0, 0,
    0x0203F515,   // ScriptJump
    0x0200BCB9,   // MessageFormat_New_Custom
    0x02026865,   // String_New
    0,
    0x020670AD,   // FieldMove_InitCheckData
    0x020672A9,   // FieldMove_CheckFly
    0,
    0x0207365D,   // SaveArray_Party_Get
    0x0207339D,   // Party_GetMonByIndex
    0x02066E95,   // sub_02067BF8
    0x02066ECD,   // sub_02067C30
    0x0204F979,   // FieldSystem_CreateTask
    0, 0,
    0x0C, 0,
    0x0A, 0x0C,
    0, 0,
    0,
    11,
    0, 0, 0, 0, 0,
    0,
    0x0203F725,   // ScriptEnvironment_New
    0x0203F7B5,   // CreateScriptContext
    0x0203F64D,   // Task_RunScripts
    2010,         // std_pokecenter_pc
    4, 9, 0x0A, 0x38, 0x44, 0x48, 0x4C,
    1, 0x08,
};

// Black (IRBO rev 0). There's no decomp, so these were traced in the running game with nds_dump's `watch` (the ARM9's
// writes to the GameSystem's event, their registers and stack): the PC's furniture, a Pokémon Center nurse and FLY
// from the party menu, each followed back to the function that made the event.
//
// Gen 5's GameSystem runs one GMEVENT at a time (+0x18). Every frame with none, GameSystem_Main calls its event check
// (+0x10: the field's, overlay 21, given the field map from +0x14), and whatever event that returns becomes the running
// one - pressing A at the PC is the check returning the PC script's event. So the hook takes the check's place for one
// frame: the game only calls it when no event runs, the moment pressing A would have started one. A job that starts an
// event stores the real check back, makes the event, sets it, and has the hook go on into a function that returns 0
// (the ARM9's `movs r0, #0; bx lr`), so the check's own answer can't replace it.
struct Gen5Fns {
    u32 gameSystemPtr;
    int offEventCheck, offEvent, offFieldMap;   // GameSystem: the event check, the running GMEVENT, the field map
    u32 eventCheck;       // the field's event check (overlay 21), what +0x10 holds on the field
    u32 returnZero;       // an ARM9 `movs r0, #0; bx lr` (Thumb)
    u32 setEvent;         // GameSystem_SetEvent(gs, event): str r1, [r0, #0x18]
    u32 scriptEvent;      // (gs, script id, object, -): a field script's event (overlay 10) - the nurse's and the PC's
    u32 pcScript;         // the Pokémon Center PC's script (what the field's check starts for A at Accumula's PC)
    // FLDSKILL (overlay 10): the party menu fills a check work from the field map, asks the skill's test, then the
    // skill's event (the X menu's event changes into it). Skill 4 is FLY: its test = the zone allows flying (the zone
    // header's +0x1E bit 13; outdoors), its event flies to the zone in the use parameters' word - landing where the
    // zone's FLY spot is (the game's own).
    u32 skillInitCheck, skillCanUse, skillUse;
    u32 skillFly;
    // Overlays 10 and 21 are the field's: their code where these words are, or no job (another overlay in their place).
    struct { u32 addr, word; } code[6];
};

const Gen5Fns BLACK = {
    0x02146248,
    0x10, 0x18, 0x20,
    0x0218A075,
    0x0200565D,
    0x02012109,
    0x02158769,
    10090,
    0x0215E841, 0x0215EA15, 0x0215EA39,
    4,
    {
        {0x0218A074, 0xB082B5F8}, {0x02158768, 0x1C0DB538}, {0x02158920, 0xB083B5F0},
        {0x0215E840, 0xB08AB5F8}, {0x0215EA14, 0x1C02B510}, {0x0215EA38, 0x1C03B538},
    },
};

// White (IRAO rev 0): Black's functions, found again by their code; the overlays and the GameSystem pointer sit 0x20
// higher, the ARM9's code where Black's is. Checked headless like Black's (the PC, FLY).
const Gen5Fns WHITE = {
    0x02146268,
    0x10, 0x18, 0x20,
    0x0218A095,
    0x0200565D,
    0x02012109,
    0x02158789,
    10090,
    0x0215E861, 0x0215EA35, 0x0215EA59,
    4,
    {
        {0x0218A094, 0xB082B5F8}, {0x02158788, 0x1C0DB538}, {0x02158940, 0xB083B5F0},
        {0x0215E860, 0xB08AB5F8}, {0x0215EA34, 0x1C02B510}, {0x0215EA58, 0x1C03B538},
    },
};

// French Black / White (IRBF / IRAF rev 0): the US / Europe functions, found by their code in the same overlays
// (scripts/port_nds.py), overlay 21 and the start of overlay 10 0x80 lower, overlay 10 from FLDSKILL on 0x90.
const Gen5Fns BLACK_FR = {
    0x021461C8,
    0x10, 0x18, 0x20,
    0x02189FF5,
    0x0200565D,
    0x02012109,
    0x021586E9,
    10090,
    0x0215E7B1, 0x0215E985, 0x0215E9A9,
    4,
    {
        {0x02189FF4, 0xB082B5F8}, {0x021586E8, 0x1C0DB538}, {0x021588A0, 0xB083B5F0},
        {0x0215E7B0, 0xB08AB5F8}, {0x0215E984, 0x1C02B510}, {0x0215E9A8, 0x1C03B538},
    },
};

const Gen5Fns WHITE_FR = {
    0x021461E8,
    0x10, 0x18, 0x20,
    0x0218A015,
    0x0200565D,
    0x02012109,
    0x02158709,
    10090,
    0x0215E7D1, 0x0215E9A5, 0x0215E9C9,
    4,
    {
        {0x0218A014, 0xB082B5F8}, {0x02158708, 0x1C0DB538}, {0x021588C0, 0xB083B5F0},
        {0x0215E7D0, 0xB08AB5F8}, {0x0215E9A4, 0x1C02B510}, {0x0215E9C8, 0x1C03B538},
    },
};

// Black 2 (IREO rev 0): Black's functions in a new build, each found by its code (BLs masked) - overlay 12 holds what
// Black's 10 did, 36 the field's event check (Black's 21) - and checked headless on the player's save (the PC, FLY).
const Gen5Fns BLACK2 = {
    0x021410F4,
    0x10, 0x18, 0x20,
    0x0218185D,
    0x020056B9,
    0x02016AED,
    0x0215366D,
    10090,
    0x02159231, 0x021593FD, 0x02159421,
    4,
    {
        {0x0218185C, 0xB082B5F8}, {0x0215366C, 0x1C0DB538}, {0x02153824, 0xB083B5F0},
        {0x02159230, 0xB08AB5F8}, {0x021593FC, 0x1C02B510}, {0x02159420, 0x1C03B538},
    },
};

// White 2 (IRDO rev 0): Black 2's functions, found again by their code, the overlays and the GameSystem pointer 0x40 higher
// (the ARM9's low code where Black 2's is). Checked headless like Black 2's.
const Gen5Fns WHITE2 = {
    0x02141134,
    0x10, 0x18, 0x20,
    0x0218189D,
    0x020056B9,
    0x02016AED,
    0x021536AD,
    10090,
    0x02159271, 0x0215943D, 0x02159461,
    4,
    {
        {0x0218189C, 0xB082B5F8}, {0x021536AC, 0x1C0DB538}, {0x02153864, 0xB083B5F0},
        {0x02159270, 0xB08AB5F8}, {0x0215943C, 0x1C02B510}, {0x02159460, 0x1C03B538},
    },
};

struct Pending {
    int seq;
    uint32_t parent;   // the field map's ApplicationManager the hook sits in (Gen 5: the GameSystem)
    uint32_t slot, orig;   // the pointer the hook replaced and its value, put back if the game never calls the hook
    int frames;
    int state;         // 0 waiting, 1 ran, -1 taken out
};

int nextSeq = 1;
Pending pending = {};
int lastDone = 0, lastFailed = 0;
uint32_t lastResults[PK_NDS_MAX_RESULTS];

// The language ports' functions (pk_nds_ports_gen.h): HeartGold's and Black's tables with each port's addresses. Black 2's
// structs and script ids are Black's, and every guard word below is the same instruction in all of them.
const CallFns* portFns(int n)
{
    static const std::vector<CallFns> fns = [] {
        std::vector<CallFns> v;
        for (int i = 0; i < PK_NDS_PORTS4_COUNT; i++) {
            const PkNdsPort4& r = PK_NDS_PORTS4[i];
            CallFns f = HEARTGOLD;
            f.scriptContextJump = r.scriptJump;
            f.stringTemplateNew = r.messageFormatNew;
            f.stringInit = r.stringNew;
            f.fieldMovesSetUsableMoves = r.fieldMoveInit;
            f.fieldMovesCheckFly = r.fieldMoveCheckFly;
            f.saveDataGetParty = r.partyGet;
            f.partyGetPokemonBySlotIndex = r.partyGetMon;
            f.flyContextNew = r.flyNew;
            f.fieldMovesFlyTask = r.flyTask;
            f.fieldSystemCreateTask = r.createTask;
            f.scriptEnvironmentNew = r.scriptEnvNew;
            f.createScriptContext = r.createScriptContext;
            f.taskRunScripts = r.taskRunScripts;
            v.push_back(f);
        }
        return v;
    }();
    return n >= 0 && n < (int) fns.size() ? &fns[n] : nullptr;
}

// The Platinum ports' functions: the US rev 1 table (its structs and script ids) with each port's addresses.
const CallFns* portPlatinumFns(int n)
{
    static const std::vector<CallFns> fns = [] {
        std::vector<CallFns> v;
        for (int i = 0; i < PK_NDS_PORTS4P_COUNT; i++) {
            const PkNdsPort4P& r = PK_NDS_PORTS4P[i];
            CallFns f = PLATINUM;
            f.scriptManagerSet = r.scriptManagerSet;
            f.fieldTaskGetEnv = r.fieldTaskGetEnv;
            f.scriptContextCreateAndStart = r.scriptContextCreateAndStart;
            f.scriptContextJump = r.scriptContextJump;
            f.stringTemplateNew = r.stringTemplateNew;
            f.stringInit = r.stringInit;
            f.scriptManagerGetMemberPtr = r.scriptManagerGetMemberPtr;
            f.fieldMovesSetUsableMoves = r.fieldMovesSetUsableMoves;
            f.fieldMovesCheckFly = r.fieldMovesCheckFly;
            f.checkFlyLocationUnlocked = r.checkFlyLocationUnlocked;
            f.saveDataGetParty = r.saveDataGetParty;
            f.partyGetPokemonBySlotIndex = r.partyGetPokemonBySlotIndex;
            f.flyContextNew = r.flyContextNew;
            f.fieldMovesFlyTask = r.fieldMovesFlyTask;
            f.fieldSystemCreateTask = r.fieldSystemCreateTask;
            f.journalCreateEventUsedMove = r.journalCreateEventUsedMove;
            f.journalSaveData = r.journalSaveData;
            v.push_back(f);
        }
        return v;
    }();
    return n >= 0 && n < (int) fns.size() ? &fns[n] : nullptr;
}

const Gen5Fns* portGen5Fns(int n)
{
    static const std::vector<Gen5Fns> fns = [] {
        std::vector<Gen5Fns> v;
        for (int i = 0; i < PK_NDS_PORTS5_COUNT; i++) {
            const PkNdsPort5& r = PK_NDS_PORTS5[i];
            Gen5Fns f = BLACK;
            f.gameSystemPtr = r.gameSystem;
            f.eventCheck = r.eventCheck;
            f.returnZero = r.returnZero;
            f.setEvent = r.setEvent;
            f.scriptEvent = r.scriptEvent;
            f.skillInitCheck = r.skillInitCheck;
            f.skillCanUse = r.skillCanUse;
            f.skillUse = r.skillUse;
            const u32 at[6] = { r.eventCheck, r.scriptEvent, r.scriptEventCheck, r.skillInitCheck, r.skillCanUse, r.skillUse };
            for (int k = 0; k < 6; k++) f.code[k].addr = at[k] & ~1u;
            v.push_back(f);
        }
        return v;
    }();
    return n >= 0 && n < (int) fns.size() ? &fns[n] : nullptr;
}

const CallFns* fnsFor(const PkNdsFieldGame* game)
{
    if (!game || !game->fieldMapMain) return nullptr;
    switch (game->callSet) {
    case 1: return &PLATINUM;
    case 2: return &HEARTGOLD;
    case 7: return &ORIGIN_HEARTGOLD;
    case 8: return &PLATINUM_FR;
    default:
        if (game->callSet >= 100 && game->callSet < 200) return portFns(game->callSet - 100);
        if (game->callSet >= 300 && game->callSet < 400) return portPlatinumFns(game->callSet - 300);
        return nullptr;
    }
}

const Gen5Fns* gen5FnsFor(const PkNdsFieldGame* game)
{
    if (!game || game->gen != 5) return nullptr;
    switch (game->callSet) {
    case 3: return &BLACK;
    case 4: return &WHITE;
    case 5: return &BLACK2;
    case 6: return &WHITE2;
    case 9: return &BLACK_FR;
    case 10: return &WHITE_FR;
    default: return game->callSet >= 200 && game->callSet < 300 ? portGen5Fns(game->callSet - 200) : nullptr;
    }
}

u32 rd32(const PkNdsCallMemory& mem, u32 a)
{
    return (a & 0xFF000000u) == 0x02000000u ? mem.read32(mem.context, a) : 0;
}

void wr32(const PkNdsCallMemory& mem, u32 a, u32 v)
{
    if ((a & 0xFF000000u) == 0x02000000u) mem.write32(mem.context, a, v);
}

bool inMain(u32 p) { return p >= 0x02000000u && p < 0x02400000u; }

void itcmWrite(const PkNdsCallMemory& mem, u32 addr, const void* src, u32 len)
{
    mem.writeItcm(mem.context, addr, src, len);
}

void itcmRead(const PkNdsCallMemory& mem, u32 addr, void* dst, u32 len)
{
    mem.readItcm(mem.context, addr, dst, len);
}

// The field map's ApplicationManager when the field is free to start something (field_system.c's HandleFieldInput:
// no pause, the map running, no task; no child process), else 0.
u32 freeFieldParent(const PkNdsCallMemory& mem, const PkNdsFieldGame* g)
{
    u32 fs = rd32(mem, g->fieldSystemPtr);
    if (!inMain(fs)) return 0;
    u32 pm = rd32(mem, fs + g->offProcessManager);
    if (!inMain(pm)) return 0;
    u32 parent = rd32(mem, pm), child = rd32(mem, pm + 4), pause = rd32(mem, pm + 8);
    if (!inMain(parent) || child || pause) return 0;
    if (rd32(mem, fs + g->offTask) || !rd32(mem, fs + g->offRunningFieldMap)) return 0;
    return parent;
}

// Gen 5: the GameSystem when its field is up and free - the field map's proc running with a field map, no event, the
// field's own event check in place and overlays 10 / 21 holding the code the jobs call - else 0.
u32 gen5FreeField(const PkNdsCallMemory& mem, const PkNdsFieldGame* g, const Gen5Fns& f)
{
    u32 gs = rd32(mem, f.gameSystemPtr);
    if (!inMain(gs)) return 0;
    u32 ps = rd32(mem, gs + g->offProcSys);
    u32 proc = inMain(ps) ? rd32(mem, ps + g->offCurrentProc) : 0;
    if (!inMain(proc) || (int) rd32(mem, proc + g->offProcOverlay) != g->fieldOverlay) return 0;
    if (rd32(mem, gs + f.offEvent) || !inMain(rd32(mem, gs + f.offFieldMap)) || !inMain(rd32(mem, gs + g->offFieldMap))) return 0;
    if (rd32(mem, gs + f.offEventCheck) != f.eventCheck) return 0;
    for (const auto& c : f.code)
        if (c.addr && rd32(mem, c.addr) != c.word) return 0;
    return gs;
}

struct Ops {
    PkNdsOp op[PK_NDS_MAX_OPS];
    int n = 0;
    int add(u32 kind, u32 a = 0, u32 b = 0, u32 c = 0, u32 d = 0)
    {
        op[n] = {kind, {a, b, c, d}};
        return n++;
    }
    static u32 res(int i) { return PK_NDS_RESULT_REF + (u32) i; }
};

// HeartGold / SoulSilver's: the environment and its first context set up as Task_RunScripts' state 0 would, the context
// on our bytes, and the task made - it runs the script from the next frame.
/** ScriptContext.taskman (script.h: 4 bytes, the native and script pointers, a 20-deep stack, the command table, its
 * count and data[4] before it). */
constexpr u32 HG_CONTEXT_TASKMAN = 0x74;

void hgScriptJob(Ops& o, const CallFns& f, u32 fs, const PkNdsFieldGame& g)
{
    int env = o.add(f.scriptEnvironmentNew);
    o.add(PK_NDS_OP_STORE16, Ops::res(env), (u32) f.envScriptNumber, (u32) f.hostScript);
    int ctx = o.add(f.createScriptContext, fs, (u32) f.hostScript);
    o.add(f.scriptContextJump, Ops::res(ctx), PK_NDS_SCRIPT_ADDR);
    o.add(PK_NDS_OP_STORE32, Ops::res(env), (u32) f.envContexts, Ops::res(ctx));
    o.add(PK_NDS_OP_STORE8, Ops::res(env), (u32) f.envContextCount, 1);
    int fmt = o.add(f.stringTemplateNew, 8, 64, (u32) f.heapField2);
    o.add(PK_NDS_OP_STORE32, Ops::res(env), (u32) f.envMsgFmt, Ops::res(fmt));
    int s0 = o.add(f.stringInit, 1024, (u32) f.heapField2);
    o.add(PK_NDS_OP_STORE32, Ops::res(env), (u32) f.envString0, Ops::res(s0));
    int s1 = o.add(f.stringInit, 1024, (u32) f.heapField2);
    o.add(PK_NDS_OP_STORE32, Ops::res(env), (u32) f.envString1, Ops::res(s1));
    o.add(PK_NDS_OP_STORE8, Ops::res(env), (u32) f.envState, 1);
    o.add(f.fieldSystemCreateTask, fs, f.taskRunScripts, Ops::res(env));
    // The context's task manager: the game's own state 0 creates the context once the task runs, so it gets
    // FieldSystem's (SetUpScriptContextForMap); made before the task, ours had none - fine for the PC, but a command that
    // starts a task of its own (a battle: ScrCmd_WildBattle's ctx->taskman) read through NULL.
    int tm = o.add(PK_NDS_OP_LOAD32, fs, (u32) g.offTask);
    o.add(PK_NDS_OP_STORE32, Ops::res(ctx), HG_CONTEXT_TASKMAN, Ops::res(tm));
}

// A field script of ours, from PK_NDS_SCRIPT_ADDR: ScriptManager_Set's own task, then what its first step would do
// (FieldTask_RunScript's state 0: the main context, the string template and buffers, one active context) with the
// context jumped to our script before it reads a byte; state 1 then runs it from the next frame like any script.
void scriptJob(Ops& o, const CallFns& f, u32 fs, const PkNdsFieldGame& g)
{
    o.add(f.scriptManagerSet, fs, f.commonScriptBase, 0);
    int task = o.add(PK_NDS_OP_LOAD32, fs, (u32) g.offTask);
    int sm = o.add(f.fieldTaskGetEnv, Ops::res(task));
    int ctx = o.add(f.scriptContextCreateAndStart, fs, f.commonScriptBase);
    o.add(f.scriptContextJump, Ops::res(ctx), PK_NDS_SCRIPT_ADDR);
    int tmpl = o.add(f.stringTemplateNew, 8, 64, (u32) f.heapField2);
    int msg = o.add(f.stringInit, 1024, (u32) f.heapField2);
    int tmp = o.add(f.stringInit, 1024, (u32) f.heapField2);
    int p = o.add(f.scriptManagerGetMemberPtr, Ops::res(sm), (u32) f.memberMainContext);
    o.add(PK_NDS_OP_STORE32, Ops::res(p), 0, Ops::res(ctx));
    p = o.add(f.scriptManagerGetMemberPtr, Ops::res(sm), (u32) f.memberStrTemplate);
    o.add(PK_NDS_OP_STORE32, Ops::res(p), 0, Ops::res(tmpl));
    p = o.add(f.scriptManagerGetMemberPtr, Ops::res(sm), (u32) f.memberMessageBuf);
    o.add(PK_NDS_OP_STORE32, Ops::res(p), 0, Ops::res(msg));
    p = o.add(f.scriptManagerGetMemberPtr, Ops::res(sm), (u32) f.memberTemporaryBuf);
    o.add(PK_NDS_OP_STORE32, Ops::res(p), 0, Ops::res(tmp));
    p = o.add(f.scriptManagerGetMemberPtr, Ops::res(sm), (u32) f.memberNumActiveContexts);
    o.add(PK_NDS_OP_STORE8, Ops::res(p), 0, 1);
    o.add(PK_NDS_OP_STORE8, Ops::res(sm), (u32) f.offScriptManagerState, 1);
}

}   // namespace

bool pk_nds_call_supported(const PkNdsFieldGame* game)
{
    return fnsFor(game) || gen5FnsFor(game);
}

// The ops into the job, the hook into [slot] (holding [orig]): it runs them the next time the game calls it, then goes
// on into [then] (orig, or a stand-in: Gen 5's event jobs). [script] (len bytes) is written to PK_NDS_SCRIPT_ADDR first.
static int postAt(const PkNdsCallMemory& mem, u32 parent, u32 slot, u32 orig, u32 then, Ops& o, const unsigned char* script, u32 scriptLen)
{
    if (rd32(mem, slot) != orig) return 0;
    if (o.n < PK_NDS_MAX_OPS) o.add(PK_NDS_OP_END);

    // The code only when it isn't there already (a state from before, a reset): rewriting it drops the JIT's blocks.
    u8 have[sizeof(PK_NDS_HOOK_CODE)];
    itcmRead(mem, PK_NDS_HOOK_ADDR, have, sizeof(have));
    if (memcmp(have, PK_NDS_HOOK_CODE, sizeof(have))) itcmWrite(mem, PK_NDS_HOOK_ADDR, PK_NDS_HOOK_CODE, sizeof(have));
    if (script) itcmWrite(mem, PK_NDS_SCRIPT_ADDR, script, scriptLen);

    int seq = nextSeq++;
    PkNdsJob job = {};
    job.seq = (u32) seq;
    job.slot = slot;
    job.orig = then;
    memcpy(job.ops, o.op, sizeof(PkNdsOp) * (size_t) o.n);
    itcmWrite(mem, PK_NDS_JOB, &job, sizeof(job));
    wr32(mem, slot, PK_NDS_HOOK_ADDR);
    pending = {seq, parent, slot, orig, 0, 0};
    return seq;
}

// Gen 4: the hook into the field map's main (ApplicationManager.template.main).
static int post(const PkNdsCallMemory& mem, const PkNdsFieldGame* game, u32 parent, Ops& o, const unsigned char* script, u32 scriptLen)
{
    return postAt(mem, parent, parent + 4, game->fieldMapMain, game->fieldMapMain, o, script, scriptLen);
}

// Gen 5's jobs (see Gen5Fns): the hook in the event check's place. [startsEvent]: the ops set an event of their own, so
// the check is stored back first and the hook goes on into returnZero instead of it.
static int postGen5(const PkNdsCallMemory& mem, const Gen5Fns& f, u32 gs, Ops& o, bool startsEvent)
{
    if (!startsEvent) return postAt(mem, gs, gs + f.offEventCheck, f.eventCheck, f.eventCheck, o, nullptr, 0);
    Ops all;
    all.add(PK_NDS_OP_STORE32, gs, (u32) f.offEventCheck, f.eventCheck);
    for (int i = 0; i < o.n && all.n < PK_NDS_MAX_OPS; i++) {
        PkNdsOp op = o.op[i];
        // results move one op down
        for (u32& a : op.a)
            if ((a & ~(u32) (PK_NDS_MAX_RESULTS - 1)) == PK_NDS_RESULT_REF) a += 1;
        all.op[all.n++] = op;
    }
    return postAt(mem, gs, gs + f.offEventCheck, f.eventCheck, f.returnZero, all, nullptr, 0);
}

// Black's actions (pk_nds_call_action's, Gen 5's way). Results are the ops' (postGen5 puts its own op first when the
// job starts an event: the event made is then result 1, 0 when none was - FLY from a place the game says no to).
static int gen5Action(const PkNdsCallMemory& mem, const PkNdsFieldGame* game, const Gen5Fns& f, int action, const uint32_t args[4])
{
    u32 gs = gen5FreeField(mem, game, f);
    if (!gs) return 0;
    u32 fieldMap = rd32(mem, gs + f.offFieldMap);
    Ops o;
    switch (action) {
    case PK_NDS_ACTION_PC: {
        int ev = o.add(f.scriptEvent, gs, f.pcScript, 0, 0);
        o.add(f.setEvent, gs, Ops::res(ev));
        return postGen5(mem, f, gs, o, true);
    }
    case PK_NDS_ACTION_FLY_CHECK: {
        // result 1 = the skill's test (0 = may fly from here), as Gen 4's; which towns the app reads from the flags
        constexpr u32 CHECK = PK_NDS_SKILL_ADDR + offsetof(PkNdsSkill, check);
        o.add(f.skillInitCheck, fieldMap, CHECK);
        o.add(f.skillCanUse, f.skillFly, CHECK);
        return postGen5(mem, f, gs, o, false);
    }
    case PK_NDS_ACTION_FLY: {
        // arg0 = the party slot of the Pokémon that flies, arg1 = the zone to fly to
        PkNdsSkill sk = {};
        sk.initCheck = f.skillInitCheck;
        sk.canUse = f.skillCanUse;
        sk.use = f.skillUse;
        sk.skill = f.skillFly;
        sk.paramSlot = (u16) args[0];
        sk.paramSkill = (u16) f.skillFly;
        sk.paramArg = args[1];
        u8 have[sizeof(PK_NDS_SKILL_CODE)];
        itcmRead(mem, PK_NDS_HELPER_ADDR, have, sizeof(have));
        if (memcmp(have, PK_NDS_SKILL_CODE, sizeof(have))) itcmWrite(mem, PK_NDS_HELPER_ADDR, PK_NDS_SKILL_CODE, sizeof(have));
        itcmWrite(mem, PK_NDS_SKILL_ADDR, &sk, sizeof(sk));
        int ev = o.add(PK_NDS_HELPER_ADDR, PK_NDS_SKILL_ADDR, fieldMap);
        o.add(f.setEvent, gs, Ops::res(ev));
        return postGen5(mem, f, gs, o, true);
    }
    default:
        return 0;
    }
}

// Scratch for structs a job hands the game (a FieldMoveContext): past the script, in the same unused ITCM.
constexpr u32 SCRATCH = PK_NDS_SCRIPT_ADDR + PK_NDS_SCRIPT_MAX;

int pk_nds_call_action(const PkNdsCallMemory& mem, const PkNdsFieldGame* game, int action, const uint32_t args[4])
{
    if (const Gen5Fns* g5 = gen5FnsFor(game)) return !(pending.seq && pending.state == 0) ? gen5Action(mem, game, *g5, action, args) : 0;
    const CallFns* f = fnsFor(game);
    if (!f || (pending.seq && pending.state == 0)) return 0;
    u32 parent = freeFieldParent(mem, game);
    if (!parent) return 0;
    if (rd32(mem, parent + 4) != game->fieldMapMain) return 0;
    u32 fs = rd32(mem, game->fieldSystemPtr);

    Ops o;
    const unsigned char* script = nullptr;
    u32 scriptLen = 0;
    switch (action) {
    case PK_NDS_ACTION_PC:
        if (f->scriptEnvironmentNew) {
            hgScriptJob(o, *f, fs, *game);
            script = PK_NDS_HEARTGOLD_PC;
            scriptLen = sizeof(PK_NDS_HEARTGOLD_PC);
        } else {
            scriptJob(o, *f, fs, *game);
            script = PK_NDS_PLATINUM_PC;
            scriptLen = sizeof(PK_NDS_PLATINUM_PC);
        }
        break;
    case PK_NDS_ACTION_FLY_CHECK: {
        if (!f->fieldMovesCheckFly) return 0;
        u32 spots = args[0] < PK_NDS_MAX_OPS - 2 ? args[0] : PK_NDS_MAX_OPS - 2;
        o.add(f->fieldMovesSetUsableMoves, fs, SCRATCH);
        o.add(f->fieldMovesCheckFly, SCRATCH);
        if (f->checkFlyLocationUnlocked)
            for (u32 i = 1; i <= spots; i++) o.add(f->checkFlyLocationUnlocked, fs, i);
        break;
    }
    case PK_NDS_ACTION_FLY: {
        if (!f->flyContextNew) return 0;
        int sd = o.add(PK_NDS_OP_LOAD32, fs, (u32) f->offSaveData);
        int party = o.add(f->saveDataGetParty, Ops::res(sd));
        int mon = o.add(f->partyGetPokemonBySlotIndex, Ops::res(party), args[0]);
        int ctx = o.add(f->flyContextNew, (u32) f->heapField2, fs, Ops::res(mon), f->flyHasSlotArg ? args[0] : args[1]);
        if (f->flyHasSlotArg) o.add(PK_NDS_OP_STORE16, Ops::res(ctx), (u32) f->offFlyMap, args[1]);
        o.add(PK_NDS_OP_STORE16, Ops::res(ctx), (u32) f->offFlyX, args[2]);
        o.add(PK_NDS_OP_STORE16, Ops::res(ctx), (u32) f->offFlyZ, args[3]);
        o.add(f->fieldSystemCreateTask, fs, f->fieldMovesFlyTask, Ops::res(ctx));
        if (!f->journalCreateEventUsedMove) break;
        int ev = o.add(f->journalCreateEventUsedMove, (u32) f->fieldMoveFly, args[1], (u32) f->heapField2);
        int j = o.add(PK_NDS_OP_LOAD32, fs, (u32) f->offJournalEntry);
        o.add(f->journalSaveData, Ops::res(j), Ops::res(ev), (u32) f->journalLocation);
        break;
    }
    default:
        return 0;
    }
    return post(mem, game, parent, o, script, scriptLen);
}

int pk_nds_call_status(int seq)
{
    if (seq == pending.seq) return pending.state;
    if (seq == lastDone) return 1;
    return -1;
}

bool pk_nds_call_results(int seq, uint32_t* out)
{
    if (seq != lastDone) return false;
    memcpy(out, lastResults, sizeof(lastResults));
    return true;
}

void pk_nds_call_frame(const PkNdsCallMemory& mem, const PkNdsFieldGame* game)
{
    if (!pending.seq || pending.state != 0) return;
    u32 done;
    itcmRead(mem, PK_NDS_JOB + offsetof(PkNdsJob, done), &done, 4);
    if (done == (u32) pending.seq) {
        pending.state = 1;
        lastDone = pending.seq;
        itcmRead(mem, PK_NDS_JOB + offsetof(PkNdsJob, res), lastResults, sizeof(lastResults));
        return;
    }
    if (++pending.frames < 8) return;
    // Not called: put the field map's main back if it's still the same manager holding our hook (Gen 5: the event
    // check, if it's still the same GameSystem's).
    if (const Gen5Fns* g5 = gen5FnsFor(game)) {
        if (rd32(mem, g5->gameSystemPtr) == pending.parent && rd32(mem, pending.slot) == PK_NDS_HOOK_ADDR)
            wr32(mem, pending.slot, pending.orig);
        pending.state = -1;
        lastFailed = pending.seq;
        return;
    }
    u32 fs = game ? rd32(mem, game->fieldSystemPtr) : 0;
    u32 pm = inMain(fs) ? rd32(mem, fs + game->offProcessManager) : 0;
    if (inMain(pm) && rd32(mem, pm) == pending.parent && rd32(mem, pending.slot) == PK_NDS_HOOK_ADDR)
        wr32(mem, pending.slot, pending.orig);
    pending.state = -1;
    lastFailed = pending.seq;
}

int pk_nds_call_ops(const PkNdsCallMemory& mem, const PkNdsFieldGame* game, const PkNdsOp* ops, int n)
{
    if (const Gen5Fns* g5 = gen5FnsFor(game)) {
        if ((pending.seq && pending.state == 0) || n <= 0 || n > PK_NDS_MAX_OPS) return 0;
        u32 gs = gen5FreeField(mem, game, *g5);
        if (!gs) return 0;
        Ops o;
        for (int i = 0; i < n; i++) o.op[o.n++] = ops[i];
        return postGen5(mem, *g5, gs, o, false);
    }
    if (!fnsFor(game) || (pending.seq && pending.state == 0) || n <= 0 || n > PK_NDS_MAX_OPS) return 0;
    u32 parent = freeFieldParent(mem, game);
    if (!parent) return 0;
    Ops o;
    for (int i = 0; i < n; i++) o.op[o.n++] = ops[i];
    return post(mem, game, parent, o, nullptr, 0);
}

int pk_nds_call_script(const PkNdsCallMemory& mem, const PkNdsFieldGame* game, const uint8_t* bytes, uint32_t len)
{
    const CallFns* f = fnsFor(game);
    if (!f || (pending.seq && pending.state == 0) || !bytes || len == 0 || len > PK_NDS_SCRIPT_MAX) return 0;
    u32 parent = freeFieldParent(mem, game);
    if (!parent) return 0;
    Ops o;
    if (f->scriptEnvironmentNew) hgScriptJob(o, *f, rd32(mem, game->fieldSystemPtr), *game);
    else scriptJob(o, *f, rd32(mem, game->fieldSystemPtr), *game);
    return post(mem, game, parent, o, bytes, len);
}

void pk_nds_call_itcm_write(const PkNdsCallMemory& mem, uint32_t addr, const void* src, uint32_t len)
{
    if (addr >= PK_NDS_SCRIPT_ADDR && addr + len <= 0x02000000u) itcmWrite(mem, addr, src, len);
}

void pk_nds_call_reset(void)
{
    pending = {};
}
