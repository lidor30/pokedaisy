package com.pokedaisy.app.companion.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Whose sprite set a hero has: FireRed / LeafGreen's (same art in both), Emerald's, Ruby / Sapphire's (Emerald's
 * sheets, but the heroes drawn before Emerald's redraw and walking and running in one sheet) or Heart and Soul's (Emerald's
 * sheets frame for frame; its heroes are only worn by the other games, [PlayerLook.engineFor] never gives it). */
enum class LookEngine { FIRERED, EMERALD, RUBY, HEART_AND_SOUL }

/**
 * A hero PLAYER LOOK can dress the player as. [frontTag] / [backTag] are its trainer picture ids (the tag the game's
 * front / back palette tables give its entries: FireRed's TRAINER_PIC_RED 0x87, TRAINER_BACK_PIC_RED 0, Emerald's
 * TRAINER_PIC_BRENDAN 0x47, TRAINER_BACK_PIC_BRENDAN 0); both games give the boy's overworld palette tag 0x1100 and
 * the girl's 0x1110, reflections one more (Ruby / Sapphire: one flat reflection palette, not the hero's). [label] is the
 * hero's name as the games write it, [game] where they come from (the picker's tiles).
 */
enum class LookChar(val engine: LookEngine, val female: Boolean, val frontTag: Int, val backTag: Int, val label: String, val game: String) {
    RED(LookEngine.FIRERED, false, 0x87, 0, "RED", "FIRERED"),
    LEAF(LookEngine.FIRERED, true, 0x88, 1, "LEAF", "LEAFGREEN"),
    BRENDAN(LookEngine.EMERALD, false, 0x47, 0, "BRENDAN", "EMERALD"),
    MAY(LookEngine.EMERALD, true, 0x48, 1, "MAY", "EMERALD"),
    RS_BRENDAN(LookEngine.RUBY, false, 0, 0, "BRENDAN", "RUBY"),
    RS_MAY(LookEngine.RUBY, true, 1, 1, "MAY", "SAPPHIRE"),
    // Worn only: no game of theirs is dressed up, so their tags are never looked up.
    GOLD(LookEngine.HEART_AND_SOUL, false, 0, 0, "GOLD", "HNS"),
    KRIS(LookEngine.HEART_AND_SOUL, true, 0, 0, "KRIS", "HNS");

    val owTag get() = if (female) 0x1110 else 0x1100

    /** The game's own region map head for the companion's MAP (RomArt's `regionmap/player_<name>.png`). */
    val headArt get() = "player_${name.lowercase()}"

    /** The same game's hero of the other gender: what the rival wears while the player wears this one (RIVAL LOOK). */
    val counterpart get() = entries.first { it.engine == engine && it.female != female }

