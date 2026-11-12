package com.pokedaisy.app.companion.data

/**
 * PC anywhere, FLY and WILD ENCOUNTERS on the Game Boy games (Yellow in all five languages, Red / Blue; Crystal rev
 * 0, rev 1 and Australia): the GBA's FieldItems.kt / Pc.kt / Fly.kt / WildEncounters.kt calls have no counterpart
 * here, so each game gets the way its own code already takes, from pret/pokeyellow's, pokered's and pokecrystal's
 * sources (their builds are the retail ROMs; the European Yellows' addresses mapped by scripts/port_gen1.py):
 *
 * - Crystal queues a map script for the overworld to run once the player stands free (RunMemScript, from
 *   PlayerEvents: wMapReentryScriptQueueFlag / Bank / Address). The PC is the Pokémon Center PC's own standard
 *   script (PCScript: opentext, special PokemonCenterPC, closetext), FLY the field move's own
 *   (FlyFunction.FlyScript, after wDefaultSpawnpoint = the Fly map's pick). Its random encounters wait while
 *   wWildEncounterCooldown is 2 or more (CheckWildEncounterCooldown, a step at a time), which is held there.
 * - Gen 1 has no such queue. Its PC is the Pokémon Center PC tile's routine (OpenPokemonCenterPC past its facing
 *   check: the PC's predef text), called from the overworld loop right after its frame wait through the game's own
 *   Bankswitch (MgbaCore.pkGbCallAt, pk_gb_field.c); FLY is what the town map's A leaves for the loop - the
 *   destination, BIT_FLY_WARP / BIT_USED_FLY and (Yellow) Pikachu hidden (Func_1510) - which then flies; its
 *   encounters roll against the map's wGrassRate / wWaterRate, which are held at 0 (and put back from the map's
 *   wild data in ROM).
 *
 * Checked headless on Yellow, Red, Blue, the European Yellows and Crystal (mgba_dump's `gbpoke` / `gbcallat`): the
 * PC's menus over the field and back to walking, the take-off and the landing at the picked town's Pokémon Center,
 * and no battle in 100 steps of Route 29's grass (one within ~15 without). The code each step relies on is checked
 * in the ROM first ([signatures]).
 */
sealed class GbField {
    /** Cart offsets and the bytes the code there must have (RAM addresses it uses included). */
    protected abstract val signatures: List<Pair<Long, String>>

    fun codeMatches(rom: (Long, Int) -> ByteArray?): Boolean = signatures.all { (off, hex) ->
        val want = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        rom(off, want.size)?.contentEquals(want) == true
    }

    /** Opens the game's PC: USED = started (or queued: [taken] says), NOT_NOW where the player isn't free. */
    abstract fun openPc(g: GameCalls): ItemUseResult

    abstract fun flyTargets(g: GameCalls): FlyTargets

    /** Flies to [mapsec], one of [targets]' destinations: OK = the game has it (or has it queued: [taken]). */
    abstract fun fly(mapsec: Int, targets: FlyTargets, g: GameCalls): FlyResult

    /** A few frames after [openPc] / [fly]: whether the game took it; if not, it's withdrawn. */
    open fun taken(g: GameCalls): Boolean = true

    /** Every frame while WILD ENCOUNTERS is OFF. */
    abstract fun holdWild(g: GameCalls)

    /** Once WILD ENCOUNTERS is back ON, on the field: false = not now, try again. */
    open fun releaseWild(g: GameCalls): Boolean = true

    protected fun u8(g: GameCalls, addr: Long) = g.read(addr, 1)[0].toInt() and 0xFF
    protected fun put(g: GameCalls, addr: Long, vararg v: Int) = g.write(addr, ByteArray(v.size) { v[it].toByte() })

    companion object {
        /** Frames [taken] waits: Yellow's loop and Crystal's PlayerEvents take theirs within one step. */
        const val TAKE_FRAMES = 24

        /** The running game's, or null (it's checked against the ROM by the caller). */
        fun forGame(game: GameKind?): GbField? = when {
            game == GameKind.YELLOW -> when (romLanguage) {
                'E' -> GB_FIELD_YELLOW
                'D' -> GB_FIELD_YELLOW_DE
                'F' -> GB_FIELD_YELLOW_FR
                'I' -> GB_FIELD_YELLOW_IT
                'S' -> GB_FIELD_YELLOW_ES
                else -> null
            }
            game == GameKind.RED || game == GameKind.BLUE -> GB_FIELD_RED_BLUE
            game == GameKind.PURE_RED || game == GameKind.PURE_BLUE -> GB_FIELD_PURE_RGB
            game == GameKind.YELLOW_LEGACY -> GB_FIELD_YELLOW_LEGACY
            game == GameKind.CRYSTAL && activeGen2 === GEN2_CRYSTAL -> Crystal
            game == GameKind.CRYSTAL && activeGen2 === GEN2_CRYSTAL_DE -> CrystalDe
            game == GameKind.CRYSTAL_LEGACY -> CrystalLegacy
            else -> null
        }
    }

