package com.pokedaisy.app

import android.util.Log
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BUSY
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_TARGET_SELECT
import com.pokedaisy.app.companion.data.GbPartySwitch
import com.pokedaisy.app.companion.data.Gfx
import com.pokedaisy.app.companion.data.InProcessReader
import com.pokedaisy.app.companion.data.MemoryReader

/**
 * Drives the real in-game battle menu (FIGHT/BAG/POKEMON/RUN, then a move, or
 * a Pokémon to switch to) via synthetic button presses injected through
 * [GbaInput.setScript] - exclusively: while a sequence runs, the player's own
 * keys are ignored so they can't throw it off. No ROM patch involved: this
 * just presses buttons a real player could press. See PLAN.md
 * Phase 5 for the full design (why this works unchanged under any GAME SPEED /
 * fast-forward setting, etc). Where the game's menu cursors are known
 * ([cursorAddrs]) a pick reads them and presses only what it needs - the move
 * already highlighted is one A; elsewhere it walks there blind.
 *
 * Thread model: [selectAction]/[selectMove]/[cancel] are called from the UI
 * thread (Compose taps); [tick] and [onState] are called from the emulator
 * thread once per real frame / on the fast telemetry-poll cadence
 * respectively. All mutable state is behind a lock since the two sides run
 * on different threads.
 */
