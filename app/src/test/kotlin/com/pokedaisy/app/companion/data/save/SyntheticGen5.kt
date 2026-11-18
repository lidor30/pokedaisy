package com.pokedaisy.app.companion.data.save

/**
 * A made-up Black save (no player's file in the repo): the National Pokédex, 24 boxes, a few Pokémon in box 1, two in the
 * party, some money and a bag.
 */
object SyntheticGen5 {
    fun save(national: Boolean = true): ByteArray {
        val l = Gen5Layout.BW
        val out = ByteArray(Gen5Layout.FILE_SIZE)
        // Box names ("BOX 1" ...), the PC on box 1, every box open.
        for (b in 0 until Gen5Layout.BOXES) {
            val name = "BOX ${b + 1}"
            name.forEachIndexed { i, c -> SyntheticSave.le16(out, 4 + 0x28 * b + i * 2, c.code) }
            SyntheticSave.le16(out, 4 + 0x28 * b + name.length * 2, 0xFFFF)
        }
        out[0x3DD] = Gen5Layout.BOXES.toByte()
        for (b in 0 until Gen5Layout.BOXES) for (s in 0 until Gen5Layout.BOX_SLOTS) {
            Gen4BoxMon.emptyRecord().copyInto(out, Gen5Layout.FIRST_BOX + b * Gen5Layout.BOX_STRIDE + s * Gen5BoxMon.SIZE)
        }
        // Four Pokémon of Unova in box 1.
        listOf(495 to 5, 498 to 12, 501 to 18, 570 to 30).forEachIndexed { i, (species, level) ->
            val n = ByteArray(128)
            SyntheticSave.le16(n, 0, species)
            SyntheticSave.le32(n, 4, 0x1234_5678L)
            SyntheticSave.le32(n, 8, gen3ExpFor(level))
            n[0x20 + 0x19] = (i * 3).toByte()
            "HILBERT".forEachIndexed { k, c -> SyntheticSave.le16(n, 96 + k * 2, c.code) }
            SyntheticSave.le16(n, 96 + 14, 0xFFFF)
            for (k in 0 until 11) SyntheticSave.le16(n, 64 + k * 2, 0xFFFF)
            n[96 + 0x1C] = level.toByte()
            SyntheticSave.le16(n, 0x20, MOVES[i])
            n[0x20 + 8] = 30
            Gen4BoxMon.build(0x1000L + i * 977, n).copyInto(out, Gen5Layout.FIRST_BOX + i * Gen5BoxMon.SIZE)
        }
        // A party of two (their level and stats filled in by the editor's own party write below).
        SyntheticSave.le32(out, Gen5Layout.PARTY + 4, 0)
        // Money and the bag: a few balls and items, medicine, TMs, berries, two key items.
        SyntheticSave.le32(out, l.money, 23_456)
        SyntheticSave.le16(out, Gen5Layout.TRAINER + 0x24, 12); out[Gen5Layout.TRAINER + 0x26] = 34
        fun bag(off: Int, vararg stacks: Pair<Int, Int>) = stacks.forEachIndexed { i, (item, q) ->
            SyntheticSave.le16(out, Gen5Layout.ITEMS + off + i * 4, item); SyntheticSave.le16(out, Gen5Layout.ITEMS + off + i * 4 + 2, q)
        }
        bag(0x000, 4 to 12, 3 to 5, 2 to 3, 1 to 1, 81 to 1)
        bag(0x7D8, 17 to 6, 26 to 2, 28 to 1)
        bag(0x624, 328 to 1, 330 to 1, 420 to 1)
        bag(0x898, 155 to 4, 158 to 2)
        bag(0x4D8, 450 to 1, 437 to 1)
        "Hilbert".forEachIndexed { i, c -> SyntheticSave.le16(out, Gen5Layout.TRAINER + 4 + i * 2, c.code) }
        SyntheticSave.le16(out, Gen5Layout.TRAINER + 4 + 14, 0xFFFF)
        out[Gen5Layout.TRAINER + 0x1E] = 2
        SyntheticSave.le32(out, l.dex, Gen5Layout.DEX_MAGIC)
        SyntheticSave.le32(out, l.dex + 4, if (national) 1 else 0)
        // The footer and the checksum block's own CRC; the backup copy the same.
        SyntheticSave.le32(out, l.footerMagic, Gen5Layout.MAGIC)
        SyntheticSave.le16(out, l.checksumsCrc, Gen4SaveFile.crc16(out, l.checksums, l.checksumsLen))
        val file = (Gen5SaveFile.parse(out) as Gen5SaveFile.Parsed.Ok).save
        val ed = Gen5SaveEditor(file, demoGame())
        ed.move(MonRef.Pc(BoxSlot(0, 3)), MonRef.Party(0))
        ed.move(MonRef.Pc(BoxSlot(0, 2)), MonRef.Party(1))
        ed.registerOwned()
        return ed.build()
    }

    /** Each made-up Pokémon's first move: TACKLE, TACKLE, TACKLE, SCRATCH. */
    private val MOVES = intArrayOf(33, 33, 33, 10)

    private fun gen3ExpFor(level: Int): Long = com.pokedaisy.app.companion.data.gen3ExperienceTables[0][level].toLong()

    /** Black's [Gen5GameData] for the screenshots: names from the app's tables, plain personal records. */
    fun demoGame(language: Int = 2): Gen5GameData = Gen5GameData(
        species = List(650) { com.pokedaisy.app.companion.data.speciesNamesBlack[it]?.uppercase() ?: "" },
        moveNames = List(560) { com.pokedaisy.app.companion.data.moveDataBlack[it]?.name ?: "" },
        items = List(639) { com.pokedaisy.app.companion.data.itemNamesBlack[it] ?: "" },
        abilityNames = emptyList(),
        personal = List(650) { s -> if (s == 0) null else ByteArray(0x3C).also { r -> for (i in 0 until 6) r[i] = 60; r[0x12] = 127 } },
        gameLanguage = language, layout = Gen5Layout.BW,
        levelUp = List(650) { listOf(1 to 33, 1 to 10, 7 to 45, 13 to 22) },
        movePp = IntArray(560) { if (it == 0) 0 else 20 },
        itemPockets = IntArray(639) { i ->
            when (i) {
                in 1..16, 81 -> 0
                in 17..54 -> 1
                in 328..425, in 618..620 -> 2
                in 149..212 -> 3
                437, 450 -> 4
                else -> -1
            }
        },
        regional = IntArray(650) { if (it >= 494) it - 494 else -1 },
    )
}