    /**
     * Gen 1: Yellow in all five languages and Red / Blue - pret/pokeyellow's and pret/pokered's symbols (their builds are the
     * English carts; the European Yellows' addresses mapped by scripts/port_gen1.py, their RAM English's + 5 from wWalkCounter
     * up; Red / Blue's RAM is Yellow's + 1). One instance per cart ([GB_FIELD_YELLOW] ...), [signatures] its own bytes.
     */
    class Gen1(
        /** OverworldLoopLessDelay, past its `call DelayFrame`. */
        private val loopAfterWait: Int,
        private val bankswitch: Int,
        /** OpenPokemonCenterPC + 6 (bank 0x18): EnableAutoTextBoxDrawing .. PrintPredefTextID of the PC's text. */
        private val pcRoutine: Int,
        private val walkCounter: Long,
        /** wStatusFlags5: bit 7 scripted movement, bit 5 joypad off. */
        private val flags5: Long,
        private val inBattle: Long,
        /** wObtainedBadges: bit 2, the Thunder Badge. */
        private val badges: Long,
        /** wCurMapTileset: OVERWORLD 0 / PLATEAU 23 (CheckIfInOutsideMap). */
        private val tileset: Long,
        /** wTownVisitedFlag: a bit per town map id 0..10. */
        private val townsVisited: Long,
        private val destination: Long,
        /** wStatusFlags6 (bit 2 a fly / dungeon warp under way, bit 3 BIT_FLY_WARP), then wStatusFlags7 (bit 7 BIT_USED_FLY). */
        private val flags6: Long,
        /** Yellow's Func_1510: Pikachu hidden for the flight (-1: Red / Blue have none). */
        private val pikachuFlags: Long,
        private val pikachuImage: Long,
        private val partyCount: Long,
        private val partyMoves: Long,
        private val curMap: Long,
        private val grassRate: Long,
        private val waterRate: Long,
        /** WildDataPointers, in bank [wildBank] with the data. */
        private val wildDataPointers: Int,
        override val signatures: List<Pair<Long, String>>,
        /** OpenPokemonCenterPC's bank (0x18; PureRGB's 0x33) and the wild data's (3; PureRGB's 0x2C). */
        private val pcBank: Int = 0x18,
        private val wildBank: Int = 0x03,
    ) : GbField() {
        private val flags7 = flags6 + 1

        // wFontLoaded (right before wWalkCounter in every cart) bit 0: a text box is up. The games without an EXPAND
        // scene (the European Yellows, Red / Blue) have only these to go by.
        private fun free(g: GameCalls) = u8(g, walkCounter) == 0 && u8(g, walkCounter - 1) and 1 == 0 && u8(g, JOY_IGNORE) == 0 &&
            u8(g, flags5) and 0xA0 == 0 && u8(g, inBattle) == 0 && u8(g, flags6) and 0x1C == 0

        override fun openPc(g: GameCalls): ItemUseResult {
            if (!free(g)) return ItemUseResult.NOT_NOW
            return if (g.gbCallAt(loopAfterWait, bankswitch, 0, pcBank, pcRoutine)) ItemUseResult.USED else ItemUseResult.NOT_NOW
        }

        private fun flyer(g: GameCalls): Int {
            val n = u8(g, partyCount).coerceAtMost(6)
            return (0 until n).firstOrNull { i ->
                g.read(partyMoves + i * PARTY_MON_SIZE, 4).any { (it.toInt() and 0xFF) == FLY_MOVE_ID }
            } ?: -1
        }

        override fun flyTargets(g: GameCalls): FlyTargets {
            if (!free(g)) return FlyTargets(FlyResult.NOT_NOW)
            if (u8(g, badges) and 0x04 == 0) return FlyTargets(FlyResult.NO_BADGE)
            if (u8(g, tileset) !in setOf(0, 23)) return FlyTargets(FlyResult.CANT_FLY_HERE)
            val slot = flyer(g).takeIf { it >= 0 } ?: return FlyTargets(FlyResult.NO_FLYER)
            val v = g.read(townsVisited, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
            // BuildFlyLocationsList's order: the town map ids, visited ones only.
            return FlyTargets(FlyResult.OK, (0 until NUM_CITY_MAPS).filter { v shr it and 1 != 0 }, slot)
        }

        private var pikachuBefore: Pair<Int, Int>? = null

        override fun fly(mapsec: Int, targets: FlyTargets, g: GameCalls): FlyResult {
            val now = flyTargets(g)
            if (now.result != FlyResult.OK) return now.result
            if (mapsec !in now.destinations) return FlyResult.FAILED
            put(g, destination, mapsec)
            if (pikachuFlags >= 0) {
                pikachuBefore = u8(g, pikachuFlags) to u8(g, pikachuImage)
                put(g, pikachuFlags, pikachuBefore!!.first or 0x80)
                put(g, pikachuImage, 0xFF)
            }
            put(g, flags7, u8(g, flags7) or 0x80)
            put(g, flags6, u8(g, flags6) or 0x08)
            return FlyResult.OK
        }

        override fun taken(g: GameCalls): Boolean {
            val f6 = u8(g, flags6)
            // HandleFlyWarpOrDungeonWarp sets bit 2 on its way; bit 3 still alone = the loop never came by.
            if (f6 and 0x08 == 0 || f6 and 0x04 != 0) return true
            put(g, flags6, f6 and 0x08.inv())
            put(g, flags7, u8(g, flags7) and 0x80.inv())
            if (pikachuFlags >= 0) pikachuBefore?.let { (flags, image) -> put(g, pikachuFlags, flags); put(g, pikachuImage, image) }
            return false
        }

        // wGrassRate shares its bytes with a link battle's enemy name (and the old man's catch copies the player's
        // name over it): only out of battle.
        override fun holdWild(g: GameCalls) {
            if (u8(g, inBattle) != 0) return
            if (u8(g, grassRate) != 0) put(g, grassRate, 0)
            if (u8(g, waterRate) != 0) put(g, waterRate, 0)
        }

        /** LoadWildData's rates for the current map, unless the game has some there already. */
        override fun releaseWild(g: GameCalls): Boolean {
            if (u8(g, inBattle) != 0 || !free(g)) return false
            if (u8(g, grassRate) != 0 || u8(g, waterRate) != 0) return true
            val map = u8(g, curMap)
            val p = g.rom(wildBank * 0x4000L + (wildDataPointers - 0x4000) + 2 * map, 2) ?: return false
            val ptr = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)
            if (ptr !in 0x4000..0x7FFF) return true
            val data = g.rom(wildBank * 0x4000L + (ptr - 0x4000), 22) ?: return false
            val grass = data[0].toInt() and 0xFF
            val water = data[if (grass != 0) 21 else 1].toInt() and 0xFF
            if (grass != 0) put(g, grassRate, grass)
            if (water != 0) put(g, waterRate, water)
            return true
        }

        private companion object {
            const val JOY_IGNORE = 0xCD6BL     // the same in every Gen 1 cart
            const val PARTY_MON_SIZE = 44
            const val NUM_CITY_MAPS = 11
        }
    }