class BattleInputController(
    private val input: GbaInput,
    private val reader: MemoryReader = InProcessReader,
    private val log: (String) -> Unit = { Log.i("pokedaisy-battle", it) },
    /** Writes the game's RAM (the emu thread, between frames): a pick puts the menu's cursor on its cell and presses A
     * once, so the game plays one select sound, not one per cursor step too. Null = the cursor is walked there. */
    private val writer: ((addr: Long, bytes: ByteArray) -> Boolean)? = null,
) {

    /**
     * Where the party menu keeps its cursor and the party, for [switchTo]:
     * FireRed / Emerald and the hacks [PokeDaisyActivity] lists (Lazarus...). [partyMenu] is gPartyMenu (`slotId` at +9), [party] gPlayerParty.
     * [grid]: the slots sit in two columns (0 2 4 | 1 3 5, Lazarus), where
     * DOWN stays in its column - else one list that DOWN walks through.
     * [gb]: a Game Boy game (Yellow, Crystal and its hacks), whose party menu is read through it instead
     * ([driveGbSwitchLocked]); the addresses above go unused then.
     */
    data class SwitchAddrs(
        val partyMenu: Long = 0,
        val party: Long = 0,
        val monStride: Int = 100,
        val grid: Boolean = false,
        val gb: GbPartySwitch? = null,
    )

    /**
     * A Gen 1 game (Yellow): its battle menu is FIGHT / PkMn over ITEM / RUN, two one-column
     * menus, and its moves are one list - not Gen 3's 2x2 grids. So presses go from the cursor
     * the game shows straight to the target ([driveGbPickLocked], read every frame through
     * [SwitchAddrs.gb]; the last poll's [gen1Cursor] without one): no LEFT / UP normalising,
     * which would wrap in a list. Crystal and its hacks share it (the same 2x2 menu and move column).
     */
    @Volatile
    var gen1 = false

    /**
     * A fresh read of the battle input state (the emu thread, between frames), or null. RUN's follow-up presses A
     * only on a state read the same frame: A answers YES to the "Are you sure you want to run away?" Unbound asks
     * (CFRU: a shiny foe, or its flag 0x16C6) - B said NO, and the tap did nothing - but an A that met the action
     * menu coming back ("Can't escape!") between two ~4-frame polls would pick RUN again. Without it, B.
     */
    @Volatile
    var stateNow: (() -> Int?)? = null

    /** The open Gen 1 menu's cursor (action cell column + 2 x row, or move index); -1 unknown. */
    @Volatile
    private var gen1Cursor = -1

    /**
     * Where the battle menus keep their cursors: gActionSelectionCursor and gMoveSelectionCursor,
     * a byte per battler holding the cell (column + 2 x row). With them a pick is a closed loop -
     * read the cursor, press only what moves it toward the cell (again if the game dropped it),
     * A once it's there - so the move already highlighted is one press. Null = the blind walk
     * ([queueCellLocked]: LEFT, UP, then RIGHT / DOWN, A, a settling gap after each).
     */
    data class CursorAddrs(val action: Long, val move: Long, val menus: MenuCells = MenuCells.GEN3)

    /**
     * How the battle menus lay out their cursor values. [GEN3]: both are 2x2 grids, the value = column + 2 x row, and an
     * action's value is its index (0 FIGHT, 1 BAG, 2 POKéMON, 3 RUN). [ELITE_REDUX]: FIGHT / INFO over POKéMON / RUN, valued
     * 0 / 3 over 1 / 2, no BAG (its own battle menu, checked headless: a written 2 + A ran, 1 + A opened the party), and the
     * moves one column of four (DOWN = the next move).
     */
    enum class MenuCells {
        GEN3, ELITE_REDUX;

        /** The cursor value for Gen 3's action [index], or -1 where this menu has no such cell. */
        fun actionValue(index: Int): Int = when (this) {
            GEN3 -> index
            ELITE_REDUX -> when (index) { 0 -> 0; 2 -> 1; 3 -> 2; else -> -1 }
        }

        /** Where cursor [value] sits: column (0 / 1) and row. */
        fun col(value: Int, move: Boolean): Int = when (this) {
            GEN3 -> value and 1
            ELITE_REDUX -> if (move) 0 else if (value == 2 || value == 3) 1 else 0
        }

        fun row(value: Int, move: Boolean): Int = when (this) {
            GEN3 -> (value shr 1) and 1
            ELITE_REDUX -> if (move) value else if (value == 1 || value == 2) 1 else 0
        }
    }

    @Volatile
    var cursorAddrs: CursorAddrs? = null

    /** Set once the running game is known to support [switchTo]; null = it doesn't. */
    @Volatile
    var switchAddrs: SwitchAddrs? = null

    private enum class SwitchPhase { WAIT_MENU, NAVIGATE, CONFIRM, WAIT_CLOSE }

    private data class Step(val bits: Int, val frames: Int)

    private val lock = Any()
    private val queue = ArrayDeque<Step>()
    private var currentBits = 0
    private var framesLeft = 0
    private var pendingMoveIndex: Int? = null
    private var lastKnownState: Int = BATTLE_INPUT_NONE
    /** The battler whose menu is up, as last polled: its byte in the cursor arrays. */
    private var lastBattler = 0

    // A closed-loop pick in flight (see [cursorAddrs]): the cell, which menu, presses so far.
    private var pickCell = -1
    private var pickMove = false
    private var pickPresses = 0

    // A Game Boy pick in flight ([driveGbPickLocked]): the menu it waits on (BATTLE_INPUT_ACTION_SELECT or MOVE_SELECT),
    // the cell / move, presses so far, frames since it started and the frame of its last A (-1: none yet).
    private var gbPickMenu = BATTLE_INPUT_NONE
    private var gbPickCell = -1
    private var gbPickPresses = 0
    private var gbPickFrames = 0
    private var gbPickA = -1

    /** The game's battle input state as last polled (BATTLE_INPUT_NONE outside battles), for SMART fast-forward. */
    @Volatile
    var gameState: Int = BATTLE_INPUT_NONE
        private set
    // Set right after queuing a move-cell confirm. Some moves (anything
    // flagged MOVE_TARGET_USER_OR_SELECTED, e.g. certain status moves) don't
    // submit on that A press even in a single battle - they open a *third*
    // "who does this hit" screen instead, which needs a second A to accept
    // the game's own default target. Without watching for this, such a move
    // would look exactly like "cursor lands on the right move, never fires".
    private var awaitingTargetConfirm = false

    // [switchTo] in flight: the chosen mon's personality, where the closed
    // loop is, and counters that keep a stuck menu from blocking input forever.
    private var switchTarget: Long? = null
    private var switchPhase = SwitchPhase.WAIT_MENU
    private var switchFrames = 0
    private var switchPresses = 0
    private var switchLastSlot = -1
    // A Game Boy switch ([driveGbSwitchLocked]): the menu is the pick after a faint (no SWITCH menu follows), SWITCH's
    // A went out, the frame of the last A (pressed again only while the menu still waits GB_REPRESS_FRAMES later).
    private var gbForced = false
    private var gbSwitchPressed = false
    private var gbLastA = 0
    private var gbAPresses = 0

    // RUN's follow-up: presses left to close "Got away safely!" (or "Can't
    // escape!", or answer a hack's "Are you sure you want to run away?"), the
    // frames until the next one, and whether the game has left action-select
    // since RUN was picked.
    private var runPresses = 0
    private var runGap = 0
    private var runLeftMenu = false

    // Frames spent in a passive watch (target confirm / pending move) with
    // nothing queued, and frames the player's keys have been shut out in a
    // row - both bounded, so nothing here can lock the player out for good.
    private var watchFrames = 0
    private var exclusiveFrames = 0

    // Frames the screen stays held after a switch ends (see [holdFrame]).
    private var holdTail = 0

    /**
     * True while the screen should keep showing the frame from before a
     * [switchTo]: the switch drives the game's own party menu, which fades the
     * battle to black and back - shown, that's a black flash and a party menu
     * the player never asked to see. Held from the switch's start until the
     * game is back in the battle (WaitForMonSelection only hands back once the
     * battle has faded in), plus [HOLD_TAIL_FRAMES] for the state poll to catch up.
     */
    @Volatile
    var holdFrame = false
        private set

    /**
     * True while the game's own sounds for automated presses should go unheard (the engine mutes them in FF): every
     * press of a sequence but its last (the A that does what was tapped; BACK's B) - so a tap makes one game click, not
     * the cursor's chatter at FF speed. The user's call.
     */
    @Volatile
    var muteGame = false
        private set
    // The A being played is the sequence's last: heard from its frame until another press starts.
    private var finalPress = false

    /** True while a queued sequence hasn't fully drained, a move selection is
     * waiting on the FIGHT screen to open, or a submitted move is waiting on
     * a target-confirm we haven't sent yet. UI should disable further taps
     * while this is true, to avoid overlapping sequences. */
    val isBusy: Boolean
        get() = synchronized(lock) { busyLocked() }

    private fun busyLocked() = exclusiveLocked() || pendingMoveIndex != null || awaitingTargetConfirm

    /**
     * Whether the player's own keys are shut out right now: only while presses
     * are queued or playing, or a closed loop (switch / RUN) is steering. Not
     * during the passive watches (a target screen that may follow a move, a
     * move waiting on FIGHT to open): the turn plays out under those, and it can
     * stop on a message that waits for the player's A (a level up, "learn a new
     * move?", a trainer's "Will you change Pokémon?") - shutting them out there
     * left the game stuck with no way to press anything.
     */
    private fun exclusiveLocked() = queue.isNotEmpty() || framesLeft > 0 || switchTarget != null || runPresses > 0 || pickCell >= 0 ||
        gbPickCell >= 0

    /** Call once per real emulated frame, before MgbaCore.pkSetKeys. */
    fun tick() {
        val (bitsToSend, exclusive) = synchronized(lock) {
            if (gbPickCell >= 0) gbPickFrames++
            // The closed-loop steps (party cursor, RUN's B) queue their next
            // presses only once the last ones have played out.
            if (framesLeft <= 0 && queue.isEmpty()) {
                drivePickLocked()
                driveGbPickLocked()
                if (queue.isEmpty() && gbPickCell < 0) {
                    driveSwitchLocked()
                    driveRunLocked()
                }
            }
            if (framesLeft <= 0) {
                val next = queue.removeFirstOrNull()
                currentBits = next?.bits ?: 0
                framesLeft = next?.frames ?: 0
                if (currentBits != 0) {
                    finalPress = queue.none { it.bits != 0 } && pickCell < 0 && (gbPickCell < 0 || gbPickA >= 0) &&
                        pendingMoveIndex == null && switchTarget == null
                }
            }
            if (framesLeft > 0) framesLeft--
            // A target screen follows a move's A within a few frames, and FIGHT
            // opens as fast: a watch still unanswered after that is stale.
            if (queue.isEmpty() && framesLeft <= 0 && (awaitingTargetConfirm || pendingMoveIndex != null)) {
                if (++watchFrames > WATCH_TIMEOUT_FRAMES) {
                    log("watch expired (awaitingTarget=$awaitingTargetConfirm, pending=$pendingMoveIndex)")
                    awaitingTargetConfirm = false
                    pendingMoveIndex = null
                    watchFrames = 0
                }
            } else {
                watchFrames = 0
            }
            // Every sequence is bounded on its own; this is the backstop.
            if (exclusiveLocked()) {
                if (++exclusiveFrames > EXCLUSIVE_TIMEOUT_FRAMES) {
                    log("keys shut out for $exclusiveFrames frames - cancelling")
                    cancelLocked()
                }
            } else {
                exclusiveFrames = 0
            }
            if (switchTarget == null && holdTail > 0) holdTail--
            holdFrame = switchTarget != null || holdTail > 0
            if (!exclusiveLocked()) finalPress = false
            muteGame = (exclusiveLocked() || pendingMoveIndex != null) && !finalPress
            currentBits to exclusiveLocked()
        }
        if (bitsToSend != lastLoggedBits) {
            log("tick sending bits=$bitsToSend")
            lastLoggedBits = bitsToSend
        }
        input.setScript(bitsToSend, exclusive)
    }

    /**
     * Switches to the party mon whose personality is [personality]: picks
     * POKéMON if the action menu is up (or uses the party menu the game
     * already opened, e.g. after a faint), then steers the menu's cursor to
     * that mon and picks SHIFT. Closed loop on the game's own memory: the
     * battle party menu shows the party in battle order (UpdatePartyToBattleOrder
     * really reorders gPlayerParty while it's open), so the mon is looked up
     * there by personality, and DOWN is pressed until gPartyMenu.slotId is its
     * slot - no assumptions about where the cursor starts or how it wraps.
     */
    fun switchTo(personality: Long) {
        synchronized(lock) {
            val addrs = switchAddrs
            if (addrs == null || personality == 0L) return
            log("switchTo(pid=0x${personality.toString(16)}) lastKnownState=$lastKnownState")
            // A Game Boy game: refused before anything is pressed if the mon can't go in (not in the party, fainted,
            // already out) - the game would only say why and wait in its party menu.
            addrs.gb?.let { gb -> gbRefusal(gb, personality)?.let { why -> log("switch refused: $why"); return } }
            pendingMoveIndex = null
            if (lastKnownState == BATTLE_INPUT_ACTION_SELECT) {
                if (gen1) queueGen1ActionLocked(2) else pickCellLocked(2, move = false) // POKéMON (PkMn / PKMN)
            }
            gbForced = false
            gbSwitchPressed = false
            gbAPresses = 0
            switchTarget = personality
            switchPhase = SwitchPhase.WAIT_MENU
            switchFrames = 0
            switchPresses = 0
            switchLastSlot = -1
        }
    }

    private fun driveSwitchLocked() {
        val target = switchTarget ?: return
        val addrs = switchAddrs ?: return endSwitchLocked("no addresses")
        switchFrames++
        addrs.gb?.let { return driveGbSwitchLocked(it, target) }
        when (switchPhase) {
            // The party menu takes a moment to fade in after the controller
            // starts waiting on it; presses before then are dropped.
            // No fixed settle: NAVIGATE presses at once, and the presses the menu drops while it
            // fades in (~50 frames on FireRed and Emerald) are simply pressed again.
            SwitchPhase.WAIT_MENU ->
                if (lastKnownState == BATTLE_INPUT_PARTY_OPEN) {
                    switchPhase = SwitchPhase.NAVIGATE
                    switchFrames = 0
                    switchLastSlot = -1
                } else if (switchFrames > MENU_TIMEOUT_FRAMES) {
                    endSwitchLocked("party menu never opened")
                }
            SwitchPhase.NAVIGATE -> {
                val slot = runCatching { reader.readCoreMemory(addrs.partyMenu + PARTY_MENU_SLOT_ID, 1)[0].toInt() }.getOrNull()
                    ?: return endSwitchLocked("cursor unreadable")
                val want = slotOf(addrs, target) ?: return endSwitchLocked("mon not in the party")
                // Steps the cursor really took (dropped presses don't count).
                if (slot != switchLastSlot) {
                    if (switchLastSlot >= 0) switchPresses++
                    switchLastSlot = slot
                }
                if (slot == want) {
                    pressLocked(MgbaCore.Key.A)      // the mon -> its SHIFT / SUMMARY / CANCEL menu
                    waitFramesLocked(SUBMENU_FRAMES)
                    switchPhase = SwitchPhase.CONFIRM
                } else if (switchPresses > MAX_CURSOR_PRESSES || switchFrames > MENU_TIMEOUT_FRAMES) {
                    endSwitchLocked("cursor didn't reach slot $want")
                } else {
                    pressLocked(cursorKey(addrs, slot, want, partyCount(addrs)))
                    waitFramesLocked(CURSOR_FRAMES)
                }
            }
            SwitchPhase.CONFIRM -> {
                pressLocked(MgbaCore.Key.A)          // SHIFT, the menu's first entry
                switchPhase = SwitchPhase.WAIT_CLOSE
                switchFrames = 0
            }
            // Keep the player's keys out until the game has taken the switch.
            SwitchPhase.WAIT_CLOSE ->
                if (lastKnownState != BATTLE_INPUT_PARTY_OPEN || switchFrames > CLOSE_TIMEOUT_FRAMES) endSwitchLocked("done")
        }
    }

    /** The press that moves the party cursor from [slot] toward [want]: in a list, the shorter way round
     * ([count] mons, then CANCEL; DOWN and UP both wrap through it). */
    private fun cursorKey(addrs: SwitchAddrs, slot: Int, want: Int, count: Int): Int = when {
        !addrs.grid -> listKey(slot, want, count, cancel = true)
        slot !in 0 until 6 -> MgbaCore.Key.UP     // CANCEL: back into the grid
        slot % 2 != want % 2 -> if (want % 2 == 1) MgbaCore.Key.RIGHT else MgbaCore.Key.LEFT
        else -> if (want > slot) MgbaCore.Key.DOWN else MgbaCore.Key.UP
    }

    /** DOWN or UP, the shorter way from [slot] to [want] round a wrapping list of [count] mons (then CANCEL, with [cancel]). */
    private fun listKey(slot: Int, want: Int, count: Int, cancel: Boolean): Int {
        val ring = (0 until count) + (if (cancel) listOf(PARTY_MENU_CANCEL) else emptyList())
        val i = ring.indexOf(slot)
        val j = ring.indexOf(want)
        val down = (j - i + ring.size) % ring.size
        return if (i < 0 || j < 0 || down <= ring.size - down) MgbaCore.Key.DOWN else MgbaCore.Key.UP
    }

    /**
     * A Game Boy game's switch (Yellow, Crystal and its hacks): the same closed loop, on its own party menu
     * ([GbPartySwitch]), which lists the party in its own order. The mon is found by its key
     * ([com.pokedaisy.app.companion.data.gbMonKey]) at every step; A on it, then A on SWITCH (the first item of the
     * menu that opens; none after a faint). The games drop presses for a few frames after drawing a menu's cursor
     * (Yellow's Delay3, Crystal's SWITCH menu for ~30), so a press the menu didn't follow is pressed again - never
     * before it could have been taken: a taken pick turns the cursor hollow at once.
     */
    private fun driveGbSwitchLocked(gb: GbPartySwitch, target: Long) {
        val menu = runCatching { gb.menu(reader) }.getOrNull()
        when (switchPhase) {
            SwitchPhase.WAIT_MENU ->
                if (menu != null) {
                    switchPhase = SwitchPhase.NAVIGATE
                    switchFrames = 0
                    switchLastSlot = -1
                } else if (switchFrames > MENU_TIMEOUT_FRAMES) {
                    endSwitchLocked("party menu never opened")
                }
            SwitchPhase.NAVIGATE -> {
                if (switchFrames > MENU_TIMEOUT_FRAMES) return endSwitchLocked("cursor didn't reach the mon")
                if (menu == null) return                                   // being drawn
                if (menu.submenu) {                                        // a SWITCH menu we didn't open: back to the list
                    pressGbLocked(MgbaCore.Key.B)
                    waitFramesLocked(GB_REPRESS_FRAMES)
                    return
                }
                gbRefusal(gb, target)?.let { return endSwitchLocked(it) }
                val party = runCatching { gb.party(reader) }.getOrNull() ?: return endSwitchLocked("party unreadable")
                val want = party.indexOfFirst { it.key == target }
                val slot = menu.cursor
                // Steps the cursor really took (dropped presses don't count).
                if (slot != switchLastSlot) {
                    if (switchLastSlot >= 0) switchPresses++
                    switchLastSlot = slot
                }
                if (slot == want) {
                    pressGbLocked(MgbaCore.Key.A)                            // the mon -> SWITCH / STATS / CANCEL, or out after a faint
                    waitFramesLocked(GB_SETTLE_FRAMES)
                    gbForced = menu.forced
                    gbLastA = 0
                    gbAPresses = 0
                    switchPhase = SwitchPhase.CONFIRM
                    switchFrames = 0
                } else if (switchPresses > MAX_CURSOR_PRESSES) {
                    endSwitchLocked("cursor didn't reach slot $want")
                } else {
                    pressGbLocked(listKey(slot, want, party.size, gb.hasCancel))
                    waitFramesLocked(CURSOR_FRAMES)
                }
            }
            SwitchPhase.CONFIRM -> when {
                // The pick was taken: the cursor went hollow and the game moves on.
                menu == null ->
                    if (gbForced || gbSwitchPressed) {
                        switchPhase = SwitchPhase.WAIT_CLOSE
                        switchFrames = 0
                    } else if (switchFrames > MENU_TIMEOUT_FRAMES) {
                        endSwitchLocked("SWITCH menu never opened")
                    }
                menu.submenu ->
                    if (menu.cursor != 0) {
                        pressGbLocked(MgbaCore.Key.UP)                       // SWITCH is the first item
                        waitFramesLocked(CURSOR_FRAMES)
                    } else if (!gbSwitchPressed || switchFrames - gbLastA >= GB_REPRESS_FRAMES) {
                        if (++gbAPresses > MAX_GB_A_PRESSES) return endSwitchLocked("SWITCH never took A")
                        pressGbLocked(MgbaCore.Key.A)
                        waitFramesLocked(GB_SETTLE_FRAMES)
                        gbSwitchPressed = true
                        gbLastA = switchFrames
                    }
                // Back on the list after SWITCH: the game said no (already out, can't be recalled, no will to fight).
                gbSwitchPressed -> endSwitchLocked("the game refused the switch")
                // Still on the list: the A on the mon was dropped - steer again from wherever the cursor is.
                switchFrames >= GB_REPRESS_FRAMES -> {
                    switchPhase = SwitchPhase.NAVIGATE
                    switchFrames = 0
                    switchLastSlot = -1
                }
            }
            // The game took it: hold the frame a little longer, while it leaves the party screen (white, then the battle).
            SwitchPhase.WAIT_CLOSE -> if (menu == null || switchFrames > CLOSE_TIMEOUT_FRAMES) {
                endSwitchLocked("done")
                holdTail = GB_HOLD_TAIL_FRAMES
            }
        }
    }

    /** Why the mon keyed [key] can't be sent in (not in the party, fainted, already out), or null. */
    private fun gbRefusal(gb: GbPartySwitch, key: Long): String? {
        val party = runCatching { gb.party(reader) }.getOrNull() ?: return "party unreadable"
        val slot = party.indexOfFirst { it.key == key }
        return when {
            slot < 0 -> "mon not in the party"
            party[slot].hp == 0 -> "slot $slot has fainted"
            slot == runCatching { gb.outSlot(reader) }.getOrDefault(-1) -> "slot $slot is already out"
            else -> null
        }
    }

    /** The party's size (the slots holding a Pokémon). */
    private fun partyCount(addrs: SwitchAddrs): Int {
        val raw = runCatching { reader.readCoreMemory(addrs.party, 6 * addrs.monStride) }.getOrNull() ?: return 6
        return (0 until 6).count { Gfx.u32(raw, it * addrs.monStride) != 0L }.coerceAtLeast(1)
    }

    /** gPlayerParty's slot holding [personality] (the menu's slot while it's open), or null. */
    private fun slotOf(addrs: SwitchAddrs, personality: Long): Int? {
        val raw = runCatching { reader.readCoreMemory(addrs.party, 6 * addrs.monStride) }.getOrNull() ?: return null
        return (0 until 6).firstOrNull { Gfx.u32(raw, it * addrs.monStride) == personality }
    }

    private fun endSwitchLocked(why: String) {
        log("switch ended: $why")
        switchTarget = null
        holdTail = HOLD_TAIL_FRAMES
    }

    private fun driveRunLocked() {
        // RUN's own pick (to the cell, then A) goes first.
        if (runPresses <= 0 || pickCell >= 0) return
        when {
            // The battle ended (got away), or it's back on the menu (couldn't escape): done.
            lastKnownState == BATTLE_INPUT_NONE -> runPresses = 0
            runLeftMenu && lastKnownState == BATTLE_INPUT_ACTION_SELECT -> runPresses = 0
            else -> {
                if (lastKnownState != BATTLE_INPUT_ACTION_SELECT) runLeftMenu = true
                if (--runGap > 0) return
                runGap = RUN_PRESS_GAP
                val read = stateNow ?: run {
                    pressLocked(MgbaCore.Key.B)
                    runPresses--
                    return
                }
                when (runCatching { read() }.getOrNull()) {
                    // A message or a YES / NO: A closes the one and says YES to the other.
                    BATTLE_INPUT_BUSY -> {
                        runLeftMenu = true
                        pressLocked(MgbaCore.Key.A)
                        runPresses--
                    }
                    // Still leaving the menu, or back on it: nothing to press this time.
                    BATTLE_INPUT_ACTION_SELECT -> runPresses--
                    // The battle's over, a party menu after a faint, an unreadable state: done.
                    else -> runPresses = 0
                }
            }
        }
    }
    private var lastLoggedBits = -1

    /** Feed the latest (battleActiveBattler, battleInputState) from the fast
     * telemetry poll (not the ~1 Hz full refresh). Resumes a move selection
     * that's waiting for the FIGHT screen to actually open. */
    fun onState(activeBattler: Int, state: Int, cursor: Int = -1) {
        synchronized(lock) {
            gen1Cursor = cursor
            if (state != lastKnownState) {
                log("state $lastKnownState -> $state (battler=$activeBattler, queue=${queue.size}, framesLeft=$framesLeft, pending=$pendingMoveIndex, awaitingTarget=$awaitingTargetConfirm)")
            }
            lastKnownState = state
            lastBattler = activeBattler.coerceIn(0, 3)
            gameState = state
            // A pick still steering is still on its menu: nothing below has happened yet.
            if (pickCell >= 0 || gbPickCell >= 0) return

            if (awaitingTargetConfirm && queue.isEmpty() && framesLeft == 0) {
                when (state) {
                    BATTLE_INPUT_TARGET_SELECT -> {
                        log("target-select seen, sending confirm A")
                        awaitingTargetConfirm = false
                        pressLocked(MgbaCore.Key.A) // accept the game's own default target
                    }
                    // Anything else here means the move submitted directly
                    // (no target-select needed) or something unrelated
                    // happened - either way, stop watching for it.
                    BATTLE_INPUT_BUSY -> Unit // still resolving; keep waiting
                    else -> {
                        log("dropping awaitingTargetConfirm, state=$state")
                        awaitingTargetConfirm = false
                    }
                }
            }

            val pending = pendingMoveIndex ?: return
            // Doubles' battler-id-to-position mapping isn't handled yet (see
            // PLAN.md) - if the choosing battler changed out from under a
            // pending move (e.g. the partner mon's turn came up), drop it
            // rather than risk firing into the wrong mon's menu.
            if (activeBattler != 0) {
                log("dropping pendingMoveIndex=$pending, activeBattler=$activeBattler != 0")
                pendingMoveIndex = null
                return
            }
            if (state == BATTLE_INPUT_MOVE_SELECT && queue.isEmpty() && framesLeft == 0) {
                log("resuming pendingMoveIndex=$pending now that MOVE_SELECT is open")
                pendingMoveIndex = null
                queueMoveCellLocked(pending)
            }
        }
    }

    /** Cancels any in-flight or pending sequence and releases all keys. */
    fun cancel() {
        synchronized(lock) { cancelLocked() }
    }

    private fun cancelLocked() {
        queue.clear()
        pickCell = -1
        gbPickCell = -1
        pendingMoveIndex = null
        awaitingTargetConfirm = false
        switchTarget = null
        runPresses = 0
        currentBits = 0
        framesLeft = 0
        watchFrames = 0
        exclusiveFrames = 0
        holdTail = 0
        holdFrame = false
    }

    /** actionIndex: 0=FIGHT, 1=BAG, 2=POKEMON, 3=RUN — matches the ROM's own
     * gActionSelectionCursor values (B_ACTION_USE_MOVE/USE_ITEM/SWITCH/RUN). */
    fun selectAction(actionIndex: Int) {
        synchronized(lock) {
            pendingMoveIndex = null
            if (gen1) queueGen1ActionLocked(actionIndex) else pickCellLocked(actionIndex, move = false)
            if (actionIndex == ACTION_RUN) {
                // "Got away safely!" waits for a button: close it (a press while
                // it's still printing just finishes the text).
                runPresses = RUN_PRESSES
                runGap = RUN_FIRST_GAP
                runLeftMenu = false
            }
        }
    }

    /** moveIndex 0-3. The move-select screen only ever gets tapped while it's
     * already open (that's the only time the UI shows move cards at all), so
     * this navigates that grid directly - it must NOT re-run the "open
     * FIGHT" detour here, since LEFT/UP/A would then be misread as move-grid
     * navigation and confirm whatever the game's own default move slot is
     * (0) regardless of which card was tapped. The FIGHT-then-continue path
     * stays available for [onState] to resume a pending selection made while
     * still on the action-select screen (not currently reachable from the
     * UI, since it only calls this once already in MOVE_SELECT, but kept so
     * a future caller doesn't have to re-solve this). */
    fun selectMove(moveIndex: Int) {
        synchronized(lock) {
            log("selectMove($moveIndex) lastKnownState=$lastKnownState col=${col(moveIndex)} row=${row(moveIndex)}")
            if (lastKnownState == BATTLE_INPUT_ACTION_SELECT) {
                pendingMoveIndex = moveIndex
                if (gen1) queueGen1ActionLocked(0) else pickCellLocked(0, move = false) // open FIGHT (action index 0)
            } else {
                pendingMoveIndex = null
                queueMoveCellLocked(moveIndex)
            }
        }
    }

    /** A single B press — backs out of the move-select screen to action-select,
     * same as the real B button. */
    fun back() {
        synchronized(lock) {
            log("back()")
            pendingMoveIndex = null
            // A Game Boy party menu (the POKéMON pane's CANCEL) takes it held: Prism's party menu reads keys every other frame.
            if (gen1) pressGbLocked(MgbaCore.Key.B) else pressLocked(MgbaCore.Key.B)
        }
    }

    // 2x2 grid shared by both the action-select and move-select screens:
    // col = LEFT/RIGHT bit, row = UP/DOWN bit (see battle_controller_player.c's
    // gActionSelectionCursor/gMoveSelectionCursor XOR-toggle logic).
    private fun col(index: Int) = index and 1
    private fun row(index: Int) = (index shr 1) and 1

    /** The normalize-then-walk script: LEFT/UP unconditionally (each a no-op
     * if already there), then RIGHT/DOWN only if the target needs them, then
     * A - each press followed by a settling gap. Lands on the right cell
     * with no need to know the current cursor.
     *
     * The settling gap exists because a cursor move that actually takes
     * effect starts a cosmetic palette fade (BeginNormalPaletteFade), and at
     * least Unbound's rewritten move-select screen appears to ignore *any*
     * further input while that fade is still active - confirmed live twice:
     * first a move whose cell required only one real DPAD move landed the
     * cursor correctly but the immediately-following A never registered;
     * fixed by gapping before A. Then a move needing *two* real moves (e.g.
     * bottom-left -> top-right: UP lands on top-left, then RIGHT) landed on
     * the *intermediate* cell instead of the target - the second directional
     * press was getting swallowed by the first press's own fade the same
     * way, since only the gap before A existed yet. Gapping after every
     * press (not just the last one) fixes both without needing to read
     * gPaletteFade.active, which we have no address for. A press whose guard
     * doesn't apply (a no-op, cursor already there) doesn't start a fade, so
     * the extra gap after it is just unused wait time, not a correctness
     * risk. */
    private fun queueCellLocked(col: Int, row: Int) {
        pressAndSettleLocked(MgbaCore.Key.LEFT)
        pressAndSettleLocked(MgbaCore.Key.UP)
        if (col == 1) pressAndSettleLocked(MgbaCore.Key.RIGHT)
        if (row == 1) pressAndSettleLocked(MgbaCore.Key.DOWN)
        pressLocked(MgbaCore.Key.A)
    }

    private fun pressAndSettleLocked(bit: Int) {
        pressLocked(bit)
        waitFramesLocked(20)
    }

    private fun waitFramesLocked(frames: Int) {
        queue.addLast(Step(0, frames))
    }

    /** Same as [queueCellLocked] but for a move specifically - also arms the
     * target-confirm watch in [onState], since submitting a move (unlike an
     * action) can land on a third "who does this hit" screen instead of
     * completing outright. */
    private fun queueMoveCellLocked(moveIndex: Int) {
        if (gen1) {
            // One list (items 1-4): UP / DOWN from the read cursor, then A. No target screen.
            if (gbPickLocked(BATTLE_INPUT_MOVE_SELECT, moveIndex)) return
            val from = gen1Cursor.takeIf { it in 0..3 } ?: 0
            repeat(kotlin.math.abs(moveIndex - from)) { pressAndSettleLocked(if (moveIndex > from) MgbaCore.Key.DOWN else MgbaCore.Key.UP) }
            pressLocked(MgbaCore.Key.A)
            return
        }
        pickCellLocked(moveIndex, move = true)
        awaitingTargetConfirm = true
    }

    /** Picks [index]'s cell on the action menu or ([move]) the move grid: straight there from the read
     * cursor when [cursorAddrs] are known ([drivePickLocked]), else the blind walk. */
    private fun pickCellLocked(index: Int, move: Boolean) {
        val menus = cursorAddrs?.menus ?: MenuCells.GEN3
        val value = if (move) index else menus.actionValue(index)
        if (value < 0) return log("no cell for action $index on this game's menu")
        if (cursorAddrs == null) return queueCellLocked(col(index), row(index))
        pickCell = value
        pickMove = move
        pickPresses = 0
    }

    /** One step of a closed-loop pick: on the cell, A; else the press toward it (a dropped one is pressed again). */
    private fun drivePickLocked() {
        if (pickCell < 0) return
        val addrs = cursorAddrs ?: return pickFallbackLocked("no addresses")
        val cur = runCatching {
            reader.readCoreMemory((if (pickMove) addrs.move else addrs.action) + lastBattler, 1)[0].toInt() and 0xFF
        }.getOrNull()
        if (cur == null || cur !in 0..3) return pickFallbackLocked("cursor read $cur")
        // The game reads the cursor when A comes (HandleInputChooseAction / ChooseMove) and draws it from there the next
        // time the menu opens: set, it's the press-free way to the cell. Read back, so a write that didn't take walks.
        val write = writer
        if (cur != pickCell && write != null && pickPresses == 0) {
            val addr = (if (pickMove) addrs.move else addrs.action) + lastBattler
            val set = runCatching { write(addr, byteArrayOf(pickCell.toByte())) && (reader.readCoreMemory(addr, 1)[0].toInt() and 0xFF) == pickCell }
                .getOrDefault(false)
            if (set) {
                pickCell = -1
                pressLocked(MgbaCore.Key.A)
                return
            }
        }
        if (cur == pickCell) {
            pickCell = -1
            pressLocked(MgbaCore.Key.A)
            return
        }
        if (++pickPresses > MAX_PICK_PRESSES) return pickFallbackLocked("cursor stayed on $cur")
        val m = addrs.menus
        pressLocked(
            when {
                m.col(cur, pickMove) != m.col(pickCell, pickMove) -> if (m.col(pickCell, pickMove) == 1) MgbaCore.Key.RIGHT else MgbaCore.Key.LEFT
                else -> if (m.row(pickCell, pickMove) > m.row(cur, pickMove)) MgbaCore.Key.DOWN else MgbaCore.Key.UP
            },
        )
    }

    /** The cursor can't be steered (unreadable, or it won't move): the blind walk does the pick instead. */
    private fun pickFallbackLocked(why: String) {
        log("pick of cell $pickCell falls back to the blind walk: $why")
        val cell = pickCell
        pickCell = -1
        val m = cursorAddrs?.menus ?: MenuCells.GEN3
        if (m == MenuCells.GEN3) return queueCellLocked(col(cell), row(cell))
        // A column of moves: to the top (UP wraps nowhere past it), then DOWN to the row.
        if (pickMove) {
            repeat(NUM_MOVE_ROWS) { pressAndSettleLocked(MgbaCore.Key.UP) }
            repeat(m.row(cell, true)) { pressAndSettleLocked(MgbaCore.Key.DOWN) }
            pressLocked(MgbaCore.Key.A)
        } else queueCellLocked(m.col(cell, false), m.row(cell, false))
    }

    /**
     * Gen 1's battle menu: [actionIndex] in Gen 3's numbering (0 FIGHT, 1 BAG, 2 POKEMON,
     * 3 RUN) onto its cells - FIGHT and PkMn on top, ITEM and RUN below. RIGHT / LEFT
     * switches column on the same row, UP / DOWN the row; then A.
     */
    private fun queueGen1ActionLocked(actionIndex: Int) {
        val target = when (actionIndex) { 0 -> 0; 1 -> 2; 2 -> 1; else -> 3 }
        if (gbPickLocked(BATTLE_INPUT_ACTION_SELECT, target)) return
        val from = gen1Cursor.takeIf { it in 0..3 } ?: 0
        if ((from and 1) != (target and 1)) pressAndSettleLocked(if (target and 1 == 1) MgbaCore.Key.RIGHT else MgbaCore.Key.LEFT)
        if ((from shr 1) != (target shr 1)) pressAndSettleLocked(if (target shr 1 == 1) MgbaCore.Key.DOWN else MgbaCore.Key.UP)
        pressLocked(MgbaCore.Key.A)
    }

    /**
     * Starts a Game Boy pick on its battle menu ([menu]: the action menu's [cell], column + 2 x row, or the move list's
     * index), steered by [driveGbPickLocked] on the menu read straight from RAM ([GbPartySwitch.battleMenu]). False (and
     * the blind walk from the last poll's cursor instead) when the game has no reader for it.
     */
    private fun gbPickLocked(menu: Int, cell: Int): Boolean {
        if (switchAddrs?.gb == null) return false
        gbPickMenu = menu
        gbPickCell = cell
        gbPickPresses = 0
        gbPickFrames = 0
        gbPickA = -1
        return true
    }

    /**
     * One step of a Game Boy pick: a closed loop like [drivePickLocked], on the cursor the game shows. The games drop
     * presses for a few frames after drawing a menu or moving its cursor (Polished Crystal: the 3 frames after a cursor
     * move, and the first frames of its action menu and move list, headless; Yellow's Delay3), and a press the poll's
     * cursor was blind to went to the wrong move once (a DOWN dropped as the move list opened, then A on the first
     * move). So: a press toward the cell, read again; A once there; and A again while the menu still waits on that cell
     * [GB_REPRESS_FRAMES] later (a taken pick turns the cursor hollow at once, so a waiting one never took it).
     */
    private fun driveGbPickLocked() {
        if (gbPickCell < 0) return
        val gb = switchAddrs?.gb ?: return endGbPickLocked("no menu reader")
        val (state, cur) = runCatching { gb.battleMenu(reader) }.getOrNull() ?: return endGbPickLocked("menu unreadable")
        val waiting = state == gbPickMenu
        if (gbPickA >= 0) {
            if (!waiting || cur != gbPickCell) return endGbPickLocked(null)    // taken: the game moved on
            if (gbPickFrames - gbPickA < GB_REPRESS_FRAMES) return
            if (++gbPickPresses > MAX_GB_PICK_PRESSES) return endGbPickLocked("A never took")
            pressLocked(MgbaCore.Key.A)
            gbPickA = gbPickFrames
            return
        }
        if (!waiting) {
            if (gbPickFrames > MENU_TIMEOUT_FRAMES) endGbPickLocked("menu never showed")
            return
        }
        if (cur == gbPickCell) {
            pressLocked(MgbaCore.Key.A)
            gbPickA = gbPickFrames
            return
        }
        if (++gbPickPresses > MAX_GB_PICK_PRESSES) return endGbPickLocked("cursor stayed on $cur")
        pressLocked(
            when {
                gbPickMenu == BATTLE_INPUT_MOVE_SELECT -> if (gbPickCell > cur) MgbaCore.Key.DOWN else MgbaCore.Key.UP
                (cur and 1) != (gbPickCell and 1) -> if (gbPickCell and 1 == 1) MgbaCore.Key.RIGHT else MgbaCore.Key.LEFT
                else -> if (gbPickCell shr 1 == 1) MgbaCore.Key.DOWN else MgbaCore.Key.UP
            },
        )
        waitFramesLocked(GB_PICK_SETTLE_FRAMES)
    }

    private fun endGbPickLocked(why: String?) {
        if (why != null) log("pick of cell $gbPickCell ended: $why")
        gbPickCell = -1
    }

    /** A Game Boy party menu's press: held [GB_PRESS_FRAMES] and released as long. Prism's party menu takes input every
     * other frame (a one-frame A was dropped on every try, headless: its re-presses all landed on the same parity), so a
     * press must span two frames; Yellow's and Crystal's take it just the same. Its battle menus read every frame: one-frame
     * RIGHT / DOWN / A / B took at all 8 frame offsets tried (headless), so FIGHT, moves and RUN keep [pressLocked]. */
    private fun pressGbLocked(bit: Int) {
        log("queue press bit=$bit (${keyName(bit)}) x$GB_PRESS_FRAMES")
        queue.addLast(Step(bit, GB_PRESS_FRAMES))
        queue.addLast(Step(0, GB_PRESS_FRAMES))
    }

    /** One real frame pressed, one real frame released — JOY_NEW only fires
     * on the frame a bit transitions from unset to set, so back-to-back
     * presses of the same button need an explicit release between them. */
    private fun pressLocked(bit: Int) {
        log("queue press bit=$bit (${keyName(bit)})")
        queue.addLast(Step(bit, 1))
        queue.addLast(Step(0, 1))
    }

    private companion object {
        const val ACTION_RUN = 3
        const val NUM_MOVE_ROWS = 3            // UPs that reach the top of a one-column move list from anywhere
        const val PARTY_MENU_SLOT_ID = 9L      // struct PartyMenu.slotId, both games
        const val PARTY_MENU_CANCEL = 7        // slotId on CANCEL (after the last mon), both games
        const val MENU_TIMEOUT_FRAMES = 240    // the menu's fade-in drops presses for ~50 frames (headless, both games)
        const val CURSOR_FRAMES = 3            // after each press, before reading the cursor again (it moves every 3)
        const val MAX_PICK_PRESSES = 8         // a 2x2 grid needs two; the rest re-press dropped ones
        const val MAX_CURSOR_PRESSES = 16      // steps the cursor took: two laps of six slots + CANCEL, then give up
        const val SUBMENU_FRAMES = 8           // the SHIFT / SUMMARY / CANCEL menu opening (takes A after 6 on FireRed, 2 on Emerald)
        const val CLOSE_TIMEOUT_FRAMES = 240
        const val GB_PRESS_FRAMES = 2          // Prism's party menu reads keys every other frame (headless)
        const val GB_SETTLE_FRAMES = 2        // after a Game Boy A: a taken pick has turned the cursor hollow by then
        const val GB_REPRESS_FRAMES = 8        // a Game Boy menu still waiting this long after an A dropped it
        const val MAX_GB_A_PRESSES = 12        // Crystal's SWITCH menu drops presses for ~30 frames after it shows (headless)
        const val GB_PICK_SETTLE_FRAMES = 4    // after a battle menu's cursor step: Polished Crystal drops presses for 3 (headless)
        const val MAX_GB_PICK_PRESSES = 12     // two steps at most; the rest re-press dropped ones
        const val GB_HOLD_TAIL_FRAMES = 20     // white, then the battle back: ~8 frames on Yellow, ~14 on Crystal (headless)
        const val RUN_PRESSES = 6
        const val RUN_FIRST_GAP = 45           // the escape message starts printing
        const val RUN_PRESS_GAP = 30
        const val HOLD_TAIL_FRAMES = 12        // the ~4-frame state poll, and a little more
        const val WATCH_TIMEOUT_FRAMES = 300   // a target screen / FIGHT that hasn't shown by then won't
        const val EXCLUSIVE_TIMEOUT_FRAMES = 1200 // a switch's worst case (every step timing out) is ~800
    }

    private fun keyName(bit: Int) = when (bit) {
        MgbaCore.Key.A -> "A"
        MgbaCore.Key.B -> "B"
        MgbaCore.Key.LEFT -> "LEFT"
        MgbaCore.Key.RIGHT -> "RIGHT"
        MgbaCore.Key.UP -> "UP"
        MgbaCore.Key.DOWN -> "DOWN"
        else -> "?$bit"
    }
}
