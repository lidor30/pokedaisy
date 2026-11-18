package com.pokedaisy.app.companion.data.save

/**
 * A made-up HeartGold / SoulSilver save, written by its own code (the layout from pret/pokeheartgold, independently of
 * [Gen4SaveFile]): [Gen4SaveTest]'s, and the SAVE EDITOR's DS screenshots in ui-preview.
 */
object SyntheticGen4 {
    fun crypt(b: ByteArray, seed: Long): ByteArray {
        val out = ByteArray(b.size)
        var s = seed
        for (i in b.indices step 2) {
            s = (s * 1103515245L + 24691L) and 0xFFFFFFFFL   // the same LCG written another way: 0x41C64E6D, 0x6073
            val w = (((b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)) xor (s ushr 16).toInt()) and 0xFFFF
            out[i] = w.toByte(); out[i + 1] = (w shr 8).toByte()
        }
        return out
    }

    private val orders = listOf(
        "ABCD", "ABDC", "ACBD", "ACDB", "ADBC", "ADCB", "BACD", "BADC", "BCAD", "BCDA", "BDAC", "BDCA",
        "CABD", "CADB", "CBAD", "CBDA", "CDAB", "CDBA", "DABC", "DACB", "DBAC", "DBCA", "DCAB", "DCBA",
    )

    /** A 136-byte BoxPokemon (encrypted) and, with [level], its 100 party bytes after it. */
    fun mon(pid: Long, species: Int, exp: Long, otId: Long = 0x0BAD_3039, level: Int? = null, hp: Int = 50, egg: Boolean = false, item: Int = 0): ByteArray {
        val blocks = Array(4) { ByteArray(32) }
        SyntheticSave.le16(blocks[0], 0, species)
        SyntheticSave.le16(blocks[0], 2, item)
        SyntheticSave.le32(blocks[0], 4, otId)
        SyntheticSave.le32(blocks[0], 8, exp)
        blocks[0][0x0C] = 70; blocks[0][0x0D] = 65; blocks[0][0x0F] = 2
        SyntheticSave.le16(blocks[1], 0, 33); blocks[1][8] = 35
        SyntheticSave.le32(blocks[1], 0x10, 0x0F7B_DEF7L or (if (egg) 1L shl 30 else 0))
        blocks[1][0x18] = ((if ((pid and 0xFF) < 127) 1 else 0) shl 1).toByte()
        SyntheticSave.le16(blocks[2], 0, 0xFFFF)   // no nickname: the species' name stands in
        "RED".forEachIndexed { i, ch -> SyntheticSave.le16(blocks[3], i * 2, 0x12B + (ch - 'A')) }
        SyntheticSave.le16(blocks[3], 6, 0xFFFF)
        val logical = blocks.reduce { a, b -> a + b }
        var sum = 0
        for (i in 0 until 64) sum = (sum + ((logical[i * 2].toInt() and 0xFF) or ((logical[i * 2 + 1].toInt() and 0xFF) shl 8))) and 0xFFFF
        val order = orders[(((pid shr 13) and 31) % 24).toInt()]
        val stored = order.map { blocks["ABCD".indexOf(it)] }.reduce { a, b -> a + b }
        val box = ByteArray(0x88)
        SyntheticSave.le32(box, 0, pid); SyntheticSave.le16(box, 6, sum)
        crypt(stored, sum.toLong()).copyInto(box, 8)
        if (level == null) return box
        val ext = ByteArray(100).also { it[4] = level.toByte(); SyntheticSave.le16(it, 6, hp); SyntheticSave.le16(it, 8, hp) }
        return box + crypt(ext, pid)
    }