    /** Crystal's engine: the addresses a game keeps them at. */
    open class CrystalEngine(
        private val cfg: Gen2Config,
        /** wMapReentryScriptQueueFlag, then Bank, Address (lo, hi). */
        private val QUEUE: Long,
        /** PCScript (the standard script every PC runs). */
        private val PC_SCRIPT: Int,
        /** FlyFunction.FlyScript. */
        private val FLY_SCRIPT: Int,
        private val SCRIPT_RUNNING: Long,
        /** MAPEVENTS_ON 0. */
        private val MAP_EVENT_STATUS: Long,
        private val VISITED_SPAWNS: Long,
        /** bit 5: ENGINE_STORMBADGE. */
        private val JOHTO_BADGES: Long,
        /** TOWN 1, ROUTE 2: CheckOutdoorMap. */
        private val ENVIRONMENT: Long,
        private val PARTY_MON1_MOVES: Long,
        /** wWildEncounterCooldown. */
        private val COOLDOWN: Long,
        private val FLYPOINTS: Long,
        override val signatures: List<Pair<Long, String>>,
    ) : GbField() {
        private val BATTLE_MODE = cfg.battleMode
        private val DEFAULT_SPAWN = wramx(1, 0xD001)
        private val PARTY_COUNT = cfg.partyCount          // then wPartySpecies
        private val PC_SCRIPT_BANK = 0x2F
        private val FLY_SCRIPT_BANK = 0x03
        private val PARTY_MON_SIZE = 0x30
        private val EGG = 0xFD
        private val KANTO_LANDMARK = 47
        private val KANTO_FLYPOINT = 12
        private val SPAWN_INDIGO = 13

        private fun free(g: GameCalls) = u8(g, SCRIPT_RUNNING) == 0 && u8(g, MAP_EVENT_STATUS) == 0 &&
            u8(g, BATTLE_MODE) == 0 && u8(g, QUEUE) == 0

        private fun queue(g: GameCalls, bank: Int, addr: Int) {
            put(g, QUEUE + 1, bank, addr and 0xFF, addr shr 8)
            put(g, QUEUE, 1)
        }

        override fun openPc(g: GameCalls): ItemUseResult {
            if (!free(g)) return ItemUseResult.NOT_NOW
            queue(g, PC_SCRIPT_BANK, PC_SCRIPT)
            return ItemUseResult.USED
        }

        override fun taken(g: GameCalls): Boolean {
            if (u8(g, QUEUE) == 0) return true
            put(g, QUEUE, 0)
            return false
        }

        private fun flyer(g: GameCalls): Int {
            val n = u8(g, PARTY_COUNT).coerceAtMost(6)
            val species = g.read(PARTY_COUNT + 1, 6)
            return (0 until n).firstOrNull { i ->
                (species[i].toInt() and 0xFF) != EGG &&
                    g.read(PARTY_MON1_MOVES + i * PARTY_MON_SIZE, 4).any { (it.toInt() and 0xFF) == FLY_MOVE_ID }
            } ?: -1
        }

        private fun visited(g: GameCalls, spawn: Int) = u8(g, VISITED_SPAWNS + spawn / 8) shr (spawn % 8) and 1 != 0

        /** The Fly map's points as (landmark, spawn), the ROM's own Flypoints table: Johto's, then Kanto's. */
        private fun flypoints(g: GameCalls): List<Pair<Int, Int>> {
            val raw = g.rom(FLYPOINTS, 64) ?: return emptyList()
            return (0 until 32).map { (raw[2 * it].toInt() and 0xFF) to (raw[2 * it + 1].toInt() and 0xFF) }.takeWhile { it.first != 0xFF }
        }

        /** FlyMap's choice of map: Kanto's points from a Kanto landmark once Indigo Plateau is visited, else Johto's. */
        private fun shownPoints(g: GameCalls): List<Pair<Int, Int>> {
            val map = g.read(cfg.mapGroup, 2)
            val backup = g.read(cfg.backupMapGroup, 2)
            val landmark = gen2Landmark(cfg, map[0].toInt() and 0xFF, map[1].toInt() and 0xFF, backup[0].toInt() and 0xFF, backup[1].toInt() and 0xFF)
            val points = flypoints(g)
            return if (landmark >= KANTO_LANDMARK && visited(g, SPAWN_INDIGO)) points.drop(KANTO_FLYPOINT) else points.take(KANTO_FLYPOINT)
        }

        override fun flyTargets(g: GameCalls): FlyTargets {
            if (!free(g)) return FlyTargets(FlyResult.NOT_NOW)
            if (u8(g, JOHTO_BADGES) and 0x20 == 0) return FlyTargets(FlyResult.NO_BADGE)
            if (u8(g, ENVIRONMENT) !in 1..2) return FlyTargets(FlyResult.CANT_FLY_HERE)
            val slot = flyer(g).takeIf { it >= 0 } ?: return FlyTargets(FlyResult.NO_FLYER)
            return FlyTargets(FlyResult.OK, shownPoints(g).filter { visited(g, it.second) }.map { it.first }, slot)
        }

        override fun fly(mapsec: Int, targets: FlyTargets, g: GameCalls): FlyResult {
            val now = flyTargets(g)
            if (now.result != FlyResult.OK) return now.result
            val spawn = shownPoints(g).firstOrNull { it.first == mapsec && visited(g, it.second) }?.second ?: return FlyResult.FAILED
            put(g, DEFAULT_SPAWN, spawn)
            queue(g, FLY_SCRIPT_BANK, FLY_SCRIPT)
            return FlyResult.OK
        }

        // A step meets nothing while the cooldown is still 2 or more when it's counted down.
        override fun holdWild(g: GameCalls) {
            if (u8(g, COOLDOWN) < 3) put(g, COOLDOWN, 5)
        }
    }