    companion object {
        fun of(engine: LookEngine, female: Boolean) = entries.first { it.engine == engine && it.female == female }
        fun byName(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/**
 * One of a hero's graphics. Sprite sheets carry their frame size in tiles ([w] x [h], frames one after another, each
 * in reading order: the decomps' `-mwidth` / `-mheight`); the rest are palettes and pictures.
 */
enum class LookPart(val w: Int = 0, val h: Int = 0) {
    // FireRed / LeafGreen's overworld sheets.
    FR_NORMAL(2, 4), FR_SURF_RUN(2, 4), FR_BIKE(4, 4), FR_ITEM(2, 4), FR_FISH(4, 4), FR_VS_SEEKER_BIKE(4, 4),
    // Emerald's.
    EM_WALKING(2, 4), EM_RUNNING(2, 4), EM_MACH_BIKE(4, 4), EM_ACRO_BIKE(4, 4), EM_SURFING(4, 4), EM_FIELD_MOVE(4, 4),
    EM_FISHING(4, 4), EM_UNDERWATER(4, 4), EM_WATERING(4, 4), EM_DECORATING(2, 4),
    // Ruby / Sapphire's walking and running sheet (Emerald's two, one after the other); its others are Emerald's parts.
    RS_NORMAL(2, 4),
    OW_PAL, OW_REFLECTION_PAL,
    /** Emerald's (and Ruby / Sapphire's) Dive palette, Brendan's and May's both: only read (the dressed-up hero's frames
     * are recoloured to it). */
    UNDERWATER_PAL,
    /** The battle back picture: 64x64 frames, the throw. */
    BACK(8, 8),
    BACK_PAL, FRONT, FRONT_PAL, MAP_HEAD, MAP_HEAD_PAL;

    val frameBytes get() = w * h * 32
}

/**
 * PLAYER LOOK (SETTINGS > GAMEPLAY): play one game as another game's hero - Red in Emerald, May in FireRed - with the
 * sprites taken from the player's own ROM of that other game; nothing is bundled.
 *
 * Any FireRed / LeafGreen / Emerald ROM a scan sees ([scan]: every [LookBlob] by fingerprint, so every language and
 * revision) leaves its heroes' graphics, decoded, in `rom-art/look/<hero>/` ([LookArt]); a hero is offered once all
 * of them are there. Dressing up is a ROM patch in memory ([build] -> [Patch], put in by
 * [com.pokedaisy.app.MgbaCore.pkRomBlocks]: the file is never written, the Poller and RetroAchievements still read the
 * file's bytes): the player's own sheets are written over in place frame by frame (the two games' frames matched up by
 * their animations, e.g. FireRed's run frames into Emerald's running sheet, a 16x32 frame centred in a 32x32 one), the
 * overworld palettes in place, and the compressed pictures (front picture, trainer palettes, FireRed's map head) go in
 * the ROM's empty tail with the game's pointers to them moved there. Only the player's own gender is dressed up, so a
 * rival of the other gender keeps their look. Nothing of the save changes: it keeps only a graphics id and the gender.
 */
object PlayerLook {
    /** The hero the running game's player wears now (null = their own; Compose state): the companion's MAP head and
     * CARD follow it, as the game's own do. */
    val worn = androidx.compose.runtime.mutableStateOf<LookChar?>(null)

    /** A ROM's [LookBlob]s, where each copy of each sits ([hits], raw ROM offsets), the 4-byte-aligned words pointing
     * at them ([refs]: where, and the two halfwords after - a palette table's tag, a sprite sheet's size), and where
     * the ROM's empty tail starts ([free]: past the last byte that isn't 0xFF). */
    class Scan(val rom: ByteArray, val hits: Map<LookFingerprint, List<Int>>, val refs: Map<Int, List<Ref>>, val free: Int) {
        fun hitsOf(char: LookChar, part: LookPart): List<Int> = blob(char, part)?.let { hits[it] }.orEmpty()

        /** The decoded graphic (its first copy), or null if the ROM doesn't have it. */
        fun data(char: LookChar, part: LookPart): ByteArray? {
            val b = blob(char, part) ?: return null
            val off = hits[b]?.firstOrNull() ?: return null
            return decode(rom, off, b)
        }

        /** Heroes all of whose graphics are in this ROM. */
        val chars: Set<LookChar> get() = LookChar.entries.filter { c -> LookBlob.entries.filter { it.char == c }.all { !hits[it].isNullOrEmpty() } }.toSet()
    }

    class Ref(val at: Int, val half0: Int, val half1: Int)

    /** The hacks that wear another look besides retail carts: each one's hero graphics and the tables pointing at them
     * are retail's, and each was played dressed up headless (docs/DEVELOPMENT.md's PLAYER LOOK). Another hack may load
     * its hero's pictures or palettes its own way, so none is offered until it's been checked (Super Mariomon kept May,
     * but the only save to play it with is Mario's). */
    val HACKS: Set<String> = setOf(
        TelemetrySampler.EMERALD_KAIZO_V2_0_SHA1,
        TelemetrySampler.EMERALD_LEGACY_V1_1_4_SHA1,
        TelemetrySampler.IMPERIUM_V1_3_1_SHA1,
        TelemetrySampler.ULTRA_VIOLET_V1_22_SHA1,
    )

    /** One block of the patch: [data] over the ROM from [off]; [crc] is the CRC32 of the file's bytes there. */
    class Block(val off: Int, val crc: Int, val data: ByteArray)

    /** A palette already loaded to swap (pk_cheats_repalette): any of [from] becomes [to]. */
    class Pal(val from: List<ByteArray>, val to: ByteArray)

    /** What dressing up takes: [blocks] into the ROM, then [palettes] swapped where the game has loaded them. */
    class Patch(
        val blocks: List<Block>, val palettes: List<Pal>,
        /** The overworld palette the player's sprite wears with this patch: put over whatever its slot holds now when
         * the look changes on the field ([com.pokedaisy.app.MgbaCore.pkLookRefresh]) - a palette swap by content can't
         * tell a look's from another's (Emerald's May's is also the rival's), nor know a Pokémon's. */
        val playerPal: ByteArray? = null,
        /** RIDE ON's vehicle, drawn behind the rider these blocks leave in the bike sheets (pk_wide.c's ride); null = none. */
        val ride: RideOn.Sprite? = null,
    ) {
        /** mgba_dump's `romblocks` file (headless checks of exactly what the app puts in). */
        fun toText(): String = buildString {
            for (b in blocks) append("block %08X %08X %s\n".format(0x08000000 + b.off, b.crc, hex(b.data)))
            for (p in palettes) append("pal ${p.from.joinToString("") { hex(it) }} ${hex(p.to)}\n")
            playerPal?.let { append("playerpal ${hex(it)}\n") }
        }

        /** The same bytes as [other] (the runner hands the engine its look again whenever the party read changes). */
        fun sameAs(other: Patch?): Boolean = other != null && blocks.size == other.blocks.size && palettes.size == other.palettes.size &&
            blocks.indices.all { i -> blocks[i].let { b -> other.blocks[i].let { o -> b.off == o.off && b.crc == o.crc && b.data.contentEquals(o.data) } } } &&
            palettes.indices.all { i -> palettes[i].to.contentEquals(other.palettes[i].to) && palettes[i].from.size == other.palettes[i].from.size &&
                palettes[i].from.indices.all { j -> palettes[i].from[j].contentEquals(other.palettes[i].from[j]) } } &&
            (playerPal == null && other.playerPal == null || playerPal != null && other.playerPal != null && playerPal.contentEquals(other.playerPal)) &&
            (ride == null && other.ride == null || ride != null && ride.sameAs(other.ride))
    }

    fun blob(char: LookChar, part: LookPart): LookBlob? = LookBlob.entries.firstOrNull { it.char == char && it.part == part }

    /** The hero the player is in a game of [engine] (FireRed / LeafGreen or Emerald) by the save's gender. */
    fun self(engine: LookEngine, female: Boolean) = LookChar.of(engine, female)

    /** Which sprite set a header's game code has: FireRed / LeafGreen (BPR / BPG), Emerald (BPE) or Ruby / Sapphire
     * (AXV / AXP); null otherwise. */
    fun engineFor(code: String?): LookEngine? = when (code?.take(3)) {
        "BPR", "BPG" -> LookEngine.FIRERED
        "BPE" -> LookEngine.EMERALD
        "AXV", "AXP" -> LookEngine.RUBY
        else -> null
    }

    // --- finding the graphics ---

    /** Every copy of every [LookBlob] in [rom] (one pass, RomArt's rolling window), the words pointing at them and the
     * empty tail. ~0.5 s for 16 MiB: off the UI thread. */
    fun scan(rom: ByteArray, extra: Collection<LookFingerprint> = emptyList()): Scan {
        val hits = Fingerprints.find(rom, LookBlob.entries + extra)
        val buf = ByteBuffer.wrap(rom).order(ByteOrder.LITTLE_ENDIAN)
        // The words pointing at a graphic: palette table entries ({ptr, tag}), picture tables ({ptr, size, tag}),
        // literal pools.
        val targets = LookBlob.entries.flatMap { hits[it].orEmpty() }.toSortedSet().toIntArray()
        val refs = HashMap<Int, MutableList<Ref>>()
        var o = 0
        while (o + 8 <= rom.size) {
            val w = buf.getInt(o)
            if (w ushr 25 == 4) {
                val off = w - 0x08000000
                if (java.util.Arrays.binarySearch(targets, off) >= 0) {
                    refs.getOrPut(off) { ArrayList() } += Ref(o, buf.getShort(o + 4).toInt() and 0xFFFF, buf.getShort(o + 6).toInt() and 0xFFFF)
                }
            }
            o += 4
        }
        var end = rom.size
        while (end > 0 && rom[end - 1] == 0xFF.toByte()) end--
        return Scan(rom, hits, refs, end)
    }

    private fun decode(rom: ByteArray, off: Int, blob: LookFingerprint): ByteArray? = Fingerprints.decode(rom, off, blob)

    // --- dressing up ---

    /**
     * The overworld palette and reflection palette [me]'s slot takes to wear [look] ([art]), and how [look]'s pixels map
     * onto it ([map], by colour index). Within one game a hero's own palette works as is (the game puts either hero in
     * that slot). Across games the slot is shared: the surf blob, jump shadows and warp arrows draw with it too
     * ([LOOK_SHARED_COLOURS]), so those colours stay and [look]'s go into the others - its most used first, exact
     * matches onto the kept ones, and with no slot left a colour joins the nearest one there is.
     */
    class Worn(val pal: ByteArray, val reflection: ByteArray, val map: IntArray)

    fun wear(me: LookChar, look: LookChar, art: Map<LookPart, ByteArray>, own: (LookPart) -> ByteArray?): Worn? {
        val srcPal = art[LookPart.OW_PAL] ?: return null
        val srcRefl = art[LookPart.OW_REFLECTION_PAL] ?: reflect(srcPal)
        if (me.engine == look.engine) return Worn(srcPal, srcRefl, IntArray(16) { it })
        // How often [look]'s sheets use each colour.
        val counts = IntArray(16)
        for (b in LookBlob.entries.filter { it.char == look && it.part.w > 0 && it.part != LookPart.BACK && it.part != LookPart.EM_UNDERWATER }) {
            art[b.part]?.forEach { v -> counts[v.toInt() and 15]++; counts[(v.toInt() shr 4) and 15]++ }
        }
        return wearWith(me, srcPal, srcRefl, counts, own)
    }

    /** [wear] for any picture: [srcPal] / [srcRefl] into [me]'s slot around its shared colours, [counts] how often the
     * picture uses each of its colours (the most used get a slot of their own first). */
    fun wearWith(me: LookChar, srcPal: ByteArray, srcRefl: ByteArray, counts: IntArray, own: (LookPart) -> ByteArray?): Worn? {
        val pal = own(LookPart.OW_PAL)?.copyOf() ?: return null
        // Ruby / Sapphire keep no reflection palette of the hero's (theirs is flat water): one is made to fill.
        val refl = (own(LookPart.OW_REFLECTION_PAL) ?: reflect(pal)).copyOf()
        val kept = LOOK_SHARED_COLOURS[me.engine].orEmpty()
        fun rgb(p: ByteArray, i: Int) = (p[i * 2].toInt() and 0xFF) or ((p[i * 2 + 1].toInt() and 0xFF) shl 8)
        fun dist(a: Int, b: Int): Int {
            val dr = (a and 31) - (b and 31); val dg = ((a shr 5) and 31) - ((b shr 5) and 31); val db = ((a shr 10) and 31) - ((b shr 10) and 31)
            return dr * dr * 3 + dg * dg * 4 + db * db * 2
        }
        val map = IntArray(16) { -1 }.also { it[0] = 0 }
        val used = (1..15).filter { counts[it] > 0 }.sortedByDescending { counts[it] }
        for (i in used) kept.firstOrNull { rgb(pal, it) == rgb(srcPal, i) }?.let { map[i] = it }
        val free = ArrayDeque((1..15).filter { it !in kept })
        val taken = kept.toMutableSet()
        for (i in used.filter { map[it] < 0 }) {
            val slot = free.removeFirstOrNull()
            if (slot != null) {
                pal[slot * 2] = srcPal[i * 2]; pal[slot * 2 + 1] = srcPal[i * 2 + 1]
                refl[slot * 2] = srcRefl[i * 2]; refl[slot * 2 + 1] = srcRefl[i * 2 + 1]
                map[i] = slot
                taken += slot
            } else {
                map[i] = taken.minBy { dist(rgb(pal, it), rgb(srcPal, i)) }
            }
        }
        for (i in 0..15) if (map[i] < 0) map[i] = 0
        return Worn(pal, refl, map)
    }

    /**
     * The patch dressing the player of the game [scan] was made from - [me], their own hero - as [look], whose graphics
     * are [art] (from [LookArt]). Null if there's nothing to do, the ROM lacks some of [me]'s graphics or its empty
     * tail is too small. [known] are every hero whose graphics are kept: a state made in another look may have loaded
     * their palettes, which [Patch.palettes] swap for the one now worn.
     */
    fun build(
        scan: Scan, me: LookChar, look: LookChar, art: Map<LookPart, ByteArray>,
        known: Map<LookChar, Map<LookPart, ByteArray>> = emptyMap(),
        /** RIVAL LOOK: the hero of the other gender (the rival, in Emerald and Ruby / Sapphire) wears [look]'s
         * counterpart too, from [known]. Where that can't be built the player is still dressed up. */
        rival: Boolean = false,
    ): Patch? {
        if (me == look || me !in scan.chars) return null
        val writes = ArrayList<Pair<Int, ByteArray>>()
        var free = (scan.free + 0x103) and 3.inv()
        val dressed = dress(scan, me, look, art, writes, free) ?: return null
        free = dressed.free
        var rivalWorn: Worn? = null
        if (rival && hasRival(me.engine)) {
            val other = me.counterpart
            val otherLook = look.counterpart
            val otherArt = known[otherLook]
            if (other != otherLook && otherArt != null && other in scan.chars) {
                val rivalWrites = ArrayList<Pair<Int, ByteArray>>()
                dress(scan, other, otherLook, otherArt, rivalWrites, free)?.let { writes += rivalWrites; rivalWorn = it.worn }
            }
        }
        return Patch(merge(scan.rom, writes), settleBoth(scan, me, dressed.worn, rivalWorn, known), dressed.worn.pal)
    }

    /** [settle] for the player's slot ([worn]) and, in a game with a rival ([hasRival]), the rival's ([rivalWorn]; null =
     * their own): RIVAL LOOK turned off puts theirs back too. A palette can't say whose slot it's in, so one the other
     * slot now takes isn't swapped - a same-game swap (Brendan as May, May as Brendan) would turn it straight back. */
    fun settleBoth(scan: Scan, me: LookChar, worn: Worn?, rivalWorn: Worn?, known: Map<LookChar, Map<LookPart, ByteArray>>, extra: List<Worn> = emptyList()): List<Pal> {
        val mine = settle(scan, me, worn, known, extra)
        if (!hasRival(me.engine) || me.counterpart !in scan.chars) return mine
        val theirs = settle(scan, me.counterpart, rivalWorn, known)
        fun Pal.without(taken: List<Pal>) = Pal(from.filter { f -> taken.none { it.to.contentEquals(f) } }, to).takeIf { it.from.isNotEmpty() }
        return mine.mapNotNull { it.without(theirs) } + theirs.mapNotNull { it.without(mine) }
    }

    /** Whether a game of [engine] has the other gender's hero walking about (the rival): Emerald and Ruby / Sapphire;
     * FireRed's rival is a hero of neither. */
    fun hasRival(engine: LookEngine) = engine == LookEngine.EMERALD || engine == LookEngine.RUBY

    /**
     * The patch walking [me] (the hero of the game [scan] was made from) as a Pokémon ([mon], Heart and Soul's follower
     * sprite and pictures, [MonArt]): on the map its six frames in every one of the hero's records (made 32x32: the
     * bike's OAM and subsprite tables, each record a new frame table picking frames by the way its animations face -
     * [monRecords]), its palette in the hero's slot around the shared colours; in battle its back picture through
     * the throw, on the trainer card its front picture. Null where the records or the palette aren't found.
     */
    fun buildMon(scan: Scan, me: LookChar, mon: MonArt.Mon, known: Map<LookChar, Map<LookPart, ByteArray>> = emptyMap(), extra: List<Worn> = emptyList()): Patch? {
        if (me !in scan.chars) return null
        val writes = ArrayList<Pair<Int, ByteArray>>()
        val art = mapOf(LookPart.FRONT to mon.front, LookPart.FRONT_PAL to mon.pal, LookPart.BACK_PAL to mon.pal)
        val dressed = dress(scan, me, null, art, writes, (scan.free + 0x103) and 3.inv(), mon) ?: return null
        return Patch(merge(scan.rom, writes), settleBoth(scan, me, dressed.worn, null, known, extra), dressed.worn.pal)
    }

    /** [mon]'s overworld palette worn in [me]'s slot ([wearWith]). */
    fun wearMon(me: LookChar, mon: MonArt.Mon, own: (LookPart) -> ByteArray?): Worn? {
        val counts = IntArray(16)
        mon.frames.forEach { v -> counts[v.toInt() and 15]++; counts[(v.toInt() shr 4) and 15]++ }
        // Colour 0 is see-through, never counted as a colour to place.
        counts[0] = 0
        return wearWith(me, mon.owPal, reflect(mon.owPal), counts, own)
    }

    /**
     * Every overworld record of [me]'s ({tileTag, paletteTag, reflectionTag, size, width, height, ..., oam +0x10,
     * subspriteTables +0x14, anims +0x18, images +0x1C}) found through its frame table - the table's entries point into
     * one of the hero's sheets (any frame, any order; the normal table on into the running sheet) - set to 32x32 frames
     * from [frameAt] (south, south step, north, north step, west, west step): each table entry takes the frame facing
     * the way the record's animations show it (animations come in fours: south, north, west, east; the first four
     * stand, the rest step). Returns the tail's next free byte, or null with no record found.
     */
    private fun monRecords(scan: Scan, me: LookChar, frameAt: IntArray, writes: MutableList<Pair<Int, ByteArray>>, tail: Int): Int? {
        val rom = scan.rom
        val buf = ByteBuffer.wrap(rom).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(o: Int) = buf.getShort(o).toInt() and 0xFFFF
        fun u32(o: Int) = buf.getInt(o)
        fun word(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        val sheets = LookPart.entries.filter { it.w > 0 && it != LookPart.BACK }
        val range = sheets.associateWith { p -> scan.hitsOf(me, p).firstOrNull()?.let { it until it + blob(me, p)!!.size } }
        val running = range[LookPart.EM_RUNNING] ?: range[LookPart.FR_SURF_RUN]
        // Each sheet's tables: entries pointing at its first frame, back to the first entry inside its frames.
        val tables = HashMap<Int, List<IntRange>>()
        for (p in sheets) {
            val r = range[p] ?: continue
            val own = listOfNotNull(r, running?.takeIf { p == LookPart.EM_WALKING || p == LookPart.FR_NORMAL })
            fun inside(o: Int) = o + 8 <= rom.size && (u32(o) - 0x08000000).let { a -> own.any { a in it } }
            for (ref in scan.refs[r.first].orEmpty()) {
                if (ref.half0 != 128 && ref.half0 != 256 && ref.half0 != 512) continue
                var t = ref.at
                while (t >= 8 && inside(t - 8) && u16(t - 4) == ref.half0) t -= 8
                tables[t] = own
            }
        }
        if (tables.isEmpty()) return null
        // The records: a word pointing at a table, 0x1C into a record of 256 / 512-byte frames.
        val records = ArrayList<Pair<Int, Int>>()
        var o = 0x1C
        while (o + 4 <= rom.size) {
            val t = u32(o) - 0x08000000
            if (t in tables && u16(o - 0x1C) == 0xFFFF) records += (o - 0x1C) to t
            o += 4
        }
        // The 32x32 shapes: a record of the hero's already that size (a bike's).
        val big = records.firstOrNull { (r, _) -> u16(r + 8) == 32 && u16(r + 10) == 32 }?.first ?: return null
        val oam = rom.copyOfRange(big + 0x10, big + 0x18)
        var free = tail
        for ((r, t) in records) {
            val own = tables[t]!!
            val size = u16(t + 4)
            var n = 0
            while (t + n * 8 + 8 <= rom.size && u16(t + n * 8 + 4) == size && (u32(t + n * 8) - 0x08000000).let { a -> own.any { a in it } }) n++
            if (n == 0) continue
            val facing = facings(rom, u32(r + 0x18) - 0x08000000, n)
            val table = ByteArray(n * 8)
            for (i in 0 until n) {
                val (dir, stand) = facing[i] ?: (0 to true)
                val f = intArrayOf(0, 2, 4, 4)[dir] + if (stand) 0 else 1
                word(0x08000000 + frameAt[f]).copyInto(table, i * 8)
                table[i * 8 + 4] = 0x00; table[i * 8 + 5] = 0x02   // 512 bytes
            }
            if (free + table.size > rom.size) return null
            writes += free to table
            writes += (r + 6) to byteArrayOf(0x00, 0x02, 32, 0, 32, 0)
            // A Pokémon leaves footprints, not a bike's tyre tracks (TRACKS_BIKE_TIRE 2 -> TRACKS_FOOT 1).
            if (rom[r + 0xD].toInt() == 2) writes += (r + 0xD) to byteArrayOf(1)
            writes += (r + 0x10) to oam
            writes += (r + 0x1C) to word(0x08000000 + free)
            free = (free + table.size + 3) and 3.inv()
        }
        return free
    }

    /** Which way each of a record's [n] frames faces and whether it stands: from its animation table at [anims] (in
     * fours: south, north, west, east; the first four stand), the first animation using a frame deciding. */
    private fun facings(rom: ByteArray, anims: Int, n: Int): Array<Pair<Int, Boolean>?> {
        val out = arrayOfNulls<Pair<Int, Boolean>>(n)
        val buf = ByteBuffer.wrap(rom).order(ByteOrder.LITTLE_ENDIAN)
        if (anims < 0 || anims + 4 > rom.size) return out
        for (k in 0 until 40) {
            val p = buf.getInt(anims + k * 4) - 0x08000000
            if (p < 0 || p + 4 > rom.size || anims + k * 4 + 4 > rom.size) break
            for (c in 0 until 64) {
                if (p + c * 4 + 2 > rom.size) break
                val v = buf.getShort(p + c * 4).toInt() and 0xFFFF
                if (v == 0xFFFF || v == 0xFFFE) break
                if (v == 0xFFFD) continue
                if (v < n && out[v] == null) out[v] = (k % 4) to (k < 4)
            }
        }
        return out
    }

    /** [Worn] plus where the empty tail is free from after [dress]' writes. */
    private class Dressed(val worn: Worn, val free: Int)

    /** [me]'s graphics as [look]'s, into [writes]; compressed ones go in the empty tail from [tail]. Null: not this ROM. */
    private fun dress(
        scan: Scan, me: LookChar, look: LookChar?, art: Map<LookPart, ByteArray>, writes: MutableList<Pair<Int, ByteArray>>, tail: Int,
        /** A Pokémon instead of [look] ([buildMon]): its frames go in through the overworld records, not the sheets. */
        mon: MonArt.Mon? = null,
    ): Dressed? {
        val rom = scan.rom
        val own = { p: LookPart -> scan.data(me, p) }
        val worn = (if (mon != null) wearMon(me, mon, own) else wear(me, look!!, art, own)) ?: return null
        fun refsOf(part: LookPart) = scan.hitsOf(me, part).flatMap { scan.refs[it].orEmpty() }
        // The overworld palette goes in where the object-event palette table gives the hero's tag (the same 32 bytes
        // may sit elsewhere for something else - Emerald's region map head has its own copy). A ROM without that
        // entry (a hack that loads the hero's palette its own way) isn't dressed up: the frames alone would be
        // drawn in the wrong colours.
        fun palByTag(part: LookPart, tag: Int, new: ByteArray): Boolean {
            val at = scan.hitsOf(me, part).filter { h -> scan.refs[h].orEmpty().any { it.half0 == tag } }
            at.forEach { writes += it to new }
            return at.isNotEmpty()
        }
        if (!palByTag(LookPart.OW_PAL, me.owTag, worn.pal)) return null
        palByTag(LookPart.OW_REFLECTION_PAL, me.owTag + 1, worn.reflection)

        // Compressed graphics: written whole into the empty tail (an all-literal LZ77 stream), the words pointing at
        // the game's own ([sites]) moved there.
        var free = tail
        fun repoint(new: ByteArray, sites: List<Ref>) {
            if (sites.isEmpty()) return
            val stream = lzLiteral(new)
            val at = free
            if (at + stream.size > rom.size) return
            free = (at + stream.size + 3) and 3.inv()
            writes += at to stream
            val ptr = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x08000000 + at).array()
            sites.forEach { writes += it.at to ptr }
        }

        // Sheets and the back picture: frame by frame, the overworld frames in the worn palette's colours - in place,
        // or (Ruby / Sapphire's compressed back picture) moved to the tail.
        for (part in LookPart.entries.filter { it.w > 0 }) {
            val blob = blob(me, part) ?: continue
            val hits = scan.hitsOf(me, part)
            if (hits.isEmpty()) continue
            if (mon != null) {
                // A Pokémon throws nothing: its back picture in every frame of the throw, moved to where the battle
                // draws that Pokémon's own back ([MonArt.asTrainerBack]).
                if (part != LookPart.BACK) continue
                val back = MonArt.asTrainerBack(mon.back)
                val bytes = ByteArray(blob.size) { back[it % back.size] }
                if (blob.lz) repoint(bytes, refsOf(part)) else hits.forEach { writes += it to bytes }
                continue
            }
            val frames = sheet(part, me, look!!, art, own) ?: continue
            val keep = part == LookPart.BACK || part == LookPart.EM_UNDERWATER
            val bytes = frames.fold(ByteArray(0)) { acc, f -> acc + if (keep) f else remap(f, worn.map) }
            if (bytes.size != blob.size) continue
            if (blob.lz) repoint(bytes, refsOf(part)) else hits.forEach { writes += it to bytes }
        }
        // The region map head's palette: the copy nearest the head itself.
        val headAt = scan.hitsOf(me, LookPart.MAP_HEAD).firstOrNull()
        val headPal = art[LookPart.MAP_HEAD_PAL]
        if (headAt != null && headPal != null) {
            scan.hitsOf(me, LookPart.MAP_HEAD_PAL).minByOrNull { kotlin.math.abs(it - headAt) }?.let { writes += it to headPal }
        }

        // The trainer pictures' palettes, told apart by the tables' tags: the front one's is the tag its picture's
        // entry ({ptr, 0x800, tag}) carries. Ruby / Sapphire give the front and back entries the same tag and data,
        // so there the back one is the entry 24 bytes past the hero's back picture entry (the three back pictures,
        // then their palettes).
        val frontTag = refsOf(LookPart.FRONT).firstOrNull { it.half0 == 0x800 }?.half1 ?: me.frontTag
        val backSites = if (me.engine == LookEngine.RUBY) {
            val at = refsOf(LookPart.BACK).map { it.at + 24 }.toSet()
            refsOf(LookPart.BACK_PAL).filter { it.at in at }
        } else refsOf(LookPart.BACK_PAL).filter { it.half0 == me.backTag }
        val frontSites = refsOf(LookPart.FRONT_PAL).filter { r -> r.half0 == frontTag && backSites.none { it.at == r.at } }
        art[LookPart.FRONT]?.let { repoint(it, refsOf(LookPart.FRONT)) }
        art[LookPart.FRONT_PAL]?.let { repoint(it, frontSites) }
        art[LookPart.BACK_PAL]?.let { repoint(it, backSites) }
        if (blob(me, LookPart.MAP_HEAD)!!.lz) art[LookPart.MAP_HEAD]?.let { repoint(it, refsOf(LookPart.MAP_HEAD)) }
        else art[LookPart.MAP_HEAD]?.let { new -> scan.hitsOf(me, LookPart.MAP_HEAD).forEach { writes += it to new } }

        if (mon != null) {
            // The overworld: the Pokémon's six frames (in the slot's colours) in the tail once, then every one of the
            // hero's records made 32x32 over a frame table of its own pointing at them.
            val frameAt = IntArray(6) { i ->
                val at = free
                writes += at to remap(mon.frames.copyOfRange(i * 512, i * 512 + 512), worn.map)
                free = at + 512
                at
            }
            free = monRecords(scan, me, frameAt, writes, free) ?: return null
        }
        return Dressed(worn, free)
    }

    /** The palettes to put right after the ROM changed or a state loaded: whatever [me]'s slot held in another look
     * ([known]'s, or the game's own) becomes [worn]'s - or the game's own with [worn] null. */
    fun settle(scan: Scan, me: LookChar, worn: Worn?, known: Map<LookChar, Map<LookPart, ByteArray>>, extra: List<Worn> = emptyList()): List<Pal> {
        val own = { p: LookPart -> scan.data(me, p) }
        // [extra]: other looks a state may have loaded (the party's Pokémon).
        val all = known.filterKeys { it != me }.mapNotNull { (c, a) -> wear(me, c, a, own) } + extra
        val ownPal = own(LookPart.OW_PAL) ?: return emptyList()
        // Ruby / Sapphire: no reflection palette of the hero's to put right.
        val ownRefl = own(LookPart.OW_REFLECTION_PAL)
        return listOfNotNull(
            (worn?.pal ?: ownPal) to (all.map { it.pal } + ownPal),
            ownRefl?.let { (worn?.reflection ?: it) to (all.map { w -> w.reflection } + it) },
        ).mapNotNull { (to, candidates) ->
            val from = candidates.filter { !it.contentEquals(to) }.distinctBy { it.toList() }
            Pal(from, to).takeIf { from.isNotEmpty() }
        }
    }

    /** [frame]'s colour indices through [map]. */
    internal fun remap(frame: ByteArray, map: IntArray): ByteArray =
        ByteArray(frame.size) { i -> val b = frame[i].toInt(); (map[b and 15] or (map[(b shr 4) and 15] shl 4)).toByte() }

    /** Writes merged into blocks that don't overlap (a later write wins), each with the CRC32 of the file under it. */
    internal fun merge(rom: ByteArray, writes: List<Pair<Int, ByteArray>>): List<Block> {
        val spans = writes.map { it.first to it.first + it.second.size }.sortedBy { it.first }
        val merged = ArrayList<IntArray>()
        for ((s, e) in spans) {
            val last = merged.lastOrNull()
            if (last != null && s <= last[1]) last[1] = maxOf(last[1], e) else merged += intArrayOf(s, e)
        }
        return merged.map { (s, e) ->
            val data = rom.copyOfRange(s, e)
            for ((at, bytes) in writes) {
                if (at >= s && at + bytes.size <= e) bytes.copyInto(data, at - s)
            }
            Block(s, CRC32().apply { update(rom, s, e - s) }.value.toInt(), data)
        }
    }

    /** [part]'s frames for [me]'s sheet, made from [look]'s graphics: the same sheet as is within one engine, the
     * games' frames matched up by their animations across - through Emerald's sheets, which Ruby / Sapphire share.
     * Null leaves the game's own. */
    internal fun sheet(part: LookPart, me: LookChar, look: LookChar, art: Map<LookPart, ByteArray>, own: (LookPart) -> ByteArray?): List<ByteArray>? {
        fun frames(p: LookPart): List<ByteArray>? = art[p]?.let { d -> (0 until d.size / p.frameBytes).map { d.copyOfRange(it * p.frameBytes, (it + 1) * p.frameBytes) } }
        fun pick(src: List<ByteArray>?, idx: List<Int>, map: (ByteArray) -> ByteArray = { it }): List<ByteArray>? {
            val s = src ?: return null
            return idx.map { s.getOrNull(it)?.let(map) ?: return null }
        }
        if (me.engine == look.engine) return frames(part)
        val nine = (0..8).toList()
        // [look]'s frames for one of Emerald's sheets (or its back picture).
        fun em(p: LookPart): List<ByteArray>? {
            return when (look.engine) {
                LookEngine.EMERALD, LookEngine.HEART_AND_SOUL -> frames(p)
                LookEngine.RUBY -> when (p) {
                    LookPart.EM_WALKING -> frames(LookPart.RS_NORMAL)?.take(9)
                    LookPart.EM_RUNNING -> frames(LookPart.RS_NORMAL)?.drop(9)
                    else -> frames(p)
                }
                LookEngine.FIRERED -> when (p) {
                    LookPart.EM_WALKING -> pick(frames(LookPart.FR_NORMAL), nine)
                    // FireRed's run frames (stand, step, step per direction) into Emerald's walking-style order.
                    LookPart.EM_RUNNING -> pick(frames(LookPart.FR_SURF_RUN), listOf(3, 6, 9, 4, 5, 7, 8, 10, 11))
                    LookPart.EM_MACH_BIKE -> pick(frames(LookPart.FR_BIKE), nine)
                    // Wheelies and hops: the bike's frame facing the same way.
                    LookPart.EM_ACRO_BIKE -> pick(frames(LookPart.FR_BIKE), nine + listOf(0, 3, 0, 4, 1, 5, 1, 6, 2, 7, 2, 8, 3, 4, 5, 6, 7, 8))
                    LookPart.EM_SURFING -> pick(frames(LookPart.FR_SURF_RUN), listOf(0, 0, 1, 1, 2, 2), ::widen)
                    LookPart.EM_FIELD_MOVE -> pick(frames(LookPart.FR_ITEM), (0..4).toList(), ::widen)
                    LookPart.EM_FISHING -> pick(frames(LookPart.FR_FISH), (0..11).toList())
                    LookPart.EM_UNDERWATER -> {
                        val from = art[LookPart.OW_PAL] ?: return null
                        val to = own(LookPart.UNDERWATER_PAL) ?: return null
                        pick(frames(LookPart.FR_NORMAL), listOf(0, 1, 2)) { recolour(widen(it), from, to) }
                    }
                    LookPart.EM_WATERING -> pick(frames(LookPart.FR_NORMAL), listOf(0, 0, 1, 1, 2, 2), ::widen)
                    LookPart.EM_DECORATING -> pick(frames(LookPart.FR_NORMAL), listOf(0))
                    // Emerald's throw: ready (0), wind-up (1), throw (2), standing (3); FireRed's stands on 0, throws 1-4.
                    LookPart.BACK -> pick(frames(LookPart.BACK), listOf(1, 2, 4, 0))
                    else -> null
                }
            }
        }
        return when (me.engine) {
            LookEngine.EMERALD -> em(part)
            // Never dressed up ([engineFor]).
            LookEngine.HEART_AND_SOUL -> null
            LookEngine.RUBY -> if (part == LookPart.RS_NORMAL) em(LookPart.EM_WALKING)?.let { w -> w + (em(LookPart.EM_RUNNING) ?: return null) } else em(part)
            // [look] is Emerald's or Ruby / Sapphire's here.
            LookEngine.FIRERED -> when (part) {
                LookPart.FR_NORMAL -> em(LookPart.EM_WALKING)
                // Surfing (sitting, by direction), the run frames, then the two of the head shake (walking in place).
                LookPart.FR_SURF_RUN -> pick(em(LookPart.EM_SURFING), listOf(0, 2, 4), ::narrow)?.let { surf ->
                    surf + (pick(em(LookPart.EM_RUNNING), listOf(0, 3, 4, 1, 5, 6, 2, 7, 8)) ?: return null) +
                        (pick(em(LookPart.EM_WALKING), listOf(3, 4)) ?: return null)
                }
                LookPart.FR_BIKE -> em(LookPart.EM_MACH_BIKE)
                // The field move, then the VS Seeker's raised arm (no such frames in Emerald: the field move's).
                LookPart.FR_ITEM -> pick(em(LookPart.EM_FIELD_MOVE), listOf(0, 1, 2, 3, 4, 2, 3, 4, 4), ::narrow)
                LookPart.FR_FISH -> em(LookPart.EM_FISHING)
                LookPart.FR_VS_SEEKER_BIKE -> pick(em(LookPart.EM_MACH_BIKE), List(6) { 0 })
                LookPart.BACK -> pick(em(LookPart.BACK), listOf(3, 0, 1, 1, 2))
                else -> null
            }
        }
    }

    /** A reflection palette for a hero who has none (Ruby / Sapphire's): [pal] washed toward the water, like the
     * other games' hand-made ones. */
    internal fun reflect(pal: ByteArray): ByteArray = ByteArray(32).also { out ->
        for (i in 0 until 16) {
            val c = (pal[i * 2].toInt() and 0xFF) or ((pal[i * 2 + 1].toInt() and 0xFF) shl 8)
            val r = ((c and 31) * 3 + 25 * 2) / 5
            val g = (((c shr 5) and 31) * 3 + 27 * 2) / 5
            val b = (((c shr 10) and 31) * 3 + 29 * 2) / 5
            val v = if (i == 0) c else r or (g shl 5) or (b shl 10)
            out[i * 2] = v.toByte(); out[i * 2 + 1] = (v shr 8).toByte()
        }
    }

    /** A 16x32 frame (2x4 tiles) centred in a 32x32 one: a blank tile column either side. */
    internal fun widen(f: ByteArray): ByteArray {
        val out = ByteArray(16 * 32)
        for (ty in 0 until 4) for (tx in 0 until 2) f.copyInto(out, (ty * 4 + tx + 1) * 32, (ty * 2 + tx) * 32, (ty * 2 + tx + 1) * 32)
        return out
    }

    /** A 32x32 frame's middle two tile columns: a 16x32 one. */
    internal fun narrow(f: ByteArray): ByteArray {
        val out = ByteArray(8 * 32)
        for (ty in 0 until 4) for (tx in 0 until 2) f.copyInto(out, (ty * 2 + tx) * 32, (ty * 4 + tx + 1) * 32, (ty * 4 + tx + 2) * 32)
        return out
    }

    /** [frame]'s pixels moved from palette [from] to the nearest colour (by brightness) of [to]; 0 stays see-through. */
    internal fun recolour(frame: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
        fun lum(p: ByteArray, i: Int): Int {
            val c = (p[i * 2].toInt() and 0xFF) or ((p[i * 2 + 1].toInt() and 0xFF) shl 8)
            return 3 * (c and 31) + 6 * ((c shr 5) and 31) + ((c shr 10) and 31)
        }
        val map = IntArray(16) { i -> if (i == 0) 0 else (1..15).minBy { kotlin.math.abs(lum(to, it) - lum(from, i)) } }
        return ByteArray(frame.size) { i ->
            val b = frame[i].toInt()
            (map[b and 15] or (map[(b shr 4) and 15] shl 4)).toByte()
        }
    }

    /** [data] as a GBA LZ77 stream with no back-references (8 literals after each zero flag byte), padded to 4 bytes. */
    internal fun lzLiteral(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x10); out.write(data.size and 0xFF); out.write((data.size shr 8) and 0xFF); out.write((data.size shr 16) and 0xFF)
        for (i in data.indices step 8) {
            out.write(0)
            out.write(data, i, minOf(8, data.size - i))
        }
        while (out.size() % 4 != 0) out.write(0)
        return out.toByteArray()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}

/**
 * The heroes' graphics PLAYER LOOK can dress up as, decoded from the player's own ROMs: `rom-art/look/<hero>/<part>.bin`
 * under filesDir. Any FireRed / LeafGreen / Emerald scan ([PlayerLook.scan]) leaves its two heroes here; a hero counts
 * once all its graphics are.
 */
object LookArt {
    fun dir(filesDir: File?): File = File(RomArt.dir(filesDir), "look")

    /** Keeps every whole hero [scan] has; returns those newly kept. Each one's region map head goes in too, where
     * [RomArt] keeps it (`regionmap/player_<hero>.png`, for the companion's MAP), if no scan of its game made it. */
    fun keep(filesDir: File?, scan: PlayerLook.Scan): List<LookChar> = scan.chars.filter { c ->
        val d = File(dir(filesDir), c.name.lowercase())
        if (has(filesDir, c)) return@filter false
        d.mkdirs()
        LookBlob.entries.filter { it.char == c }.forEach { b ->
            val bytes = scan.data(c, b.part) ?: return@filter false
            write(File(d, "${b.part.name.lowercase()}.bin"), bytes)
        }
        val head = File(RomArt.dir(filesDir), "regionmap/${c.headArt}.png")
        if (!head.isFile) {
            val gfx = scan.data(c, LookPart.MAP_HEAD)
            val pal = scan.data(c, LookPart.MAP_HEAD_PAL)
            if (gfx != null && pal != null) {
                head.parentFile?.mkdirs()
                write(head, RomArt.sprite(gfx, RomArt.palette(pal), 16, 16).png())
                RomArt.announce()
            }
        }
        true
    }

    private fun write(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
    }

    fun has(filesDir: File?, c: LookChar): Boolean = LookBlob.entries.filter { it.char == c }.all { file(filesDir, c, it.part).isFile }

    /** Heroes whose graphics are all kept. */
    fun available(filesDir: File?): Set<LookChar> = LookChar.entries.filter { has(filesDir, it) }.toSet()

    fun load(filesDir: File?, c: LookChar): Map<LookPart, ByteArray>? {
        if (!has(filesDir, c)) return null
        return LookBlob.entries.filter { it.char == c }.associate { it.part to file(filesDir, c, it.part).readBytes() }
            .takeIf { m -> LookBlob.entries.filter { it.char == c }.all { b -> m[b.part]?.size == b.size } }
    }

    /** [c] on the overworld facing down - standing, then its two steps (its own colours, cropped to the pixels they
     * use) - for the picker's tiles; null if not kept. */
    fun walk(filesDir: File?, c: LookChar): List<RomArt.Image>? {
        val part = when (c.engine) {
            LookEngine.FIRERED -> LookPart.FR_NORMAL
            LookEngine.EMERALD, LookEngine.HEART_AND_SOUL -> LookPart.EM_WALKING
            LookEngine.RUBY -> LookPart.RS_NORMAL
        }
        val sheet = file(filesDir, c, part).takeIf { it.isFile }?.readBytes() ?: return null
        val pal = file(filesDir, c, LookPart.OW_PAL).takeIf { it.isFile }?.readBytes()?.takeIf { it.size == 32 } ?: return null
        if (sheet.size < 5 * part.frameBytes) return null
        val frames = listOf(0, 3, 4).map { RomArt.sprite(sheet.copyOfRange(it * part.frameBytes, (it + 1) * part.frameBytes), RomArt.palette(pal), 16, 32) }
        // Cropped to the pixels any of the three use (the frame's empty rows over the head), so the tile can draw it bigger.
        fun used(x: Int, y: Int) = frames.any { it.argb[y * 16 + x] != 0 }
        val top = (0 until 32).firstOrNull { y -> (0 until 16).any { used(it, y) } } ?: return frames
        val bottom = (31 downTo 0).first { y -> (0 until 16).any { used(it, y) } }
        val left = (0 until 16).first { x -> (top..bottom).any { used(x, it) } }
        val right = (15 downTo 0).first { x -> (top..bottom).any { used(x, it) } }
        return frames.map { f ->
            RomArt.Image(right - left + 1, bottom - top + 1).also { c ->
                for (y in top..bottom) for (x in left..right) c.argb[(y - top) * c.width + x - left] = f.argb[y * 16 + x]
            }
        }
    }

    /** [c]'s front pic (64x64, 4bpp) and its palette, for the companion's CARD; null if not kept. */
    fun frontPic(filesDir: File?, c: LookChar): Pair<ByteArray, ByteArray>? {
        val pic = file(filesDir, c, LookPart.FRONT).takeIf { it.isFile }?.readBytes() ?: return null
        val pal = file(filesDir, c, LookPart.FRONT_PAL).takeIf { it.isFile }?.readBytes() ?: return null
        return (pic to pal).takeIf { pic.size == 2048 && pal.size == 32 }
    }

    private fun file(filesDir: File?, c: LookChar, part: LookPart) = File(File(dir(filesDir), c.name.lowercase()), "${part.name.lowercase()}.bin")
}

/** A graphic found in a ROM by fingerprint (the generated [LookBlob] / [TextBlob] rows): its decoded [size] and
 * [crc], a 16-byte window of its raw bytes at [headOff] ([head], RomArt.head) and the window's prefilter [pre]. */
interface LookFingerprint {
    val size: Int
    val crc: Long
    val headOff: Int
    val head: Long
    val pre: Int
    val lz: Boolean get() = false
    val smol: Boolean get() = false
}

object Fingerprints {
    /** Every copy of every one of [blobs] in [rom]: one pass, RomArt's rolling window. ~0.5 s for 16 MiB: off the UI thread. */
    fun find(rom: ByteArray, blobs: Collection<LookFingerprint>): Map<LookFingerprint, List<Int>> {
        val byHead = blobs.groupBy { it.head }
        val filter = LongArray(1 shl 10)
        blobs.forEach { filter[it.pre ushr 6] = filter[it.pre ushr 6] or (1L shl it.pre) }
        val buf = ByteBuffer.wrap(rom).order(ByteOrder.LITTLE_ENDIAN)
        val hits = HashMap<LookFingerprint, MutableList<Int>>()
        if (rom.size >= 16) {
            var a = buf.getLong(0) shl 8
            for (i in 0..rom.size - 16) {
                a = (a ushr 8) or ((rom[i + 7].toLong() and 0xFF) shl 56)
                val p = RomArt.pre(a)
                if (filter[p ushr 6] and (1L shl p) == 0L) continue
                for (blob in byHead[RomArt.head(buf, i)] ?: continue) {
                    val off = i - blob.headOff
                    if (off < 0 || hits[blob]?.contains(off) == true) continue
                    if (decode(rom, off, blob) != null) hits.getOrPut(blob) { ArrayList() } += off
                }
            }
        }
        return hits
    }

    /** [blob] decoded at [off] (raw, LZ77 or smol), or null where it isn't (its CRC doesn't match). */
    fun decode(rom: ByteArray, off: Int, blob: LookFingerprint): ByteArray? {
        if (off < 0 || off >= rom.size) return null
        val data = try {
            when {
                blob.smol -> Smol.decompressAny(rom.copyOfRange(off, minOf(rom.size, off + 0x10000))).takeIf { it.size >= blob.size }?.copyOf(blob.size)
                blob.lz -> RomArt.lz77(rom, off, blob.size)
                off + blob.size <= rom.size -> rom.copyOfRange(off, off + blob.size)
                else -> null
            }
        } catch (_: RuntimeException) { // a stream that isn't one after all (out of bounds, a smol header that lies)
            null
        } ?: return null
        return data.takeIf { CRC32().apply { update(it) }.value == blob.crc }
    }
}
