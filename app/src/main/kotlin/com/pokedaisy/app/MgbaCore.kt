package com.pokedaisy.app

import java.nio.ByteBuffer

/**
 * Thin Kotlin facade over the native mGBA bridge (`libpokedaisy.so`).
 *
 * All methods must be called from the single emulator thread, and so must
 * reads of [pkVideoBuffer]'s [ByteBuffer]: the core draws into it during
 * [pkRunFrame], so the screen gets a copy made between frames
 * ([EmulatorView.publishFrame]).
 */
object MgbaCore {
    init {
        System.loadLibrary("pokedaisy")
    }

    /** GBA key bit positions, matching the native bridge. */
    object Key {
        const val A = 1 shl 0
        const val B = 1 shl 1
        const val SELECT = 1 shl 2
        const val START = 1 shl 3
        const val RIGHT = 1 shl 4
        const val LEFT = 1 shl 5
        const val UP = 1 shl 6
        const val DOWN = 1 shl 7
        const val R = 1 shl 8
        const val L = 1 shl 9
        /** The DS's X / Y (melonDS's key mask bits 10 and 11); a GBA core ignores them. */
        const val X = 1 shl 10
        const val Y = 1 shl 11
    }

    /** Loads [romPath], attaches [savePath] as persistent SRAM, resets. */
    external fun pkInit(romPath: String, savePath: String?): Boolean
    external fun pkDeinit()

    /** Direct buffer over the native RGBA8888 framebuffer, [pkVideoWidth] x [pkVideoHeight]. */
    external fun pkVideoBuffer(): ByteBuffer?
    external fun pkVideoWidth(): Int
    external fun pkVideoHeight(): Int
    external fun pkSampleRate(): Int
    /** Resample the core's audio to [rate] samples per emulated second (default [pkSampleRate]). Emu thread. */
    external fun pkSetAudioRate(rate: Double)