    /** Pokémon Crystal (rev 0, rev 1, Australia: one set of addresses): pret/pokecrystal's symbols. */
    object Crystal : CrystalEngine(
        cfg = GEN2_CRYSTAL,
        QUEUE = wramx(1, 0xD45C), PC_SCRIPT = 0x41A9, FLY_SCRIPT = 0x4AA3, SCRIPT_RUNNING = wramx(1, 0xD438),
        MAP_EVENT_STATUS = wramx(1, 0xD433), VISITED_SPAWNS = wramx(1, 0xDCA5), JOHTO_BADGES = wramx(1, 0xD857),
        ENVIRONMENT = wramx(1, 0xD19A), PARTY_MON1_MOVES = wramx(1, 0xDCE1), COOLDOWN = wramx(1, 0xD452),
        FLYPOINTS = 0x24 * 0x4000L + (0x5C5E - 0x4000),
        signatures = listOf(
            0x25 * 0x4000L + (0x7C30 - 0x4000) to "fa5cd4a7c8215ed42a666ffa5dd4cd1f", // RunMemScript
            0x2F * 0x4000L + (0x41A9 - 0x4000) to "470f1c00499185",                  // opentext, special, closetext, end
            0x03 * 0x4000L + (0x4AA3 - 0x4000) to "7c0e0016300f3500",                // the Fly script's first commands
            0x25 * 0x4000L + (0x68C7 - 0x4000) to "2152d47ea7c835c837c9",           // CheckWildEncounterCooldown
            0x24 * 0x4000L + (0x5C5E - 0x4000) to "010e030f",                         // Flypoints: NEW BARK, CHERRYGROVE
        ),
    )

