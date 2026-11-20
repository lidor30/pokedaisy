// PokeDaisy - co-op joined battles on retail English Emerald. A joined battle is the game's own link battle on both devices (the
// link bridge, pk_coop_link.c and its parts, below); pk_coop.c is the retail check under it. Plain C over `struct mCore*`, no JNI,
// so native-capture/mgba_dump.c compiles these very files in and tests them headless (scripts/coop_headless_check.sh).
#pragma once

#include <stddef.h>
#include <stdint.h>

struct mCore;

// Co-op's EWRAM past the bridge's (PK_COOP_LINK_RAM below): the story lock's RAM starts at 0x0203F400 and the story join's at
// 0x0203FA00; 0x0203F000-0x0203F3FF is free. EWRAM the game never uses: its last symbol ends
// at 0x0203CF64, and FieldItems' scratch is 0x0203FFF0.
#define PK_COOP_RAM     0x0203F000u

// Calls the game's Thumb function `fn` with r0-r3 = args between frames, to its return; returns r0, or 0xFFFFFFFF if
// it never returned (the app's pk_call / mgba_dump's `call`).
typedef uint32_t (*pk_coop_call_fn)(struct mCore* core, uint32_t fn, const uint32_t args[4]);

// The retail check: 1 = the first 32 bytes of every function co-op reads or calls match retail English Emerald's (the lock's and the
// link bridge's own head patches don't count), 0 = not (pk_coop_refused says which function, or "not a GBA game"). Changes nothing in
// the game or the ROM; safe to call every second.
int  pk_coop_install(struct mCore* core);
void pk_coop_uninstall(struct mCore* core);                        // forgets the check (pk_coop_battle_starting says 0 again)
int  pk_coop_battle_starting(struct mCore* core);                  // 1 after a passed check while Task_BattleStart is active
// Why the last pk_coop_install refused: the first function whose code didn't match, or "not a GBA game"; "" after one passed.
const char* pk_coop_refused(void);
// 1 while the check holds.
int pk_coop_installed(void);
// Forgets the check (the app's teardown).
void pk_coop_reset(void);

// ---------------------------------------------------------------------------------------------------------------------
// Plan 6: joined battles as Emerald link battles (pk_coop_link.c). The game's own link multi battle (the tower's: two players
// against two AI trainers, the host the link master) runs on both devices; link.c's block layer is replaced, while the battle
// runs, by Thumb stubs (scripts/coop_link_emerald.s) that hand every block the game sends to the app and write every block the
// app gives into the game's receive buffers as the cable's serial interrupt would. No serial line is emulated.

#define PK_COOP_LINK_RAM       0x0203D000u   // struct pk_coop_link_ram + the outbox + the party backup (EWRAM past the game's last symbol, clear of the lock's 0x0203F400 and the story join's 0x0203FA00)
#define PK_COOP_LINK_RAM_SIZE  0x2000u
#define PK_COOP_LINK_OUT       0x40u         // the outbox: PK_COOP_LINK_OUT_N entries of PK_COOP_LINK_ENTRY bytes
#define PK_COOP_LINK_OUT_N     16
#define PK_COOP_LINK_ENTRY     0x108u        // u32 src (the game's address), u16 size, u16 0, 0x100 bytes
#define PK_COOP_LINK_BLOCK_MAX 0x100u        // BLOCK_BUFFER_SIZE: a bigger block is refused
#define PK_COOP_LINK_BACKUP    0x1100u       // the party backup: 6 x 100 bytes, offset in the area