    fun crc(b: ByteArray, off: Int, len: Int): Int {
        var c = 0xFFFF
        for (i in off until off + len) {
            c = c xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    fun footer(out: ByteArray, off: Int, size: Int, count: Long, slot: Int) {
        val f = off + size - 16
        SyntheticSave.le32(out, f, count); SyntheticSave.le32(out, f + 4, size.toLong()); SyntheticSave.le32(out, f + 8, 0x20060623)
        SyntheticSave.le16(out, f + 12, slot); SyntheticSave.le16(out, f + 14, crc(out, off, size - 16))
    }

    /** Both halves: half 0 (counter 7) and the newer half 1 (counter 8), which the game loads; [national]: its National Pokédex. */
    fun save(national: Boolean = false): ByteArray {
        val out = ByteArray(Gen4SaveFile.SIZE)
        for ((half, count) in listOf(0 to 7L, 1 to 8L)) {
            val base = half * 0x40000
            // RED, ID 12345 / SID 2989, money 3000.
            "RED".forEachIndexed { i, ch -> SyntheticSave.le16(out, base + 0x64 + i * 2, 0x12B + (ch - 'A')) }
            SyntheticSave.le16(out, base + 0x64 + 6, 0xFFFF)
            SyntheticSave.le32(out, base + 0x74, 0x0BAD_3039); SyntheticSave.le32(out, base + 0x78, 3000)
            SyntheticSave.le32(out, base + 0x90, 6); SyntheticSave.le32(out, base + 0x94, 2)
            mon(0x1234_5678, 154, 125_000, level = 50).copyInto(out, base + 0x98)
            mon(0x0000_0017, 25, 1000, level = 10).copyInto(out, base + 0x98 + 236)
            for (i in 2 until 6) (Gen4BoxMon.emptyRecord() + crypt(ByteArray(100), 0)).copyInto(out, base + 0x98 + i * 236)
            val pc = base + 0xF700
            for (s in 0 until 18 * 30) Gen4BoxMon.emptyRecord().copyInto(out, pc + (s / 30) * 0x1000 + (s % 30) * 0x88)
            mon(0x100, 1, 500).copyInto(out, pc)
            mon(0x205, 4, 900).copyInto(out, pc + 0x88)
            mon(0xFFFF_FFF0, 150, 1_250_000, otId = 1).copyInto(out, pc + 0x1000)   // box 2
            // The bag: LEFTOVERS x1 (ITEMS), POTION x3 (MEDICINE: after items 165, key 50, TMs 101, mail 12).
            SyntheticSave.le16(out, base + 0x644, 234); SyntheticSave.le16(out, base + 0x646, 1)
            val medicine = base + 0x644 + (165 + 50 + 101 + 12) * 4
            SyntheticSave.le16(out, medicine, 17); SyntheticSave.le16(out, medicine + 2, 3)
            // Box names: "BOX 1" .. "BOX 18".
            for (bx in 0 until 18) {
                val codes = listOf(0x12C, 0x139, 0x142, 0x1DE) + (bx + 1).toString().map { 0x121 + (it - '0') } + 0xFFFF
                codes.forEachIndexed { i, c -> SyntheticSave.le16(out, pc + 0x12008 + bx * 40 + i * 2, c) }
            }
            if (national) out[base + 0x12B8 + 0x337] = 1
            footer(out, base, 0xF628, count, 0)
            footer(out, pc, 0x12310, count, 1)
        }
        return out
    }


    /** A HeartGold / SoulSilver [Gen4GameData] for the screenshots: species and move names from the app's tables. */
    fun demoGame(layout: Gen4Layout = Gen4Layout.HGSS): Gen4GameData = Gen4GameData(
        species = List(494) { com.pokedaisy.app.companion.data.speciesNamesPlatinum[it]?.uppercase() ?: "" },
        moveNames = List(468) { com.pokedaisy.app.companion.data.moveDataPlatinum[it]?.name?.uppercase() ?: "" },
        items = List(537) { com.pokedaisy.app.companion.data.itemNamesHeartGold[it]?.uppercase() ?: "" },
        abilityNames = List(124) { if (it == 65) "OVERGROW" else if (it == 34) "CHLOROPHYLL" else "ABILITY $it" },
        personal = List(494) { s ->
            if (s == 0) null else ByteArray(0x2C).also { r ->
                for (i in 0 until 6) r[i] = (40 + s % 60 + i * 5).toByte()
                r[0x10] = 127; r[0x13] = 0; r[0x16] = 65; r[0x17] = 34; r[0x1C] = 1
            }
        },
        levelUp = List(494) { listOf(1 to 33, 7 to 45, 20 to 22) },
        preEvolution = IntArray(494),
        tmMoves = IntArray(100) { 264 + it },
        movePp = IntArray(468) { if (it == 0) 0 else 20 },
        itemPockets = demoItemPockets,
        layout = layout,
    )

    /** POTION (17) in MEDICINE, LEFTOVERS (234), METAL COAT (233), EVERSTONE (229) in ITEMS, ORAN BERRY (155) in BERRIES, a
     * mail (137) in MAIL. */
    val demoItemPockets: IntArray = IntArray(537) { -1 }.also { it[17] = 1; it[234] = 0; it[233] = 0; it[229] = 0; it[155] = 4; it[137] = 5 }

    /** Platinum's 20-byte footer (pokeplatinum's SaveBlockFooter). */
    fun ptFooter(out: ByteArray, off: Int, size: Int, global: Long, count: Long, id: Int) {
        val f = off + size - 20
        SyntheticSave.le32(out, f, global); SyntheticSave.le32(out, f + 4, count); SyntheticSave.le32(out, f + 8, size.toLong())
        SyntheticSave.le32(out, f + 12, 0x20060623); out[f + 16] = id.toByte()
        SyntheticSave.le16(out, f + 18, crc(out, off, size - 20))
    }

    /**
     * A made-up Platinum save (pret/pokeplatinum's layout): the primary copy at global 4, the backup at global 5 (loaded);
     * RED's party of TYPHLOSION and PIKACHU, BULBASAUR and CHARMANDER in box 1, MEWTWO (another trainer's) in box 2.
     */
    fun savePlatinum(): ByteArray {
        val out = ByteArray(Gen4SaveFile.SIZE)
        for ((half, global) in listOf(0 to 4L, 1 to 5L)) {
            val base = half * 0x40000
            "RED".forEachIndexed { i, ch -> SyntheticSave.le16(out, base + 0x68 + i * 2, 0x12B + (ch - 'A')) }
            SyntheticSave.le16(out, base + 0x68 + 6, 0xFFFF)
            SyntheticSave.le32(out, base + 0x78, 0x0BAD_3039); SyntheticSave.le32(out, base + 0x7C, 3000)
            SyntheticSave.le32(out, base + 0x98, 6); SyntheticSave.le32(out, base + 0x9C, 2)
            mon(0x1234_5678, 154, 125_000, level = 50).copyInto(out, base + 0xA0)
            mon(0x0000_0017, 25, 1000, level = 10).copyInto(out, base + 0xA0 + 236)
            for (i in 2 until 6) (Gen4BoxMon.emptyRecord() + crypt(ByteArray(100), 0)).copyInto(out, base + 0xA0 + i * 236)
            val pc = base + 0xCF2C
            for (sl in 0 until 18 * 30) Gen4BoxMon.emptyRecord().copyInto(out, pc + 4 + sl * 0x88)
            mon(0x100, 1, 500).copyInto(out, pc + 4)
            mon(0x205, 4, 900).copyInto(out, pc + 4 + 0x88)
            mon(0xFFFF_FFF0, 150, 1_250_000, otId = 1).copyInto(out, pc + 4 + 30 * 0x88)   // box 2
            SyntheticSave.le16(out, base + 0x630, 234); SyntheticSave.le16(out, base + 0x632, 1)
            val medicine = base + 0x630 + (165 + 50 + 100 + 12) * 4
            SyntheticSave.le16(out, medicine, 17); SyntheticSave.le16(out, medicine + 2, 3)
            val names = pc + 4 + 18 * 30 * 0x88
            for (bx in 0 until 18) {
                val codes = listOf(0x12C, 0x139, 0x142, 0x1DE) + (bx + 1).toString().map { 0x121 + (it - '0') } + 0xFFFF
                codes.forEachIndexed { i, c -> SyntheticSave.le16(out, names + bx * 40 + i * 2, c) }
            }
            ptFooter(out, base, 0xCF2C, global, global * 2, 0)
            ptFooter(out, pc, 0x121E4, global, global * 3, 1)
        }
        return out
    }
}
