package com.pokedaisy.app.companion.data.save

/** A made-up Black save (no player's file in the repo): the National Pokédex, 24 boxes, a few Pokémon in box 1. */
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
            Gen4BoxMon.build(0x1000L + i * 977, n).copyInto(out, Gen5Layout.FIRST_BOX + i * Gen5BoxMon.SIZE)
        }
        "Hilbert".forEachIndexed { i, c -> SyntheticSave.le16(out, Gen5Layout.TRAINER + 4 + i * 2, c.code) }
        SyntheticSave.le16(out, Gen5Layout.TRAINER + 4 + 14, 0xFFFF)
        out[Gen5Layout.TRAINER + 0x1E] = 2
        SyntheticSave.le32(out, l.dex, Gen5Layout.DEX_MAGIC)
        SyntheticSave.le32(out, l.dex + 4, if (national) 1 else 0)
        // The footer and the checksum block's own CRC; the backup copy the same.
        SyntheticSave.le32(out, l.footerMagic, Gen5Layout.MAGIC)
        SyntheticSave.le16(out, l.checksumsCrc, Gen4SaveFile.crc16(out, l.checksums, l.checksumsLen))
        val file = (Gen5SaveFile.parse(out) as Gen5SaveFile.Parsed.Ok).save
        return file.withData(file.data.also { it[0] = it[0] })
    }

    private fun gen3ExpFor(level: Int): Long = com.pokedaisy.app.companion.data.gen3ExperienceTables[0][level].toLong()

    /** Black's [Gen5GameData] for the screenshots: names from the app's tables, plain personal records. */
    fun demoGame(language: Int = 2): Gen5GameData = Gen5GameData(
        species = List(650) { com.pokedaisy.app.companion.data.speciesNamesBlack[it]?.uppercase() ?: "" },
        moveNames = emptyList(), items = emptyList(), abilityNames = emptyList(),
        personal = List(650) { s -> if (s == 0) null else ByteArray(0x3C).also { r -> for (i in 0 until 6) r[i] = 60; r[0x12] = 127 } },
        gameLanguage = language, layout = Gen5Layout.BW,
    )
}