    /** Runs the game's Thumb function [fn] (r0-r3 = [a0]-[a3]) to its return on the player's
     * core and gives back its r0, or -1 if it never returned. Emu thread, between frames. */
    external fun pkCall(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long

    /** FREE FLIGHT (pk_flight.h, companion.data.Flight.kt): the game's addresses ([com.pokedaisy.app.companion.data.FlightFns.words]),
     * or null to switch it off. Emu thread. */
    external fun pkFlightConfigure(words: IntArray?)
    /** Takes off: [FLIGHT_OK] or why not ([FLIGHT_BUSY], [FLIGHT_NOT_NOW], [FLIGHT_NOT_ON_FOOT]). Emu thread, between frames. */
    external fun pkFlightTakeOff(): Int
    /** Lands on the nearest tile a player can stand on. */
    external fun pkFlightLand()
    /** The scenic FLY: flies [dirs] (1 down, 2 up, 3 left, 4 right) at the faster speed, then lands. */
    external fun pkFlightRoute(dirs: ByteArray): Int
    /** FLIGHT AREA (companion.data.FlightArea): a bit per map (group shl 8 or num) the flight may cross into, null = anywhere. Emu thread. */
    external fun pkFlightArea(bits: ByteArray?)
    /** True once after the D-pad met FLIGHT AREA's edge (a map the player hasn't seen). Emu thread. */
    external fun pkFlightAreaTurned(): Boolean
    /** [FLIGHT_IDLE], [FLIGHT_TAKING_OFF], [FLIGHT_FLYING], [FLIGHT_LANDING] or [FLIGHT_DISMOUNT]. */
    external fun pkFlightState(): Int
    /** Steps of the route flown so far, -1 with none. */
    external fun pkFlightRouteDone(): Int

    const val FLIGHT_IDLE = 0
    const val FLIGHT_TAKING_OFF = 1
    const val FLIGHT_FLYING = 2
    const val FLIGHT_LANDING = 3
    const val FLIGHT_DISMOUNT = 4
    const val FLIGHT_OK = 0
    const val FLIGHT_NOT_CONFIGURED = -1
    const val FLIGHT_BUSY = -2
    const val FLIGHT_NOT_NOW = -3
    const val FLIGHT_NOT_ON_FOOT = -4

    /** Whether [pkCall] can run now: the frame ended with the CPU in the BIOS, not part-way through the game's own
     * code (its interrupt handler, a lag frame) - pokedaisy_jni.c's pk_call_safe. Field-item jobs wait a frame if not. */
    external fun pkCallSafe(): Boolean

    external fun pkSetKeys(mask: Int)
    external fun pkRunFrame()

    /** Runs frames (no keys, no sound) until a save the game is writing has reached the file, at most [maxFrames];
     * returns how many ran. Emu thread. */
    external fun pkFinishSave(maxFrames: Int): Int

    /** WIDE ([WideScreen]): draw the game whose ROM has this CRC32 ([WideScreen.gameFor]; 0 = off). Emu thread. */
    external fun pkWideConfigure(crc: Int)
    /** WIDE: whether pk_wide.c has tables for the ROM with this CRC32 (pk_wide_games.h). Any thread. */
    external fun pkWideKnows(crc: Int): Boolean
    /** WIDE and the field reads: know the ROM with CRC32 [crc] by [row] (a hack [com.pokedaisy.app.companion.data.RetailEngine]
     * proved to keep the retail build [baseCrc]'s engine). One at a time. Any thread, before the game is configured. */
    external fun pkWideAddDerived(crc: Int, baseCrc: Int, row: IntArray): Boolean
    /** SCENE SPEEDS / AUTO-RUN / RUN INDOORS / WILD ENCOUNTERS: read the field with WIDE's tables for the ROM with
     * this CRC32, WIDE on or off (0 = none), and its wild-encounter switch at [wildFlag] ([WildEncounters.find]; 0 =
     * none). Emu thread. */
    external fun pkFieldConfigure(crc: Int, wildFlag: Int, wildOff: Boolean)
    /** Whether the field reads know the ROM with this CRC32 (WIDE's table, or a hack WIDE doesn't draw yet). Any thread. */
    external fun pkFieldKnows(crc: Int): Boolean
    /** This frame's [FIELD_KNOWN] ... [FIELD_WILD_HELD] bits (0 = a game it doesn't know); [runIndoors] also lets the
     * player run on the current map, [wildKeep] < 100 leaves about that percent of wild encounters. Emu thread,
     * between frames. */
    external fun pkFieldState(runIndoors: Boolean, wildKeep: Int): Int

    /** DAY AND NIGHT: the map's BG palettes (0-12) and the sprites' drawn tinted by [r] / [g] / [b] (256 = as is),
     * in the renderer only. GBA; emu thread. */
    external fun pkSetTint(r: Int, g: Int, b: Int)

    /** PLAYER LOOK: the player's sprite set up again from its graphics record, as the game does getting on a bike (a
     * Pokémon's look changes the record; the next look rewrites the tail its frame table sat in). Emu thread. */
    external fun pkLookRefresh(pal: ByteArray?): Boolean

    /** FOLLOWER: the Pokémon's six 32x32 frames (3072 bytes) and palette (32); nulls = none. Emu thread. */
    external fun pkFollowerSet(frames: ByteArray?, pal: ByteArray?)
    /** RIDE ON's vehicle (companion.data.RideOn.Sprite): three views (1024 bytes each, OBJ tile order: 32 x 64 or 64 x 32 by
     * [width]) and a palette, or null. Emu thread. */
    external fun pkRideSet(frames: ByteArray?, pal: ByteArray?, width: Int)
    /** RIDE ON's vehicle drawn this frame (behind the player, only while they're on a bike). Emu thread. */
    external fun pkRideShow(on: Boolean)

    /** FOLLOWER: drawn from the next frame on, or not (off, it reappears only once the player steps). Emu thread. */
    external fun pkFollowerShow(on: Boolean)

    /** Co-op presence: the player this frame - valid, map group, map num, fine x, fine y (object-event coords x 16 +
     * the step's pixels), facing (DIR_*), mode (PresenceMode), step count (pk_wide.h's PK_PARTNER_SELF_*); null = not a
     * GBA game the field reads know. Emu thread, once a frame (the step's progress needs every frame). */
    external fun pkPartnerSelf(): IntArray?

    /** Co-op presence: the partner's art, `PartnerArt.ART_BYTES` (21536: 42 32x32 4bpp frames + the palette); null or any
     * other length = none. Emu thread. */
    external fun pkPartnerSet(art: ByteArray?)

    /** Co-op presence: the partner drawn from the next frame on - fine position (object-event coords x 16 + pixels), one of
     * the art's 42 frames, mirrored, the surf blob's frame under it (-1 = none) and its bob in pixels - as hardware
     * sprites at each frame's line 0, put back after line 159 (pk_wide.c's partnerBegin / partnerEnd). Emu thread. */
    external fun pkPartnerPlace(visible: Boolean, fineX: Int, fineY: Int, frame: Int, hflip: Boolean, blobFrame: Int, bob: Int)

    // pkFieldState's bits (pk_wide.h's PK_FIELD_*).
    const val FIELD_KNOWN = 1
    const val FIELD_LOCKED = 2
    const val FIELD_BATTLE = 4
    const val FIELD_TEXT = 8
    const val FIELD_BIKE = 16
    /** WILD ENCOUNTERS set the game's switch: keep calling (with 100) until it's been put back. */
    const val FIELD_WILD_HELD = 32
    const val FIELD_SURF = 64
    /** [FIELD_BIKE] / [FIELD_SURF] come from the game's own player state (FIELD SPEED's BIKE / SURF rows work). */
    const val FIELD_AVATAR = 128
    /** A DS game's own: the overworld is on screen (its field map runs, nothing over it) - what FfMenuWatch.onField
     * tells for a GBA game. */
    const val FIELD_SCREEN = 256
    /** A DS game's own: a menu screen (party, bag, Pokédex, ...) runs over the field. */
    const val FIELD_MENU = 512
    /** A DS game's own: a battle waits for the player's command (FIGHT / BAG / POKéMON / RUN, a move, a target). */
    const val FIELD_BATTLE_INPUT = 1024
    /** A DS game's own: the game wants its touch screen now (DS LAYOUT floats it in; nds/pk_nds_field.cpp's touch rules). */
    const val FIELD_TOUCH = 2048
    /** A DS game's own: a battle's command or move panel is on the touch screen (BATTLE BUTTONS stand in for it). */
    const val FIELD_TOUCH_BATTLE_MENU = 4096
    /** A DS game's own: a menu screen's page is all on the touch screen (it floats in alone, not beside the top one). */
    const val FIELD_TOUCH_ONLY = 8192
    /** A DS game's own: the touch screen [FIELD_TOUCH] wants is a new one the game fades in first (a battle's bag / party). */
    const val FIELD_TOUCH_FADES = 16384
    /** A DS game's own: the top screen / the touch screen is one colour (black between scenes). */
    const val FIELD_DARK_TOP = 32768
    const val FIELD_DARK_BOTTOM = 65536
    /** A DS game's own: a menu screen all on the top screen, its touch screen a picture (Platinum's party): the game alone. */
    const val FIELD_TOP_ONLY = 131072
    /** A GBA game: a window up on the field's text layer while its controls are free (a START menu that doesn't lock them,
     *  Recharged Yellow's): AUTO-RUN keeps its B out of it (GitHub #610). */
    const val FIELD_WINDOW = 262144
    /** WIDE: the window to draw around the frame from the next frame on ([WideCamera]), in the frame's pixels
     * (its top-left 0, 0); [w] 0 = none. Any thread. */
    external fun pkWideSetView(x: Int, y: Int, w: Int, h: Int)
    /** Direct buffer over the wide frame (room for the largest window); null for a Game Boy core. */
    external fun pkWideBuffer(): ByteBuffer?
    /** The last frame's window ([pkWideBuffer] holds [pkWideWidth] x [pkWideHeight]), or 0 if it wasn't drawn wide. Emu thread. */
    external fun pkWideWidth(): Int
    external fun pkWideHeight(): Int
    /** WIDE: draw the next frame's window, or (false) only keep its RAM - a frame the screen won't show. Emu thread. */
    external fun pkWideSetDraw(draw: Boolean)
    /** WIDE: the last frame was only noted ([pkWideSetDraw] false): nothing new to put on screen. Emu thread. */
    external fun pkWideSkipped(): Boolean
    /** WIDE: the signal that made it switch itself off for this game (a fault while drawing, caught: the game goes on
     * with the plain frame), or 0. */
    external fun pkWideFault(): Int
    /** ZOOM: whether the last frame wants the game seen close up (a battle, a menu, a text box). Emu thread. */
    external fun pkWideWantsClose(): Boolean
    /** ZOOM: what the last frame is ([WideCamera.SCENE_FIELD] ... [WideCamera.SCENE_OFF]), known a frame early so the
     * pull-in starts before anything shows (a battle's flashes, a text box). Emu thread. */
    external fun pkWideScene(): Int

    /** REWIND: keep [entries] states, one every 2 frames (0 = off, its memory freed). Emu thread. */
    external fun pkSetRewind(entries: Int)

    /** While on, each [pkRunFrame] steps back through the REWIND buffer (standing still at its start). */
    external fun pkSetRewinding(on: Boolean)

    /** Copies up to [out].size interleaved L/R s16 samples of the last frame; returns count. */
    external fun pkReadAudio(out: ShortArray): Int

    external fun pkSaveState(path: String): Boolean
    external fun pkLoadState(path: String): Boolean
    /** Whether the save inside the state at [path] is the save file the game has open
     * (1), isn't (0: loading it would overwrite the file with an older save), or the
     * state holds none (-1). Call after [pkInit]. */
    external fun pkStateMatchesSave(path: String): Int

    // --- memory / ROM introspection (emu-thread only) ---

    /** Reads [len] bytes of emulated bus memory from GBA address [addr]. */
    external fun pkReadBytes(addr: Long, len: Int): ByteArray?
    /** [pkReadBytes] into [dst] (its whole length), no new array; false with no core up. */
    external fun pkReadInto(addr: Long, dst: ByteArray): Boolean

    /** Writes [bytes] at GBA address [addr], EWRAM / IWRAM only (false otherwise); a Game Boy game's WRAM, a Color bank as
     * (bank << 16) | D000-DFFF. Emu thread, between frames. */
    external fun pkWriteBytes(addr: Long, bytes: ByteArray): Boolean

    /** A Game Boy game calls [fn] (bank 0; A = [a], B = [b], HL = [hl]) from its main loop at [at], coming back there
     * after - false if the loop isn't running (pk_gb_field.c). Emu thread, between frames. */
    external fun pkGbCallAt(at: Int, fn: Int, a: Int, b: Int, hl: Int): Boolean

    /** 4-char game code from the ROM header (`BPRE`, `BPEE`, …). */
    external fun pkRomCode(): String?
    external fun pkRomSize(): Long
    /** [PLATFORM_GBA], [PLATFORM_GB], [PLATFORM_NDS] or -1 (no core). */
    external fun pkPlatform(): Int

    const val PLATFORM_GBA = 0
    const val PLATFORM_GB = 1
    const val PLATFORM_NDS = 2
    /** A Game Boy / Color cart's ROM bytes (it's bank-switched, so not on the bus whole); null on GBA. */
    external fun pkRomRead(off: Long, len: Int): ByteArray?

    // --- Nintendo DS (nds/pk_nds.cpp: melonDS) ---
    // A DS cart goes through the calls above too: [pkVideoBuffer] is its top screen, [pkReadBytes] reads main RAM
    // (0x02000000) and the cart's ROM file from 0x08000000, [pkSetKeys] takes [Key] plus [Key.X] / [Key.Y].

    /** The DS's bottom (touch) screen, 256 x 192 RGBA like [pkVideoBuffer]; null without a DS. Emu thread, between frames. */
    external fun pkNdsBottomBuffer(): ByteBuffer?

    /** The stylus on the DS's bottom screen at ([x], [y]) in its pixels, or lifted ([down] false). Any thread. */
    external fun pkNdsTouch(down: Boolean, x: Int, y: Int)

    /** Whether a DS game's coming frame / the one after are shown (fast-forward shows one of each group): unseen ones
     * skip their drawing. Emu thread, before [pkRunFrame]. */
    external fun pkNdsSetDraw(draw: Boolean, drawNext: Boolean)

    /** DS LAYOUT floats the X menu (one screen): it comes back after a menu screen left by touch, as after B (the game
     * closes it then: HeartGold / SoulSilver's menuInputState). Emu thread. */
    external fun pkNdsKeepStartMenu(on: Boolean)

    /** A DS game's screens fading by master brightness (towards black or white): the factor 0-16, the top screen's in
     * bits 0-7, the touch screen's in 8-15; 0 = no fade. Emu thread, after [pkRunFrame]. */
    external fun pkNdsFade(): Int

    /** DS CPU BOOST: the emulated ARM9 at twice the DS's clock - a game's own slow frames (HeartGold walking) go. Emu thread. */
    external fun pkNdsCpuBoost(on: Boolean)

    /** DS CORE (companion.DsCore): the DS core the next [pkInit] of a DS cart starts, NDS_CORE_*. Emu thread. */
    external fun pkNdsSetCore(core: Int)
    /** The DS core that's up (NDS_CORE_*), -1 with no DS game. */
    external fun pkNdsCore(): Int
    /** melonDS (nds/pk_nds.cpp): every DS feature. */
    const val NDS_CORE_MELONDS = 0
    /** DSperate (nds/pk_nds_dsp.cpp): for weak devices; no EXPAND, DS RESOLUTION, PC / FLY calls, STEADY or DS CPU BOOST. */
    const val NDS_CORE_DSPERATE = 1
    /** [pkStateMatchesSave]'s answer for a DS state the other core made: it won't load. */
    const val STATE_OTHER_CORE = 2
    /** A DS game's Action Replay codes ([words], [lengths] words per cheat: cheats.DsCheats) into melonDS's AREngine,
     * replacing the ones before; none = no cheats. Emu thread, between frames. The count loaded, -1 with no DS game. */
    external fun pkNdsCheats(words: IntArray, lengths: IntArray): Int

    /** DS RESOLUTION (nds/pk_nds_gl.h): the 3D at [scale] times the DS's on the GPU (2-8), or the DS's own on the CPU
     * (1). Taken before the next frame. Emu thread. */
    external fun pkNdsSetScale(scale: Int)
    /** The scale in effect: 1 = the CPU's (asked for, or the GPU path isn't available here). */
    external fun pkNdsScale(): Int
    /** Whether the GPU path can run here: -1 not tried yet, 0 no (no OpenGL ES 3.2), 1 yes. Any thread. */
    external fun pkNdsGpuSupported(): Int
    /** The game screen's SurfaceTexture's Surface (an android.view.Surface; null: none) for the GPU path's frames. Any
     * thread. */
    external fun pkNdsGlSetWindow(surface: Any?)
    /** The last frame went out through the GPU path (into that Surface). Emu thread. */
    external fun pkNdsGlPresented(): Boolean
    /** The GPU path's frame presented at [timestamp] (SurfaceTexture.getTimestamp): its window x, y, w, h in DS pixels
     * and its texels wide / high into [out] (6). False when not known. Any thread. */
    external fun pkNdsGlFrameInfo(timestamp: Long, out: IntArray): Boolean

    /** The cart's wild encounter rates by map header id, [map, grass, surf] triples (Gen4World.wildRates): WILD ENCOUNTERS
     *  back on restores a map's from here. Emu thread, once the DS core is up. */
    external fun pkNdsWildRates(triples: IntArray)

    /** STEADY FF music for a DS game (nds/pk_nds_music.h): the song the game plays (its BGM id; 0 = none or fading to the
     * next, -1 = this cart's sound system isn't mapped), a copy of the game that plays on from now at 1x by itself, one
     * frame (1/60 s) of its audio into [out] (interleaved stereo; returns the shorts written), its fade-out over [frames]
     * frames, and stopping it. Emu thread, between frames. */
    external fun pkNdsBgm(): Int
    /** Whether the song can be copied now: -1 not yet (a volume fade runs, or its player is paused or not started), else
     * its player's state - a copy taken at another state is taken again (the game paused, faded or resumed the song). */
    external fun pkNdsBgmState(): Int
    external fun pkNdsMusicStart(): Boolean
    external fun pkNdsMusicRead(out: ShortArray): Int
    external fun pkNdsMusicFade(frames: Int)
    external fun pkNdsMusicStop()

    /** A DS game's own functions (nds/pk_nds_call.h): posts [action] (NDS_ACTION_*) for the next frame - 0 when the
     * field isn't free, else the job's number. Emu thread, between frames. */
    external fun pkNdsAction(action: Int, a0: Int, a1: Int, a2: Int, a3: Int): Int

    /** Job [seq]'s results once it ran (op i's into [i]; NDS_ACTION_* say which mean what), else null. Emu thread. */
    external fun pkNdsActionResults(seq: Int): IntArray?

    /** How job [seq] did: 1 ran, 0 still waiting, -1 never will (the field changed under it). Emu thread. */
    external fun pkNdsActionStatus(seq: Int): Int

    /** PC anywhere: the Pokémon Storage System's menu, as a Pokémon Center PC opens it. */
    const val NDS_ACTION_PC = 1
    /** FLY's checks: [1] = the game's FLY error (0 = may fly from here), [1 + n] = fly spot n unlocked. */
    const val NDS_ACTION_FLY_CHECK = 2
    /** FLY (slot, map header, x, z): the game's own cut-in, flight and landing. */
    const val NDS_ACTION_FLY = 3

    // --- cheats (pk_cheats.c, see cheats/Cheats.kt) ---

    /** Checks a cheat's code lines ('\n'-separated) as [type] (CheatType.native), after
     * [directive] ("" or a .cheats directive). No core needed, any thread. Returns
     * "<directive mGBA settled on>\n<'1' / '0' per line>", null on failure. */
    external fun pkCheatsCheck(code: String, type: Int, directive: String): String?

    /** Emu thread: replaces the core's cheats with [text] (mGBA .cheats, enabled
     * ones only; "" = none). Returns how many loaded, -1 if it didn't parse. */
    external fun pkCheatsApply(text: String): Int

    /** Emu thread: a game option's ROM patch (REUSABLE TMS, [com.pokedaisy.app.companion.data.ReusableTms]) -
     * [value] (1-4 bytes) at [addr], only where the ROM holds [expect] from [expectAddr]. The Poller's reads and
     * RetroAchievements' hash still see the file's bytes, like a cheat's. False if the code didn't match. */
    external fun pkRomPatch(expectAddr: Long, expect: ByteArray, addr: Long, value: ByteArray): Boolean

    /** Emu thread: every game option's ROM patch out again. */
    external fun pkRomUnpatch()

    /** Emu thread: PLAYER LOOK's blocks ([com.pokedaisy.app.companion.data.PlayerLook]) in place of any before - block i
     * is [lens][i] bytes of [data] (back to back) at [addrs][i], only where the ROM's CRC32 there is [crcs][i] (the
     * file's bytes). All or none: how many went in, or -1. The Poller's reads and RetroAchievements' hash still see the
     * file's bytes. */
    external fun pkRomBlocks(addrs: IntArray, lens: IntArray, crcs: IntArray, data: ByteArray): Int

    /** Emu thread: every PLAYER LOOK block out again. */
    external fun pkRomUnblock()

    // --- Co-op (pk_coop.c's retail check, the link bridge below, the after-battle steps): retail English Emerald only, main core,
    // emu thread. A joined battle is the game's own link battle; the caller runs pkCoopInstall (+ pkCoopLinkInstall) once the game
    // is on a free field. Everything is a no-op (false / 0 / null) on a ROM that isn't retail Emerald.

    /** Emu thread: the retail check - every function co-op reads or calls CRC-checked against retail Emerald's; changes nothing in
     * the game or the ROM. False for any other ROM. Safe to call every second. */
    external fun pkCoopInstall(): Boolean

    /** Why the last [pkCoopInstall] refused (the function whose code did not match, or "not a GBA game"); empty after a pass. For the log. */
    external fun pkCoopRefused(): String

    /** Why the link bridge last refused (install, begin, host go, partner go); empty = nothing. For the log. */
    external fun pkCoopLinkRefused(): String

    /** Emu thread: forgets the check. */
    external fun pkCoopUninstall()

    /** Emu thread: true while a battle's start-up is running (Task_BattleStart) after a passed check. */
    external fun pkCoopBattleStarting(): Boolean

    /** Emu thread: true while the game stands free on the field (CB2_Overworld, controls not locked, no palette fade,
     * not in a battle). */
    external fun pkCoopOnFreeField(): Boolean

    /** Emu thread: while a battle starts (pkCoopBattleStarting): 0 not joinable, 1 an ordinary wild battle, 2 two trainers, 3 one trainer with two or more Pokemon. */
    external fun pkCoopJoinKind(): Int

    /** Emu thread: [trainerA, trainerB] of a kind-2 battle, [trainerA, 0] of a kind-3 one, zeros otherwise. */
    external fun pkCoopJoinTrainers(): IntArray

    /** Emu thread: free on the field, or on the START menu's top level. */
    external fun pkCoopPartnerFree(): Boolean

    // --- Co-op link battles (pk_coop_link.c, plan 6): the game runs the battle as an Emerald link battle and the app carries
    // its blocks (`[u8 kind][u8 seq][u16 size][bytes]`) between the two games. Retail English Emerald only, emu thread.

    /** Puts the bridge in (CRC-checked head patches); false = refused, nothing changed. Call before [pkCoopLinkBegin]. */
    external fun pkCoopLinkInstall(): Boolean

    /** The game enters a link battle ([master] = the host; [kind] PK_COOP_LINK_WILD 1 / TRAINERS 2 / SINGLE 3 / STORY 4). */
    external fun pkCoopLinkBegin(master: Boolean, kind: Int, trainerA: Int, trainerB: Int, name: ByteArray, gender: Int, otId: Int): Boolean

    /** The next outgoing block, null when none. */
    external fun pkCoopLinkTake(): ByteArray?

    /** Writes a received block; false = refused (shape, state, full). The caller has checked the generic size shape. */
    external fun pkCoopLinkGive(block: ByteArray): Boolean

    /** PK_COOP_LINK_IDLE 0 / SETUP 1 / BATTLE 2 / ENDED 3. */
    external fun pkCoopLinkState(): Int

    /** The last blocks the bridge refused as malformed (records of check u16, where u8, kind u8, size u16, bytes; see pk_coop_link_bad_blocks); null = none. */
    external fun pkCoopLinkBadBlocks(): ByteArray?

    /** Ends the link battle: [blackout] = a story battle lost to a link drop. The native pk_coop_link_leave (task 7): [blackout] = the game's whiteout, else back
     * to the field, no prize, set-aside party restored; the bridge is reset. Emu thread; pump state / take (CoopLinkBattle.afterFrame) every frame. */
    external fun pkCoopLinkLeave(blackout: Boolean)

    /** The finished battle's result: the 16 bytes of pk_coop_link_result, once the end's steps (prize, flags, evolutions, white-out) are through. */
    external fun pkCoopLinkResult(): ByteArray?

    // --- How a joined battle starts and ends (plan 6 task 8b); every call is main core, emu thread, between frames. ---

    /** The host's game is held where a joined battle is offered (a wild battle, two trainers, one trainer with 2+ Pokemon). */
    external fun pkCoopLinkHostHold(): Boolean

    /** The held battle becomes the link battle; [name] / [gender] / [otId] are the PARTNER's. False = refused, the held battle as it was. */
    external fun pkCoopLinkHostGo(name: ByteArray, gender: Int, otId: Int): Boolean

    /** The partner's start from its free field; [name] / [gender] / [otId] are the HOST's. 1 started, -1 the field is not free yet, 0 refused. */
    external fun pkCoopLinkPartnerGo(kind: Int, trainerA: Int, trainerB: Int, name: ByteArray, gender: Int, otId: Int): Int

    /** The partner said yes at a story trainer's spot: the next `dotrainerbattle` runs the team pick. False = refused. */
    external fun pkCoopStoryArm(trainerId: Int): Boolean

    /** PK_COOP_STORY_*: 0 idle, 1 armed, 2 picking, 3 ready, 4 cancelled, 5 mismatch. */
    external fun pkCoopStoryState(): Int

    /** Both teams are picked: the link battle begins ([master] = the host; the other player's identity). False = not yet / refused. */
    external fun pkCoopStoryGo(master: Boolean, name: ByteArray, gender: Int, otId: Int): Boolean

    /** The pick was backed out of or the join fell through: the game's own solo battle starts. */
    external fun pkCoopStoryCancel(): Boolean

    /** The story hook out, the state idle. */
    external fun pkCoopStoryDisarm()

    /** What the battle caught: byte 0 who threw (1 host, 2 partner) then the Pokemon's 100 bytes; null = nothing. Read before reset / leave. */
    external fun pkCoopLinkCaught(): ByteArray?

    /** The bridge goes (hooks out, area zeroed) and the result is dropped: only once the result was read. */
    external fun pkCoopLinkReset()

    /** A story script held by its trainerbattle is let go: 1 released, 0 nothing held, -1 not yet (ask again on a later frame). */
    external fun pkCoopReleaseScript(): Int

    // coop-field (plan 4 Task 7)
    // coop-after (plan 4 Task 8): a battle's results written through the game's own functions, on the emu thread, only while
    // the game stands free on the field (else -1). Every one: -1 = not now (no free field / no safe point; nothing
    // changed, try again on a later frame).
    /** 0 = in the party, 1 = in a PC box (the Pokédex's seen and caught set for its species), 2 = no room, -1. */
    external fun pkCoopGiveMon(mon: ByteArray): Int

    /** 1 removed, 0 not held in that number or a value out of range (item 1..376, count 1..99), -1. */
    external fun pkCoopRemoveItem(item: Int, count: Int): Int

    /** FlagSet(TRAINER_FLAGS_START + id): 1, 0 for an id outside 1..854, -1. */
    external fun pkCoopSetTrainerFlag(id: Int): Int

    /** The prize for this trainer in a two-trainer battle (single = false: no x2) or alone in a one-trainer battle (single = true: x2 for a double-battle trainer), from the ROM tables (no Amulet Coin), read on any frame; -1 for a bad id. */
    external fun pkCoopPrize(id: Int, single: Boolean): Int

    /** AddMoney: 1, 0 for an amount outside 1..999,999, -1. */
    external fun pkCoopAddMoney(amount: Int): Int

    /** A returned Pokémon into its own party slot (same personality and OT ID as what the slot holds, else 0): 1, 0, -1. */
    external fun pkCoopWritePartyMon(slot: Int, mon: ByteArray): Int

    /** The game's own white-out: 1 started, -1. */
    external fun pkCoopWhiteOut(): Int

    /** The game's evolution scene for party slot [slot]: 1 started, 0 nothing to evolve into, -1. */
    external fun pkCoopEvolve(slot: Int): Int

    /** The START menu's save steps: 1 saved, 0 the game said it failed, -1 not started (retry), -2 started but never returned: do not retry, treat as failed and keep the backup. */
    external fun pkCoopSaveGame(): Int

    /** Emu thread: the 32-byte palettes in [from] (back to back) swapped for [to] wherever the game has one loaded
     * (EWRAM's palette buffers, OBJ palette RAM). Returns how many. */
    external fun pkRepalette(from: ByteArray, to: ByteArray): Int

    // --- FF-music rendering (see FfMusicRenderer) ---
    // A second, fully independent core, never the player's real session —
    // no save file, never shown, never touched by input. Same one-frame-
    // then-drain contract as pkRunFrame/pkReadAudio, just its own handle.

    external fun pkRenderInit(romPath: String): Boolean
    external fun pkRenderDeinit()
    external fun pkRenderRunFrame()
    external fun pkRenderSampleRate(): Int
    external fun pkRenderReadAudio(out: ShortArray): Int

    /** Calls the ROM's own m4aSongNumStart(songId, alt) at [addr] on the render
     * core and returns once it has, CPU state untouched (see the JNI side).
     * [addr] must be right for the ROM loaded there (M4aSongs finds it); [alt]
     * only means something to Heart and Soul's (its alternate soundtrack), but
     * it's always set: r1 was whatever the interrupted game left there. */
    external fun pkRenderForceSong(addr: Long, songId: Int, alt: Int): Boolean

    /** Parks the render core's main loop on [spin] (a Thumb `b .`) so the booted game can't
     * change the music; the sound engine keeps running from VBlank. False if it never got
     * to a safe moment (see the JNI side). */
    external fun pkRenderPark(spin: Long): Boolean

    /** A Game Boy render core's [pkRenderPark]: the main loop goes to [spin] (a `jr @` in bank 0, or a WRAM address the
     * `jr @` is written to first). */
    external fun pkRenderGbPark(spin: Int): Boolean

    /** Calls the Game Boy render core's [fn] (bank 0) with A = [a], C = [c], until it returns to [ret]. */
    external fun pkRenderGbCall(fn: Int, a: Int, c: Int, ret: Int): Boolean

    /** [pkReadBytes] for the render core (which song it's really playing - see FfMusicKey). */
    external fun pkRenderReadBytes(addr: Long, len: Int): ByteArray?
}