// kind: what the battle is (pk_coop_link_setup.kind).
enum { PK_COOP_LINK_WILD = 1, PK_COOP_LINK_TRAINERS, PK_COOP_LINK_SINGLE, PK_COOP_LINK_STORY };
// pk_coop_link_state.
// PK_COOP_LINK_ERROR: the game sent a block the bridge has no kind for (so it can never reach the other player) - the two games are out
// of step, the app should end the battle (pk_coop_link_reset); pk_coop_link_give refuses everything from then on.
enum { PK_COOP_LINK_IDLE, PK_COOP_LINK_SETUP, PK_COOP_LINK_BATTLE, PK_COOP_LINK_ENDED, PK_COOP_LINK_ERROR };
// A block on the wire = [u8 kind][u8 seq][u16 size, little-endian][size bytes]; `kind` is the bridge's own, from where in the
// game the block came from (pk_coop_link_take) and checked against its shape on the way in (pk_coop_link_give). `seq` counts
// the blocks one direction sent, from 0: a block whose seq isn't the next one is a stale or lost one and is refused.
enum {
    PK_COOP_LINKB_HEADER = 1,   // the link battler header (version signature 0x0300, health flags, Enigma berry)
    PK_COOP_LINKB_PARTY,        // one to two of the sender's own Pokémon (100 bytes each), gPlayerParty's blocks
    PK_COOP_LINKB_ENEMY,        // one to two of gEnemyParty's Pokémon (the master's are the ones that count)
    PK_COOP_LINKB_SEED,         // the recorded battle's RNG seed (4 bytes)
    PK_COOP_LINKB_BUFFER,       // one battle controller message: [buffer id][battler][attacker][target][u16 size][absent][effect][data]
    PK_COOP_LINKB_MPARTY,       // the three Pokémon the party showcase screen shows of the sender (struct MultiPartnerMenuPokemon x 3)
    PK_COOP_LINKB_COUNT
};
#define PK_COOP_LINK_WIRE_HEADER 4

// Callers zero the whole struct first (memset): fields a kind does not use, picks included, must be 0.
struct pk_coop_link_setup {
    uint8_t master;               // 1 = this game is the link master (the host, multiplayer id 0), 0 = the partner (id 1)
    uint8_t kind;                 // PK_COOP_LINK_*
    uint16_t trainerA, trainerB;  // the two AI trainers (gTrainers ids), for TRAINERS (the master builds their parties)
    uint8_t partnerName[8];       // the OTHER player's name (Emerald's charset, 0xFF-terminated), gender (0 boy, 1 girl) and OT id
    uint8_t partnerGender;
    uint32_t partnerOtId;
    uint8_t picks[3];             // task 6 (STORY): the party slots picked on the party screen, 1-based, in order (0 = none: the first able three)
};

// The bridge's RAM at PK_COOP_LINK_RAM (our Thumb code reads / writes the first fields; pk_coop_link.c checks the layout).
struct pk_coop_link_ram {
    uint8_t active;        // 1 from begin to reset
    uint8_t myId;          // multiplayer id: 0 = the host (link master), 1 = the partner
    uint8_t ended;         // CloseLink ran, or pk_coop_link_end: no more blocks go in
    uint8_t kind;          // PK_COOP_LINK_*
    uint8_t head, tail;    // the outbox ring: head = next to write (the stub), tail = next to take (pk_coop_link_take)
    uint8_t txSeq, rxSeq;  // the next block's seq out / in
    uint16_t sent, overrun, dropped, refused, received;
    uint8_t backupCount;   // gPlayerPartyCount before begin trimmed the party
    uint8_t backupValid;
    uint8_t seen;          // the game has been in the link battle (BATTLE_TYPE_LINK_IN_BATTLE was set)
    uint8_t error;         // the game sent a block of no known kind (take dropped it): the link is out of step
};

