package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile

/** The same verified per-ROM field tables for the two hosts. Failed reads can be retried after boot. */
class FieldItemSupportCache(private val reader: MemoryReader = InProcessReader) {
    @Volatile private var cached: Pair<String, FieldItemSupport?>? = null

    fun get(snapshot: SnapshotView, code: String, revision: Int, configured: FieldItemFns?): FieldItemSupport? {
        // A recognized map alone is not proof of gameplay: the title/intro can expose those defaults too.
        if (!snapshot.hasData || snapshot.party.isEmpty()) return null
        val game = snapshot.game
        // The config's text key and tables too: Emerald Kaizo runs as EMERALD and keeps no classic FLY (fieldItemsForGame);
        // Recharged Emerald shares retail Emerald's code, revision and kind, but not its functions.
        val candidates = fieldItemsCandidatesForGame(game, code, revision, configured)
            .ifEmpty { FlyWithoutMoveGame.active = false; return null }
        // The PC mailbox (TAKE of Mail) depends on the config too: support built before the config was known is rebuilt.
        val cfg = activeNativeConfig
        val key = "$code/$revision/$game/$romGameCode/" + candidates.joinToString(",") { "${it.varGet}/${it.items}" } +
            "|${cfg?.saveBlock1Ptr}/${cfg?.staticSaveBlocks}"
        cached?.let { (k, support) -> if (k == key) return support }
        // The first table whose code is in the ROM (SoulGold's builds share one game).
        val support = runCatching {
            candidates.firstNotNullOfOrNull { fieldItemSupport(it, reader::readCoreMemory, mailboxFor(it, cfg, game)) }
        }.getOrElse { return null }
        cached = key to support
        FlyWithoutMoveGame.active = support?.canFly == true && support.fns.fly?.withoutMove != null
        return support
    }
}