    /** German Crystal (Kristall): English Crystal's RAM and scripts, its Flypoints table 0x1E later. */
    object CrystalDe : CrystalEngine(
        cfg = GEN2_CRYSTAL_DE,
        QUEUE = wramx(1, 0xD45C), PC_SCRIPT = 0x41A9, FLY_SCRIPT = 0x4AA3, SCRIPT_RUNNING = wramx(1, 0xD438),
        MAP_EVENT_STATUS = wramx(1, 0xD433), VISITED_SPAWNS = wramx(1, 0xDCA5), JOHTO_BADGES = wramx(1, 0xD857),
        ENVIRONMENT = wramx(1, 0xD19A), PARTY_MON1_MOVES = wramx(1, 0xDCE1), COOLDOWN = wramx(1, 0xD452),
        FLYPOINTS = 0x24 * 0x4000L + (0x5C7C - 0x4000),
        signatures = listOf(
            0x25 * 0x4000L + (0x7C31 - 0x4000) to "fa5cd4a7c8215ed42a666ffa5dd4cd09", // RunMemScript
            0x2F * 0x4000L + (0x41A9 - 0x4000) to "470f1c00499185",                  // opentext, special, closetext, end
            0x03 * 0x4000L + (0x4AA3 - 0x4000) to "7c0e0000300f3500",                // the Fly script's first commands
            0x25 * 0x4000L + (0x68C7 - 0x4000) to "2152d47ea7c835c837c9",           // CheckWildEncounterCooldown
            0x24 * 0x4000L + (0x5C7C - 0x4000) to "010e030f",                         // Flypoints: NEUBORKIA, ROSALIA CITY
        ),
    )

    /** Crystal Legacy v1.3.1 (its pokecrystal.sym): Crystal's scripts and rules at its own addresses. */
    object CrystalLegacy : CrystalEngine(
        cfg = GEN2_CRYSTAL_LEGACY,
        QUEUE = wramx(1, 0xD467), PC_SCRIPT = 0x41E3, FLY_SCRIPT = 0x4AEC, SCRIPT_RUNNING = wramx(1, 0xD443),
        MAP_EVENT_STATUS = wramx(1, 0xD43E), VISITED_SPAWNS = wramx(1, 0xDCB0), JOHTO_BADGES = wramx(1, 0xD862),
        ENVIRONMENT = wramx(1, 0xD1A5), PARTY_MON1_MOVES = wramx(1, 0xDCEC), COOLDOWN = wramx(1, 0xD45D),
        FLYPOINTS = 0x24 * 0x4000L + (0x5D18 - 0x4000),
        signatures = listOf(
            0x25 * 0x4000L + (0x7C8F - 0x4000) to "fa67d4a7c82169d42a666ffa68d4cde8", // RunMemScript
            0x2F * 0x4000L + (0x41E3 - 0x4000) to "470f1c00499185",                  // opentext, special, closetext, end
            0x03 * 0x4000L + (0x4AEC - 0x4000) to "7c0e00df2f0f3700",                // the Fly script's first commands
            0x25 * 0x4000L + (0x68D9 - 0x4000) to "215dd47ea7c835c837c9",           // CheckWildEncounterCooldown
            0x24 * 0x4000L + (0x5D18 - 0x4000) to "010e030f",                         // Flypoints: NEW BARK, CHERRYGROVE
        ),
    )
}