// Installs the bridge's code block (0x08FFD000, own 4 KB) after checking every function it replaces or calls against the bus:
// 1 = in, 0 = refused (nothing changed, see pk_coop_link_refused). `call` runs a game function between frames (pk_call).
int  pk_coop_link_install(struct mCore* core, pk_coop_call_fn call);
void pk_coop_link_uninstall(struct mCore* core);                       // reset, then the block out
const char* pk_coop_link_refused(void);
int  pk_coop_link_installed(void);
void pk_coop_link_forget(void);                                        // forgets it all without touching the core (the app's teardown already took the ROM changes out)
// Starts the link battle from the field: the head patches in, the link players, the first three able Pokémon of the party
// (backed up, put back by reset), on the master the two AI trainers' parties, then the game's own CB2_InitBattle with the
// tower multi's battle flags. 1 = the game is entering the link battle; 0 = refused or no pk_call-safe frame right now (nothing
// changed: ask again on a later frame).
int  pk_coop_link_begin(struct mCore* core, const struct pk_coop_link_setup* setup);
// The next block the game sent, as a wire block (header + bytes); its size, or 0 for none. Needs `cap` >= PK_COOP_LINK_WIRE_HEADER
// + PK_COOP_LINK_BLOCK_MAX.
int  pk_coop_link_take(struct mCore* core, uint8_t* out, int cap);
// Writes a wire block into the game's receive buffer of the other player and sets its received flag: 1 = written; 0 = refused
// (not in a link battle, a stale seq, a kind, size or content the game could never have sent - a Pokemon with a species, move, item or PP
// out of range, an egg, a controller answer the sender may not give: on the master the partner answers only for its own battler (2),
// with an action 0-3, a move slot and target 0-3, or one of its own live Pokemon at party slot 3-5, never a command - or the game
// hasn't read the last block yet: ask again). A refused block changes nothing in the game.
int  pk_coop_link_give(struct mCore* core, const uint8_t* block, int size);
int  pk_coop_link_state(struct mCore* core);                           // PK_COOP_LINK_*
// The last (up to 4) blocks give refused as malformed or take could not classify, for the log: records of [u16 check (a source line of
// pk_coop_link.c, 0 = a check without a code)][u8 where: 1 give, 2 take][u8 kind][u16 size][the block, wire header included]. Bytes written.
int  pk_coop_link_bad_blocks(uint8_t* out, int cap);
// The battle is over (or the app says so): no more blocks go in. The hooks stay until reset.
void pk_coop_link_end(struct mCore* core);
// Hooks out, the party and the link flags put back, the area zeroed.
void pk_coop_link_reset(struct mCore* core);
// Puts the file's own bytes into `dst`, a bus read of [addr, addr + len), where a head patch is in (co-op's install CRC-checks
// functions the bridge replaces while a link battle runs).
void pk_coop_link_original_bytes(uint32_t addr, uint8_t* dst, int len);

