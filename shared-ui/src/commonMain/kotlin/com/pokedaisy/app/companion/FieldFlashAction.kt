package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*

/** ROM signatures and badge-rule verification, cached within one game host. */
class FieldFlashSupport(private val read: (Long, Int) -> ByteArray) {
    private var cached: Pair<String, FlashFns?>? = null

    fun get(s: SnapshotView, code: String?, revision: Int): FlashFns? {
        if (code == null || !s.hasData || s.party.isEmpty()) return null
        // These player sprites never finish the game's field-move pose, leaving controls locked.
        if (runningFireRedHack === fireRedHackRocketEdition || runningFireRedHack === fireRedHackAshGray) return null
        val rebuild = runningEmeraldHack != null
        val key = "$code/$revision/${s.game}/$romGameCode/$rebuild"
        cached?.let { if (it.first == key) return it.second }
        val candidates = flashCandidatesFor(s.game, code, revision, rebuild)
        val found = runCatching {
            candidates.firstOrNull { flashCodeMatches(it, read) }?.let { flashBadgeRule(it, read) }
        }.getOrElse { return null } // Boot/read failures may be retried; a verified mismatch is cached.
        cached = key to found
        return found
    }
}

/** The game's own FLASH field move and Quick Menu policy, shared by both hosts. */
class FieldFlashAction(
    private val items: FieldItemUse,
    private val metadata: () -> FlashFns?,
    private val snapshot: () -> SnapshotView,
    private val read: (Long, Int) -> ByteArray,
    private val handedOver: () -> Unit = {},
) {
    fun available() = metadata()?.let { flash ->
        HmFree.active || snapshot().party.any { mon -> !mon.isEgg && mon.moves.any { it.id == flash.flashMove } }
    } == true

    fun dark(): Boolean? = metadata()?.let { flash ->
        flashDark(flash, read) { snapshot().progress?.flag(it) }
    }

    fun use(done: (FlashResult) -> Unit) {
        val flash = metadata() ?: return done(FlashResult.FAILED)
        items.onGame(FlashResult.FAILED, { result ->
            if (result == FlashResult.OK) handedOver()
            done(result)
        }) { fields, calls, busy -> useFlash(fields.fns, flash, busy, calls) }
    }
}