/** MOVE_FLY in Gen 1 and 2 (19, as in Gen 3). */
private const val FLY_MOVE_ID = 19

/** Each Gen 1 cart's addresses and code (generated from the carts: pret/pokeyellow / pokered, port_gen1.py). */
val GB_FIELD_YELLOW = GbField.Gen1(
    loopAfterWait = 0x0248, bankswitch = 0x3E84, pcRoutine = 0x66F4,
    walkCounter = 0xCFC4L, flags5 = 0xD72FL, inBattle = 0xD056L, badges = 0xD355L,
    tileset = 0xD366L, townsVisited = 0xD70AL, destination = 0xD719L, flags6 = 0xD731L, pikachuFlags = 0xD42FL, pikachuImage = 0xC1F2L,
    partyCount = 0xD162L, partyMoves = 0xD172L, curMap = 0xD35DL, grassRate = 0xD886L,
    waterRate = 0xD8A3L, wildDataPointers = 0x4B95,
    signatures = listOf(
        0x245L to "cd641ecd2a34cd6f1ecde10ffac4cfa7c2c303cd",
        0x3E84L to "f0b8f578e0b8ea0020cd983e",
        0x3F3AL to "e08c21673fcd543f2111cfcb",
        0x626EEL to "fa09c1fe04c0cd293c3e01ea0ccf3e21c33a3ff9",
        0x710B4L to "7eea19d72131d7cbde23cbfe",
        0x710FBL to "213dcd36ff23fa0ad75ffa0bd75701",
        0xCB62L to "21954bfa5dd34f060009092a666f2aea86d8",
        0x1510L to "e5212fd4cbfe21f2c136ffe1c9",
    ),
)
val GB_FIELD_YELLOW_DE = GbField.Gen1(
    loopAfterWait = 0x0248, bankswitch = 0x3E89, pcRoutine = 0x66F4,
    walkCounter = 0xCFC9L, flags5 = 0xD734L, inBattle = 0xD05BL, badges = 0xD35AL,
    tileset = 0xD36BL, townsVisited = 0xD70FL, destination = 0xD71EL, flags6 = 0xD736L, pikachuFlags = 0xD434L, pikachuImage = 0xC1F2L,
    partyCount = 0xD167L, partyMoves = 0xD177L, curMap = 0xD362L, grassRate = 0xD88BL,
    waterRate = 0xD8A8L, wildDataPointers = 0x4B95,
    signatures = listOf(
        0x245L to "cd691ecd2f34cd741ecde10ffac9cfa7c2c303cd",
        0x3E89L to "f0b8f578e0b8ea0020cd9d3e",
        0x3F3FL to "e08c216c3fcd593f2116cfcb",
        0x626EEL to "fa09c1fe04c0cd2e3c3e01ea11cf3e21c33f3ff9",
        0x710B2L to "7eea1ed72136d7cbde23cbfe",
        0x710F9L to "213dcd36ff23fa0fd75ffa10d75701",
        0xCB62L to "21954bfa62d34f060009092a666f2aea8bd8",
        0x1510L to "e52134d4cbfe21f2c136ffe1c9",
    ),
)
val GB_FIELD_YELLOW_FR = GbField.Gen1(
    loopAfterWait = 0x0248, bankswitch = 0x3E87, pcRoutine = 0x66F4,
    walkCounter = 0xCFC9L, flags5 = 0xD734L, inBattle = 0xD05BL, badges = 0xD35AL,
    tileset = 0xD36BL, townsVisited = 0xD70FL, destination = 0xD71EL, flags6 = 0xD736L, pikachuFlags = 0xD434L, pikachuImage = 0xC1F2L,
    partyCount = 0xD167L, partyMoves = 0xD177L, curMap = 0xD362L, grassRate = 0xD88BL,
    waterRate = 0xD8A8L, wildDataPointers = 0x4B95,
    signatures = listOf(
        0x245L to "cd601ecd2d34cd6b1ecde10ffac9cfa7c2c303cd",
        0x3E87L to "f0b8f578e0b8ea0020cd9b3e",
        0x3F3DL to "e08c216a3fcd573f2116cfcb",
        0x626EEL to "fa09c1fe04c0cd2c3c3e01ea11cf3e21c33d3ff9",
        0x710B7L to "7eea1ed72136d7cbde23cbfe",
        0x710FEL to "213dcd36ff23fa0fd75ffa10d75701",
        0xCB62L to "21954bfa62d34f060009092a666f2aea8bd8",
        0x150DL to "e52134d4cbfe21f2c136ffe1c9",
    ),
)
val GB_FIELD_YELLOW_IT = GbField.Gen1(
    loopAfterWait = 0x0248, bankswitch = 0x3E82, pcRoutine = 0x66F4,
    walkCounter = 0xCFC9L, flags5 = 0xD734L, inBattle = 0xD05BL, badges = 0xD35AL,
    tileset = 0xD36BL, townsVisited = 0xD70FL, destination = 0xD71EL, flags6 = 0xD736L, pikachuFlags = 0xD434L, pikachuImage = 0xC1F2L,
    partyCount = 0xD167L, partyMoves = 0xD177L, curMap = 0xD362L, grassRate = 0xD88BL,
    waterRate = 0xD8A8L, wildDataPointers = 0x4B95,
    signatures = listOf(
        0x245L to "cd641ecd2834cd6f1ecde10ffac9cfa7c2c303cd",
        0x3E82L to "f0b8f578e0b8ea0020cd963e",
        0x3F38L to "e08c21653fcd523f2116cfcb",
        0x626EEL to "fa09c1fe04c0cd273c3e01ea11cf3e21c3383ff9",
        0x710B5L to "7eea1ed72136d7cbde23cbfe",
        0x710FCL to "213dcd36ff23fa0fd75ffa10d75701",
        0xCB62L to "21954bfa62d34f060009092a666f2aea8bd8",
        0x1510L to "e52134d4cbfe21f2c136ffe1c9",
    ),
)
val GB_FIELD_YELLOW_ES = GbField.Gen1(
    loopAfterWait = 0x0248, bankswitch = 0x3E89, pcRoutine = 0x66F4,
    walkCounter = 0xCFC9L, flags5 = 0xD734L, inBattle = 0xD05BL, badges = 0xD35AL,
    tileset = 0xD36BL, townsVisited = 0xD70FL, destination = 0xD71EL, flags6 = 0xD736L, pikachuFlags = 0xD434L, pikachuImage = 0xC1F2L,
    partyCount = 0xD167L, partyMoves = 0xD177L, curMap = 0xD362L, grassRate = 0xD88BL,
    waterRate = 0xD8A8L, wildDataPointers = 0x4B95,
    signatures = listOf(
        0x245L to "cd631ecd2f34cd6e1ecde10ffac9cfa7c2c303cd",
        0x3E89L to "f0b8f578e0b8ea0020cd9d3e",
        0x3F3FL to "e08c216c3fcd593f2116cfcb",
        0x626EEL to "fa09c1fe04c0cd2e3c3e01ea11cf3e21c33f3ff9",
        0x710B8L to "7eea1ed72136d7cbde23cbfe",
        0x710FFL to "213dcd36ff23fa0fd75ffa10d75701",
        0xCB62L to "21954bfa62d34f060009092a666f2aea8bd8",
        0x1510L to "e52134d4cbfe21f2c136ffe1c9",
    ),
)
/** Yellow Legacy v1.0.10 (its pokeyellow.sym): Yellow's ways in at its own addresses. */
val GB_FIELD_YELLOW_LEGACY = GbField.Gen1(
    loopAfterWait = 0x0242, bankswitch = 0x3E87, pcRoutine = 0x6733,
    walkCounter = 0xCFC4L, flags5 = 0xD72FL, inBattle = 0xD057L, badges = 0xD3A3L,
    tileset = 0xD3B4L, townsVisited = 0xD70AL, destination = 0xD719L, flags6 = 0xD731L, pikachuFlags = 0xD47DL, pikachuImage = 0xC1F2L,
    partyCount = 0xD162L, partyMoves = 0xD172L, curMap = 0xD3ABL, grassRate = 0xD886L,
    waterRate = 0xD8A3L, wildDataPointers = 0x4C35,
    signatures = listOf(
        0x23FL to "cd051ecd1534cd101ecd0810fac4cfa7c2c303cd",
        0x3E87L to "f0b8f578e0b8ea0020cd9b3e",
        0x3F39L to "e08c21663fcd533f2111cfcb",
        0x6272DL to "fa09c1fe04c0cd2e3c3e01ea0ccf3e21c3393ff9",
        0x71154L to "7eea19d72131d7cbde23cbfe",
        0x7119BL to "213dcd36ff23fa0ad75ffa0bd75701",
        0xCC02L to "21354cfaabd34f060009092a666f2aea86d8",
        0x151CL to "e5217dd4cbfe21f2c136ffe1c9",
    ),
)
val GB_FIELD_RED_BLUE = GbField.Gen1(
    loopAfterWait = 0x0405, bankswitch = 0x35D6, pcRoutine = 0x651C,
    walkCounter = 0xCFC5L, flags5 = 0xD730L, inBattle = 0xD057L, badges = 0xD356L,
    tileset = 0xD367L, townsVisited = 0xD70BL, destination = 0xD71AL, flags6 = 0xD732L, pikachuFlags = -1L, pikachuImage = -1L,
    partyCount = 0xD163L, partyMoves = 0xD173L, curMap = 0xD35EL, grassRate = 0xD887L,
    waterRate = 0xD8A4L, wildDataPointers = 0x4EEB,
    signatures = listOf(
        0x402L to "cdaf20cdba20fa36d7cb77c49e03fac5cfa7c2b5",
        0x35D6L to "f0b8f578e0b8ea002001e435",
        0x3EF5L to "e08c21223fcd0f3f2111cfcb",
        0x62516L to "fa09c1fe04c0cd3c3c3e01ea0ccf3e1fc3f53ef9",
        0x7102BL to "7eea1ad72132d7cbde23cbfe",
        0x71070L to "213dcd36ff23fa0bd75ffa0cd75701",
        0xCEB8L to "21eb4efa5ed34f060009092a666f2aea87d8",
    ),
)