// ---------------------------------------------------------------------------------------------------------------------
// task 2 (plan 6): a joined trainer battle starts as a link battle on both games, from the field (pk_coop_link_start.c).
// Needs pk_coop_install (the join offer's rules, pk_coop_join_kind) and pk_coop_link_install.
//
// The host: 1 while its game is held where a joined battle is offered (Task_BattleStart running - the transition has usually
// started by then, one frame in - two trainers, or one trainer with 2+ Pokemon) and the bridge is idle: the moment
// pk_coop_battle_starting / the app's CoopGuards.hold freezes the frames at. They stay held until pk_coop_link_host_go or the app gives the offer up.
int pk_coop_link_host_hold(struct mCore* core);
// The host's start once the partner said yes (`partner` = the partner's name, gender and OT id; master / kind / trainer ids are
// filled in here from the held battle): the task is destroyed, pk_coop_link_begin runs as the master (the first three able Pokemon,
// the trainers' parties, two trainers' halves of gEnemyParty or one trainer's split 0 2 4 / 1 3 5), then gMain.savedCallback is the
// script's CB2_EndTrainerBattle again and gTrainerBattleOpponent_A / _B are the real ids. 1 = the game enters the link battle;
// 0 = refused, the held battle exactly as it was (flags, callbacks, ids, enemy party): it goes on as the game's own solo battle.
int pk_coop_link_host_go(struct mCore* core, const struct pk_coop_link_setup* partner);
// The partner's start from its own free field (pk_coop_on_free_field; no task to stop): `setup` as pk_coop_link_begin takes it
// with master = 0, kind / trainerA / trainerB the host's. 1 = started, -1 = the field is not free yet (ask again on a later frame),
// 0 = refused (pk_coop_link_refused says why; "no safe frame" is worth asking again).
int pk_coop_link_partner_go(struct mCore* core, const struct pk_coop_link_setup* setup);
// Writes the ids the game had before the link battle back into gTrainerBattleOpponent_A / _B, once (the host's script reads them in
// CB2_EndTrainerBattle; the host's savedCallback goes back too; the partner's own ids on the partner): pk_coop_link_state calls it
// when it first says PK_COOP_LINK_ENDED, before the game's own end runs.
void pk_coop_link_end_ids(struct mCore* core);
// Called by pk_coop_link_begin: note = what a refused begin (unwind) or the end puts back (A / B, savedCallback, gEnemyParty);
// patches = the AI flag choice (each foe trainer's own scripts, as in the solo game) in / out with the link hooks.
void pk_coop_link_start_note(struct mCore* core);
void pk_coop_link_start_unwind(struct mCore* core);
int  pk_coop_link_start_patches_in(struct mCore* core);
void pk_coop_link_start_patches_out(struct mCore* core);
extern int pk_coop_link_test_fail;   // headless tests only: begin refuses after it has written everything (mgba_dump linkforcefail)
// Called by pk_coop_link_begin / reset: the real trainer ids into the game, a single trainer's party split between the two foe
// halves (master only; 1 = done, 0 = not a trainer with 2+ Pokemon), the host's saved values forgotten.
void pk_coop_link_start_ids(struct mCore* core, const struct pk_coop_link_setup* setup);
int  pk_coop_link_start_single_party(struct mCore* core, pk_coop_call_fn call, uint16_t trainer);
void pk_coop_link_start_forget(void);
// task 3: a joined wild battle (pk_coop_link_wild.c; a TRAINER-less link multi battle, RUN rolls like a wild one's).
// The wild kind's per-battle code patches (*in = how many went in); 1 = all in, 0 = nothing in (refused).
int  pk_coop_link_wild_patches_in(struct mCore* core, int* in);
void pk_coop_link_wild_patches_out(struct mCore* core, int* in);
// The host's two wild Pokémon rolled from the map's table into gEnemyParty slots 0 and 3 (the rest zeroed): 1 = rolled.
int  pk_coop_link_wild_roll(struct mCore* core, pk_coop_call_fn call);
// ---- task 6: story battles (pk_coop_link_story.c) ----
// The story RAM (outside the bridge's area: begin zeroes that): state, the picked slots, the pick script (scripts/coop_link_emerald.s).
#define PK_COOP_STORY_RAM     0x0203FA00u
#define PK_COOP_STORY_SIZE    0x40u
#define PK_COOP_STORY_SCRIPT  0x12u   // offset of the pick script in it
enum { PK_COOP_STORY_IDLE, PK_COOP_STORY_ARMED, PK_COOP_STORY_PICKING, PK_COOP_STORY_READY, PK_COOP_STORY_CANCELLED, PK_COOP_STORY_MISMATCH };
// The partner said yes at a story trainer's spot: the next `dotrainerbattle` of the game's script runs the team pick screen first
// (`special ChooseHalfPartyForBattle`) instead of starting the battle. Needs the link bridge installed, on the field with no battle.
// `trainerId` = the gTrainers id the offer was for: the hook takes only a `dotrainerbattle` whose gTrainerBattleOpponent_A is that, any other
// trainer's battle starts as the game does (and the state turns MISMATCH, 5: pk_coop_story_state then takes the hook out).
// `call` runs a game function between frames (pk_call): cancel and go use it. 1 = armed, 0 = refused.
int  pk_coop_story_arm(struct mCore* core, pk_coop_call_fn call, uint16_t trainerId);
int  pk_coop_story_state(struct mCore* core);   // PK_COOP_STORY_*; IDLE when not armed
// READY (a team picked on this device): the link battle begins with those slots as the party and the story trainer(s), the story
// battle's own end callback (CB2_EndTrainerBattle) in place. `setup` carries master and the partner's name / gender / OT id as for
// pk_coop_link_begin (kind, trainers and picks are set here). 1 = the game is entering the link battle; 0 = not READY or begin
// refused / no safe frame (ask again).
int  pk_coop_story_go(struct mCore* core, struct pk_coop_link_setup* setup);
// The pick screen was backed out of (CANCELLED) or the join fell through (READY): the hook out and the game's own, solo battle
// starts. 1 = started.
int  pk_coop_story_cancel(struct mCore* core);
void pk_coop_story_disarm(struct mCore* core);  // the hook out, the state IDLE (a join that ended before the script reached it)
void pk_coop_story_forget(void);                // forgets it all without touching the core (the app's teardown took the ROM changes out)
// task 7 (pk_coop_link_drop.c): ends this game's side of the link battle now, from any frame (no game function is called). blackout = 1:
// the game's whiteout (CB2_WhiteOut); 0: back to the field, no prize, trainer flags untouched, what the battle earned kept (pk_coop_link_reset puts
// the set-aside party back). Then the bridge is reset (state IDLE). No-op unless a link battle is set up or running (idempotent).
void pk_coop_link_leave(struct mCore* core, int blackout);
// task 7: the heap tidy-up after a leave. arm (begin calls it) notes the heap blocks in use on the field; tick (state / take call it each frame)
// waits until a leave's battle loop has returned to the game's own end callback, then frees every block the battle's screens still hold
// (party / bag / summary / evolution / animation buffers) and zeroes gBattleTypeFlags. Both are no-ops with no leave pending.
void pk_coop_link_drop_arm(struct mCore* core);
void pk_coop_link_drop_tick(struct mCore* core);
// state / take MUST be pumped after EVERY emulated frame on the emu thread: the tidy-up above frees only inside the one-frame window after
// the game leaves the battle loop (the field has allocated nothing yet); a later poll leaks that battle's screens instead (pk_coop_link_drop_leaks counts them).
int pk_coop_link_drop_leaks(struct mCore* core);
// --- task 4: EXP, the BAG and catching in a joined link battle (pk_coop_link_rewards.c; scripts/coop_link_emerald.s's `task 4` block) ---
// begin puts the rewards' patches in with the link's, reset takes them out. With them, a foe that faints gives each side's Pokémon a
// single battle's full EXP (the master gives a partner Pokémon's EXP to its own copy too, the partner's game to its own), items
// can be used from the BAG (a medicine / X item of the partner's: from the partner's own bag, applied to the master's copy of the
// Pokémon as well), and a ball is thrown at the one wild foe left (two foes up, or the wrong one: refused, the ball stays in the bag).
// A catch isn't put in a party or box of the temporary link party: it waits in the rewards' RAM for pk_coop_link_caught.
#define PK_COOP_LINK_REWARDS 0x0203E400u     // struct pk_coop_link_rewards, inside the bridge's RAM (zeroed with it)
struct pk_coop_link_rewards {
    uint16_t expHost, expPartner, shareHost, sharePartner;   // each side's EXP / Exp. Share EXP for the foe that fainted (our Thumb code)
    uint8_t caughtBy;      // 0 nothing caught, 1 the host threw the ball, 2 the partner did
    uint8_t patchesIn;     // how many of the rewards' ROM patches are in (pk_coop_link_rewards_in)
    uint8_t swallowed;     // partner-made controller commands the partner's SendBlock swallowed (counted, not sent)
    uint8_t boxFrames;     // the partner Pokemon's level-up box: frames since its last automatic A press
    uint8_t thrower;       // who threw the ball being handled + 1 (the ball wrapper), for the catch commands
    uint8_t pad[3];
    uint8_t caught[100];   // the caught foe's struct Pokemon, as the host's game had it (its nickname, its ball; no OT of the catcher's yet)
    uint32_t otId[2];      // the host's and the partner's trainer ID (pk_coop_link_begin), whoever's game this is: what a catch is re-keyed to
};
// Called by begin once the link players are in: who is the master here, this game's trainer ID and the other player's.
void pk_coop_link_rewards_players(struct mCore* core, int master, uint32_t ownOtId, uint32_t partnerOtId);
int  pk_coop_link_rewards_in(struct mCore* core);            // 1 = in, 0 = refused (nothing changed; pk_coop_link_rewards_refused)
void pk_coop_link_rewards_out(struct mCore* core);
const char* pk_coop_link_rewards_refused(void);
void pk_coop_link_rewards_original_bytes(uint32_t addr, uint8_t* dst, int len);   // like pk_coop_link_original_bytes, for its head patches
// What was caught during the battle: 0 nothing, 1 the host's ball, 2 the partner's; the Pokémon's 100 bytes in `mon`. Read it before
// pk_coop_link_reset (which zeroes the area); the catcher's own game gives it to itself after the battle (pk_coop_give_mon: its OT,
// name and gender, the Pokédex) - never the other game.
int  pk_coop_link_caught(struct mCore* core, uint8_t mon[100]);
// --- task 5 (plan 6): a joined link battle ends like a normal battle in each game (pk_coop_link.c's merge, pk_coop_link_end.c) ---
// The game's own end, whole: CB2_InitEndLinkBattle's frontier branch (one nop in the file, while the bridge is begun) skips the VS
// screen, the record prompt and the link reconnect and goes to gMain.savedCallback - CB2_EndWildBattle (field again, or the game's
// own CB2_WhiteOut on a LOST / DREW outcome). The rest is the app's, once per battle, on each game's own field: the party merged
// back (below), the prize, the trainers' flags, the evolutions, a blackout the game wouldn't have made.
struct pk_coop_link_merge {
    uint8_t count;     // the pre-battle party's size, put back
    uint8_t matched;   // how many of them came out of the battle (found by personality + OT id; the rest are the set-aside reserves)
    uint8_t leveled;   // bit i: original slot i went up a level in the battle (an evolution may be waiting)
    uint8_t able;      // 1 = some Pokemon of the whole restored party can fight
};
// The party back as a merge (see pk_coop_link.c); idempotent. Safe to call whenever; a battle that never ended merges what it has.
void pk_coop_link_restore(struct mCore* core, struct pk_coop_link_merge* out);
const struct pk_coop_link_setup* pk_coop_link_setup_get(void);   // what begin was given, NULL outside begin .. reset
int  pk_coop_link_drained(struct mCore* core);                    // 1 = every block the game queued has been taken (a reset now loses none)
// After every frame (the app takes its core lock around it like pk_coop_tick: it writes the game): the first frame the bridge is ENDED
// (before the game's own IsPlayerDefeated / CB2_WhiteOut look at the party) it snapshots the outcome and merges the party back; from then on
// each frame the game is on a free field it does the next step: the prize (WON, a TRAINERS battle: both trainers', no x2; SINGLE: that
// trainer's own), the trainers' flags, one evolution scene per leveled Pokemon, a blackout (WON / RAN / caught with nothing in the whole
// party able to fight; a LOST battle with an able reserve is no blackout). No result is ready until all of it ran.
// MUST run after EVERY frame (a missed frame can miss the one before the game's own white-out check), under the core WRITE lock (it
// writes game RAM and calls the game).
void pk_coop_link_tick(struct mCore* core);
int  pk_coop_link_mon_item(struct mCore* core, uint32_t mon);   // the held item of the struct Pokemon at `mon` (Amulet Coin check)
// The result, 16 bytes once the whole end ran, the party is back and the outbox is empty (so a reset loses nothing): returns 16, else 0.
// [0] version 1; [1] outcome (gBattleOutcome: 1 won, 2 lost, 3 drew, 4 ran, 7 caught; 0 = the battle never reached its end);
// [2] flags: b0 blackout (a white-out was started), b1 two trainers' battle, b2 link lost (no prize, no flags); [3] leveled bits (original slots);
// [4..5] u16 trainer A, [6..7] u16 trainer B (0 = none: the trainers whose flags were set); [8..11] u32 the prize paid (0 = none);
// [12..15] 0. Reading doesn't clear it.
#define PK_COOP_LINK_RESULT_SIZE 16
int  pk_coop_link_result(struct mCore* core, uint8_t* out, int cap);
void pk_coop_link_result_clear(struct mCore* core);               // the app has read it (also the next begin's): drop it
