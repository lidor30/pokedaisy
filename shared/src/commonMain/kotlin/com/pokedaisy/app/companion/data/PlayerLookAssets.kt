package com.pokedaisy.app.companion.data

import okio.Buffer
import okio.Path

/** Prepares the appearance choices for one cart using the player's ROM library. Call on an asset worker. */
class PlayerLookAssets(private val files: Path, private val isRetail: (String) -> Boolean) {
    class Catalog(
        val engine: LookEngine,
        val scan: PlayerLook.Scan,
        val known: Map<LookChar, Map<LookPart, ByteArray>>,
        val heroes: Map<Triple<Boolean, LookChar?, Boolean>, PlayerLook.Patch?>,
        val text: Map<LookEngine, List<PlayerLook.Block>>,
    ) {
        fun availableHeroes(female: Boolean?): List<LookChar> = LookChar.entries.filter { look ->
            if (female == null) listOf(false, true).any { heroes[Triple(it, look, false)]?.blocks?.isNotEmpty() == true }
            else look != LookChar.of(engine, female) && heroes[Triple(female, look, false)]?.blocks?.isNotEmpty() == true
        }
    }

    fun prepare(
        running: AppearanceAssets.Source,
        engine: LookEngine,
        code: String,
        library: () -> List<AppearanceAssets.Source>,
        current: () -> Boolean,
    ): Catalog? {
        if (!current()) return null
        val rom = running.read()
        val scan = PlayerLook.scan(rom, TextBlob.entries)
        if (!current()) return null
        val assets = AppearanceAssets(files)
        assets.keep(rom, scan)

        // Only verified carts and supported hacks can wear a look. Other carts can still supply assets.
        val hash = sha1(rom).hex()
        val rev = rom.getOrNull(0xBC)?.toInt()?.and(0xFF)
        val size = rom.size.toLong()
        val verified = isRetail(hash) || hash in PlayerLook.HACKS ||
            RetailDumps.matchStream(code, rev, size) { Buffer().write(rom) } != null ||
            RandomizedRetail.matchStream(code, rev, size) { Buffer().write(rom) } != null
        if (!verified || !current()) return null

        if (LookArt.available(files).size < LookChar.entries.size ||
            TextLook.STYLES.any { TextArt.load(files, it) == null } ||
            MonArt.sources(files).size < MonArt.Source.entries.size
        ) {
            assets.scanMissing(running.identity, library(), current)
        }
        if (!current()) return null

        val known = LookChar.entries.mapNotNull { look -> LookArt.load(files, look)?.let { look to it } }.toMap()
        val heroes = mutableMapOf<Triple<Boolean, LookChar?, Boolean>, PlayerLook.Patch?>()
        val rivals = if (PlayerLook.hasRival(engine)) listOf(false, true) else listOf(false)
        for (female in listOf(false, true)) {
            val own = LookChar.of(engine, female)
            for ((look, art) in known) {
                if (look == own) continue
                for (rival in rivals) {
                    if (!current()) return null
                    heroes[Triple(female, look, rival)] = PlayerLook.build(scan, own, look, art, known, rival)
                }
            }
            // The original look still restores palettes left by an older saved state.
            val off = PlayerLook.Patch(emptyList(), PlayerLook.settleBoth(scan, own, null, null, known), scan.data(own, LookPart.OW_PAL))
            for (rival in rivals) heroes[Triple(female, null, rival)] = off
        }
        val host = TextLook.styleOf(engine)
        val texts = if (host == null) emptyMap() else TextLook.STYLES.filter { it != host }.mapNotNull { style ->
            val art = TextArt.load(files, style) ?: return@mapNotNull null
            TextLook.build(scan, host, style, art).takeIf { it.isNotEmpty() }?.let { style to it }
        }.toMap()
        return Catalog(engine, scan, known, heroes, texts)
    }
}