/**
 * PureRed / PureBlue v2.7.6 (pureRGB's pokered.sym; both carts the same code at the same places): Red's ways in at its own
 * addresses. Its overworld loop waits for the frame at OverworldLoop's `rst DelayFrame` and goes on into
 * OverworldLoopLessDelay (no wait of its own there: GBC double speed), so the PC is called from 0x03D7; its PC routine is
 * in bank 0x33 (DisableAutoTextBoxDrawing, then the PC's predef text), its wild data in bank 0x2C, and its game state in
 * WRAM bank 1 (read and written by bank). Checked headless from the converted retail Red save: the PC's menus from
 * Vermilion's Pokemon Center, FLY's flags taken by the loop (landed in Saffron, then walked), and the game's own FLY menu
 * landing at the same spots.
 */
val GB_FIELD_PURE_RGB = GbField.Gen1(
    loopAfterWait = 0x03D7, bankswitch = 0x3297, pcRoutine = 0x46B5,
    walkCounter = 0xCFC5L, flags5 = wramx(1, 0xD738), inBattle = wramx(1, 0xD057), badges = wramx(1, 0xD35E),
    tileset = wramx(1, 0xD36F), townsVisited = wramx(1, 0xD713), destination = wramx(1, 0xD722), flags6 = wramx(1, 0xD73A),
    pikachuFlags = -1L, pikachuImage = -1L,
    partyCount = wramx(1, 0xD16B), partyMoves = wramx(1, 0xD17B), curMap = wramx(1, 0xD366), grassRate = wramx(1, 0xD88F),
    waterRate = wramx(1, 0xD8AC), wildDataPointers = 0x484A,
    signatures = listOf(
        0x3D6L to "d721bd680635c7cdb01efa3ed7cb7728",      // OverworldLoop: rst DelayFrame, callfar GBCSetCPU2xSpeed, ...
        0x3297L to "f0b8f578cda332cda932c178",               // Bankswitch
        0xCC6AFL to "fa09c1fe04c0cd9b363e05c37129",           // OpenPokemonCenterPC
        0x70AE8L to "7eea22d7213ad7cbde23cbfe",               // the town map's FLY pick
        0x70B2DL to "213dcd3eff22fa13d75ffa14d757",           // BuildFlyLocationsList
        0xB076AL to "214a484f060009092a666f2aea8fd8",         // LoadWildDataCommon
    ),
    pcBank = 0x33,
    wildBank = 0x2C,
)
