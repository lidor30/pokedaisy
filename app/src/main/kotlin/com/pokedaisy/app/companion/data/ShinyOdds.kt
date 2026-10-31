package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.data.save.Gen3BoxMon
import java.util.Random

/**
 * SHINY ODDS (GAMEPLAY on the companion, `Prefs.shinyOdds`): a wild or legendary Pokémon turns up shiny at the odds the
 * player picked. Not by raising the game's own check (`shinyValue < 8`): that would make Pokémon already caught shiny, and
 * plain again when switched off. Instead each new foe is changed in gEnemyParty between the frame the game creates it
 * (CreateWildMon / the scripted legendaries, both with the player's OT ID) and the battle drawing it - the transition gives
 * a second or more, and nothing is touched once gMain.inBattle is set. Its personality is swapped for one that is shiny for
 * the player's ID and keeps what the personality decides: nature (% 25), gender and ability bit (the low byte), UNOWN's
 * letter and WURMPLE's evolution; the substructs are reordered and encrypted again ([Gen3BoxMon.withPersonality]). IVs,
 * moves and stats stay as they were. A caught one keeps the player's OT, so it stays shiny with the setting off. What
 * legality checkers trace (the personality / IV link of the game's RNG) doesn't hold for it - the manual says so.
 *
 * Retail FireRed, LeafGreen, Ruby, Sapphire and Emerald, every language and revision ([targetFor]): vanilla's encrypted
 * 100-byte struct at each config's own addresses, on a ROM whose hash is a retail one (the Poller's [NativeConfig.shinyBelow]).
 * Each was checked headless by scripts/verify_shiny.py: the foe made shiny in a scripted wild battle, the battle drawing it
 * in other colours. Hacks by hash too ([HACK_SHINY_BELOW], each through the same check at its own shiny rule), in their
 * own box formats ([Target]): plaintext boxes get the personality written alone, and Elite Redux / R.O.W.E., which keep
 * shininess as a bit of their own, get that bit set instead.
 */
object ShinyOdds {
    /** The game's own odds: 8 shiny values in 65536. */
    const val NORMAL = 8192
    val CHOICES = listOf(NORMAL, 4096, 1024, 512, 128)
    /** Debug builds: almost every one, and every one, to check it quickly. */
    val DEBUG_CHOICES = CHOICES + listOf(4, 1)
    fun choices(debug: Boolean) = if (debug) DEBUG_CHOICES else CHOICES

    /**
     * The other retail versions' hashes (the Poller's: the file's SHA-1 with the GPIO bytes at 0xC4 zeroed) - every
     * language and revision of FireRed, LeafGreen, Ruby, Sapphire and Emerald but English FireRed / Emerald, which the
     * Poller already knows by hash. Each one passed scripts/verify_shiny.py.
     */
    val RETAIL_SHA1S: Set<String> = setOf(
        "5a087835009d552d4c5c1f96be3be3206e378153", // AXPD0 Pokemon - Saphir-Edition (Germany)
        "7e6e034f9cdca6d2c4a270fdb50a94def5883d17", // AXPD1 Pokemon - Saphir-Edition (Germany) (Rev 1)
        "3ccbbd45f8553c36463f13b938e833f652b793e4", // AXPE0 Pokemon - Sapphire Version (USA, Europe)
        "4722efb8cd45772ca32555b98fd3b9719f8e60a9", // AXPE1 Pokemon - Sapphire Version (USA, Europe) (Rev 1)
        "89b45fb172e6b55d51fc0e61989775187f6fe63c", // AXPE2 Pokemon - Sapphire Version (USA, Europe) (Rev 2)
        "c269b5692b2d0e5800ba1ddf117fda95ac648634", // AXPF0 Pokemon - Version Saphir (France)
        "860e93f5ea44f4278132f6c1ee5650d07b852fd8", // AXPF1 Pokemon - Version Saphir (France) (Rev 1)
        "f729dd571fb2c09e72c5c1d68fe0a21e72713d34", // AXPI0 Pokemon - Versione Zaffiro (Italy)
        "73edf67b9b82ff12795622dca412733755d2c0fe", // AXPI1 Pokemon - Versione Zaffiro (Italy) (Rev 1)
        "3233342c2f3087e6ffe6c1791cd5867db07df842", // AXPJ0 Pocket Monsters - Sapphire (Japan)
        "01f509671445965236ac4c6b5a354fe2f1e69f13", // AXPJ1 Pocket Monsters - Sapphire (Japan) (Rev 1)
        "3a6489189e581c4b29914071b79207883b8c16d8", // AXPS0 Pokemon - Edicion Zafiro (Spain)
        "0fe9ad1e602e2fafa090aee25e43d6980625173c", // AXPS1 Pokemon - Edicion Zafiro (Spain) (Rev 1)
        "1c2a53332382e14dab8815e3a6dd81ad89534050", // AXVD0 Pokemon - Rubin-Edition (Germany)
        "424740be1fc67a5ddb954794443646e6aeee2c1b", // AXVD1 Pokemon - Rubin-Edition (Germany) (Rev 1)
        "f28b6ffc97847e94a6c21a63cacf633ee5c8df1e", // AXVE0 Pokemon - Ruby Version (USA, Europe)
        "610b96a9c9a7d03d2bafb655e7560ccff1a6d894", // AXVE1 Pokemon - Ruby Version (USA, Europe) (Rev 1)
        "5b64eacf892920518db4ec664e62a086dd5f5bc8", // AXVE2 Pokemon - Ruby Version (USA, Europe) (Rev 2)
        "a6ee94202bec0641c55d242757e84dc89336d4cb", // AXVF0 Pokemon - Version Rubis (France)
        "ba888dfba231a231cbd60fe228e894b54fb1ed79", // AXVF1 Pokemon - Version Rubis (France) (Rev 1)
        "2b3134224392f58da00f802faa1bf4b5cf6270be", // AXVI0 Pokemon - Versione Rubino (Italy)
        "015a5d380afe316a2a6fcc561798ebff9dfb3009", // AXVI1 Pokemon - Versione Rubino (Italy) (Rev 1)
        "5c5e546720300b99ae45d2aa35c646c8b8ff5c56", // AXVJ0 Pocket Monsters - Ruby (Japan)
        "971e0d670a95e5b32240b2deed20405b8daddf47", // AXVJ1 Pocket Monsters - Ruby (Japan) (Rev 1)
        "1f49f7289253dcbfecbc4c5ba3e67aa0652ec83c", // AXVS0 Pokemon - Edicion Rubi (Spain)
        "9ac73481d7f5d150a018309bba91d185ce99fb7c", // AXVS1 Pokemon - Edicion Rubi (Spain) (Rev 1)
        "61c2eb2b380b1a75f0c94b767a2d4c26cd7ce4e3", // BPED0 Pokemon - Smaragd-Edition (Germany)
        "ca666651374d89ca439007bed54d839eb7bd14d0", // BPEF0 Pokemon - Version Emeraude (France)
        "1692db322400c3141c5de2db38469913ceb1f4d4", // BPEI0 Pokemon - Versione Smeraldo (Italy)
        "d7cf8f156ba9c455d164e1ea780a6bf1945465c2", // BPEJ0 Pocket Monsters - Emerald (Japan)
        "fe1558a3dcb0360ab558969e09b690888b846dd9", // BPES0 Pokemon - Edicion Esmeralda (Spain)
        "0802d1fb185ee3ed48d9a22afb25e66424076dac", // BPGD0 Pokemon - Blattgruene Edition (Germany)
        "574fa542ffebb14be69902d1d36f1ec0a4afd71e", // BPGE0 Pokemon - LeafGreen Version (USA, Europe)
        "7862c67bdecbe21d1d69ce082ce34327e1c6ed5e", // BPGE1 Pokemon - LeafGreen Version (USA, Europe) (Rev 1)
        "4b5758c14d0a07b70ef3ef0bd7fa5e7ce6978672", // BPGF0 Pokemon - Version Vert Feuille (France)
        "a1dfea1493d26d1f024be8ba1de3d193fcfc651e", // BPGI0 Pokemon - Versione Verde Foglia (Italy)
        "5946f1b59e8d71cc61249661464d864185c92a5f", // BPGJ0 Pocket Monsters - LeafGreen (Japan)
        "de9d5a844f9bfb63a4448cccd4a2d186ecf455c3", // BPGJ1 Pocket Monsters - LeafGreen (Japan) (Rev 1)
        "f9ebee5d228cb695f18ef2ced41630a09fa9eb05", // BPGS0 Pokemon - Edicion Verde Hoja (Spain)
        "18a3758ceeef2c77b315144be2c3910d6f1f69fe", // BPRD0 Pokemon - Feuerrote Edition (Germany)
        "fc663907256f06a3a09e2d6b967bc9af4919f111", // BPRF0 Pokemon - Version Rouge Feu (France)
        "66a9d415205321376b4318534c0dce5f69d28362", // BPRI0 Pokemon - Versione Rosso Fuoco (Italy)
        "04139887b6cd8f53269aca098295b006ddba6cfe", // BPRJ0 Pocket Monsters - FireRed (Japan)
        "7c7107b87c3ccf6e3dbceb9cf80ceeffb25a1857", // BPRJ1 Pocket Monsters - FireRed (Japan) (Rev 1)
        "ab8f6bfe0ccdaf41188cd015c8c74c314d02296a", // BPRS0 Pokemon - Edicion Rojo Fuego (Spain)
    )

    /**
     * The hacks it works on: masked hash -> their own shiny check (shiny below this shiny value), each measured by
     * scripts/verify_shiny.py --probe (foes at chosen shiny values, watched in battle) after the foe made shiny passed.
     * (*) Recharged Emerald / Yellow tint each Pokémon by its personality, so a probe's colours say nothing: theirs is
     * vanilla's 8 - a shiny of theirs at 8.. is at worst left unmarked, never a plain one marked.
     */
    val HACK_SHINY_BELOW: Map<String, Int> = mapOf(
        "a7c4e34fafb53f2d5283eb6d43dad285c0dd40a8" to 8, // Emerald Kaizo v2.0
        "cbd71af65ee3dcd53280a6a12014bfb11e8f626a" to 8, // Emerald Legacy v1.1.4
        "67f44fb4cdbb16c0d1a495ac219f2979b1e3005f" to 8, // Recharged Emerald v2.2.5, the original rule (*)
        "56c9777215f5be627f7c3099e68985aefc10f2b7" to 8, // Recharged Emerald v2.2.8, the original rule (*)
        "08b9c7903ae4c4e26d80ef945a90c004ae1e0ebb" to 8, // Recharged Emerald Rebalanced v2.2.8, the original rule (*)
        "e10105d8544469c6a11ca2cf510289981df3c3b0" to 16, // Glazed 9.2.0 (its own 1 in 4096)
        "3b6c51d8fb6b513bdd01b7bda3ed8965bc985d58" to 8, // Ultra Violet v1.22
        "8bac897de515f88e6ce92d0712ce2c018a394b37" to 8, // Orange Islands
        "033d05374ae34eb84fae62c693ca8f3da23dd1bd" to 8, // FireRed Rocket Edition
        "1ee1557db1e4c2e0e94c6306efcc40506bc40cc9" to 8, // Ash Gray v4.5.3
        "92ed82145c013069472153391c7aab1cfbf37b75" to 8, // Ash Gray Fork Bug Fix v1.0
        "be5033598e91d0e80fa93e551406f02a7876d7c2" to 8, // Recharged Yellow v1.8.0, the original rule (*)
        "ac30d1ed80e75104a3fb92f71e2b9a51c26e80c8" to 8, // Recharged Yellow v1.9.0, the original rule (*)
        "7d9fae6cf31c07bc7fa5cf7afe137ad5385cefb1" to 8, // Recharged Yellow v1.9.4, the original rule (*)
        "ed60654d7525ba005bfc8f799bf0ecba2ef08a2e" to 8, // Recharged Yellow v1.9.7, the original rule (*)
        "d5b1e77975fcda831e0e9a7b527906bf3f40ecd0" to 8, // Gaia v3.2 (CFRU)
        "964f951a0fdaf209e4ea1344883ef0d557bb3a80" to 8, // Radical Red v4.1 (CFRU)
        "8745ddbdbfadf6abaf66de4e9055923b62eb4668" to 255, // Odyssey v4.1.1 (shiny value 254 shiny, 255 not) (CFRU)
        "afe4c8e0f9c7186860fcea5f25d8318f5d7929b9" to 8, // Sword and Shield Ultimate Plus v1.2.1.2 (CFRU)
        "daa2f89934d2c0447d5271dabeba86bfba04e1fd" to 8, // Sword and Shield Ultimate Plus, Casual Patch (CFRU)
        "4858cc3d7b335bb5eed553861d7a4a17bad9ce2c" to 8, // Sword and Shield Ultimate Plus, Performance Patch (CFRU)
        "932323d1fa3732d8673d1319fa86ed4df41087b9" to 8, // Sword and Shield Ultimate Plus, Casual and Performance Patch (CFRU)
        "25f8840ecb58e584edf18ffde35f845e94f3f17c" to 8, // GS Chronicles v2.7.6 (CFRU)
        "00e70c0384a5f1698588034201fd5b849d3542e2" to 8, // Amethyst v1.3.0 (CFRU)
        "91291aade04b4b111cd03ae7b6e2ff460e1edd8a" to 16, // Amethyst v1.4.1 (CFRU, its own 1 in 4096)
        "b4776b82a4c7915d0fadeaa27e013523f99dfd94" to 16, // Unbound v2.1.1.1 (CFRU, its own 1 in 4096)
        "0ce2a880aa097f1dce4e1db8ee513d0e82d15859" to 16, // Unbound v2.1.1.1 FR (CFRU, its own 1 in 4096)
        "7dcdc7e280bc4631487e13dd37e6e0cea04adea6" to 16, // Lazarus v2.0 (its colours shift with the personality: 15 drew nearest the shiny, 16 the normal) (expansion)
        "b9f4d332d30fc88c379f9e037f9eae3b2755ead4" to 16, // Emerald Seaglass v3.0 (expansion)
        "3fa8e61ec1727cc51540c54677623f736a446e1b" to 16, // Too Many Types 2 v1.5.2 (expansion)
        "1d20091c4d936f5eb122db8780554dd0829ffb63" to 36, // Emerald Imperium v1.3.1 (35 shiny, 36 not, on the save checked) (expansion)
        "06e79d12afa578db97c23c1f7ba771fe2f8fe905" to 16, // Super Mariomon v1.5.2 (expansion)
        "831fe0f97301f17b24a9cd4a64ef0520835c4dde" to 16, // Super Mariomon v1.5.1 (expansion)
        "043512c2497871c018df606fb90ffbd4c81430f4" to 16, // Super Mariomon v1.3 (expansion)
        "08d1c275c0ba4d559b33427292702d61ad49fb5c" to 16, // Super Mariomon v1.2.2 (expansion)
        "43dd208b23eade69aa3384301e934ecafbf6c2b3" to 16, // Super Mariomon v1.2.1 (expansion)
        "ea5d369cc8a31cbf1cfacb7c9470ea670f08957b" to 256, // SoulGold v1.1.4 (its own 1 in 256)
        "805d880ee229fb6dc3ce03d7b03baf48f0d759d0" to 256, // SoulGold v1.2 (its own 1 in 256)
        "5d6a036260fdbde96f85b5c1b92d0256d3aebafb" to 256, // SoulGold v1.2, second build (its own 1 in 256)
        "2be7c99aa473308debe1d03ff2015bab7d9addb5" to 256, // SoulGold v1.2, third build (its own 1 in 256)
        "32988f918ca09923466ae3bc5bbfe5f2a771c728" to 256, // SoulGold v1.2.1 (its own 1 in 256)
        "dacb6e40e7f572ddefd2dfa10e99c0da6835b062" to 256, // SoulGold v1.2.1, second build (its own 1 in 256)
        "40780347d0b4df6bf491eaf12603e5422caee677" to 8, // Team Rocket Edition: Dragonsden Version 1.5
        "2f3e17e55c6afe23da97ec0f96a067eeb142ad15" to 8, // Elite Redux 2.65.4.1b (a shiny bit of its own; its roll's odds unknown)
        "5b38a681b6924ef3e0bb9f6cfbf22426c4c4e523" to 8, // Elite Redux 2.65.3b (a shiny bit of its own; its roll's odds unknown)
        "81bd0f4bfa1c04ab2c6faab1bddd10e8a390ea77" to 8, // R.O.W.E. v2.1.9.1 Experimental (a shiny bit of its own; its roll's odds unknown)
    )

    /** Where the foe party, the player's ID (gSaveBlock2Ptr, or gSaveBlock2 itself: [saveBlock2Static], Ruby / Sapphire;
     * [trainerIdOff] into it, 4 later in Recharged Emerald Rebalanced) and the battle flag are. [plaintext]: CFRU's boxes,
     * the substructs in a fixed order with no encryption or checksum - a new personality is written alone. */
    data class Target(
        val enemyParty: Long, val saveBlock2Ptr: Long, val inBattle: Long, val saveBlock2Static: Boolean = false,
        val trainerIdOff: Long = 0x0A, val plaintext: Boolean = false,
        /** The game's own shiny check (shiny below this shiny value): retail's 8, a hack's own (Glazed 16, Odyssey 255). */
        val shinyBelow: Int = 8,
        /** The species' bits in the box (expansion 1.10 keeps more above them) and whether its ids are National Dex
         * numbers (pokeemerald-expansion's: WURMPLE is 265 there, 290 in Gen 3's own order). */
        val speciesMask: Int = 0xFFFF,
        val national: Boolean = false,
        /** A party slot's size, and in a [plaintext] record: the species' u16, the egg flags (the byte at [flags]: [eggMask],
         * a bad egg [badEggMask]; or bit 30 of the u32 at [eggIvWord], Quetzal's) and the byte whose 0x40 is the newer
         * engine's shinyModifier ([shinyModifier], SoulGold: shiny is the shiny value's check XOR it; 0 = none). An
         * encrypted record's egg flags are read at [flags] too (Team Rocket Edition keeps them a byte early). */
        val stride: Int = MON_STRUCT_SIZE,
        val plainSpecies: Int = 0x20,
        val flags: Int = 0x13,
        val eggMask: Int = 0x04,
        val badEggMask: Int = 0x01,
        val eggIvWord: Int = -1,
        val shinyModifier: Int = 0,
        /** Shininess as bits of its own ([PartyMonLayout.shinyBits], Elite Redux): set [shinyBitSet] in that byte rather
         * than change the personality; any of [shinyBitsMask] there = already shiny. -1 = none. */
        val shinyBits: Int = -1,
        val shinyBitsMask: Int = 0,
        val shinyBitSet: Int = 0,
    )

    /**
     * [cfg]'s target: its gEnemyParty, the save block with the player's ID and gMain's battle flag; null for a struct
     * layout other than vanilla's. FireRed / LeafGreen's configs before English rev 1 don't name gEnemyParty: there it sits
     * right before gPlayerParty ([fireRedFamily]; pokefirered's map, and every language's headless check). The caller
     * checks the ROM is retail, or a hack it knows ([plaintext]: one of [PLAINTEXT_SHA1S]).
     */
    fun targetFor(cfg: NativeConfig?, fireRedFamily: Boolean = false, plaintext: Boolean = false, national: Boolean = false): Target? {
        cfg ?: return null
        val l = cfg.partyMonLayout
        if (l.size != cfg.monStride) return null
        val enemy = cfg.enemyParty.takeIf { it != 0L } ?: if (fireRedFamily) cfg.playerParty - PARTY_SIZE * MON_STRUCT_SIZE else return null
        // Every layout but vanilla's 100 bytes is plaintext (SoulGold, Quetzal, R.O.W.E.'s and Elite Redux's packed ones).
        val plain = plaintext || l.size != MON_STRUCT_SIZE || l.rowe || l.eliteRedux
        val species = when { l.rowe -> 0x1C; l.eliteRedux -> 0x10; else -> l.plainBox }
        val mask = when { l.rowe -> 0xFFF; plain -> l.plainSpeciesMask; else -> cfg.speciesMask }
        // playerTrainerId follows playerGender's byte and the special warp flags'.
        return Target(
            enemy, cfg.saveBlock2Ptr, cfg.gMainInBattle, cfg.staticSaveBlocks, trainerIdOff = cfg.playerGenderOff + 2, plaintext = plain,
            shinyBelow = cfg.shinyBelow.coerceAtLeast(8), speciesMask = mask, national = national,
            stride = cfg.monStride, plainSpecies = species, flags = l.flags, eggMask = if (l.eggInIvWord) 0 else l.eggMask,
            // Vanilla's flags byte: isBadEgg bit 0 next to isEgg's 0x04; the other layouts' have none there.
            badEggMask = if (l.eggMask == 0x04 && !l.eggInIvWord) 0x01 else 0,
            eggIvWord = if (l.eggInIvWord) l.plainBox + 0x28 else -1, shinyModifier = l.shinyModifier,
            // The lower of the bits: bit 26 of +0x14 in Elite Redux.
            shinyBits = l.shinyBits, shinyBitsMask = l.shinyBitsMask, shinyBitSet = l.shinyBitsMask and -l.shinyBitsMask,
        )
    }

    /** The chance a foe that isn't shiny by itself is made shiny, so that all of them come out at 1 in [odds], in a game
     * whose own check is shiny below [shinyBelow] (0 when the game's own odds are as good already). */
    fun rerollChance(odds: Int, shinyBelow: Int = 8): Double {
        if (odds >= NORMAL || odds <= 0) return 0.0
        val base = shinyBelow / 65536.0
        return ((1.0 / odds - base) / (1.0 - base)).coerceIn(0.0, 1.0)
    }

    private const val UNOWN = 201
    private const val WURMPLE = 290   // Gen 3's own species id
    private const val WURMPLE_NATIONAL = 265   // pokeemerald-expansion's
    private const val WURMPLE_FEMALE_BELOW = 127   // its gender ratio: female when the low byte is under it

    // GetUnownLetterByPersonality: the low 2 bits of each byte, highest first.
    private fun unownLetter(p: Long): Long {
        val bits = (((p ushr 24) and 3) shl 6) or (((p ushr 16) and 3) shl 4) or (((p ushr 8) and 3) shl 2) or (p and 3)
        return bits % 28
    }

    private fun wurmpleSilcoon(p: Long): Boolean = (p ushr 16) % 10 <= 4

    /**
     * A personality shiny for [otId] that decides everything [old] did for [species], picked at random among them; null if
     * there's none (never for most species: ~80 of the 2048 tried keep the nature; UNOWN sometimes).
     */
    fun shinyPersonality(old: Long, otId: Long, species: Int, random: Random, national: Boolean = false): Long? {
        val wurmple = if (national) WURMPLE_NATIONAL else WURMPLE
        val tsv = (otId ushr 16) xor (otId and 0xFFFF)
        // The low byte is gender and the ability bit. UNOWN has neither, but its letter takes the low 2 bits of each byte;
        // WURMPLE's branch and nature together can rule out every high half, so any low byte of the same gender goes.
        val oldLow = (old and 0xFF).toInt()
        val lowBytes = when (species) {
            UNOWN -> (0 until 256).filter { it and 3 == oldLow and 3 }
            wurmple -> (0 until 256).filter { it and 1 == oldLow and 1 && (it < WURMPLE_FEMALE_BELOW) == (oldLow < WURMPLE_FEMALE_BELOW) }
            else -> listOf(oldLow)
        }
        val found = ArrayList<Long>()
        for (low in lowBytes) for (mid in 0 until 256) {
            val lo = (mid.toLong() shl 8) or low.toLong()
            for (x in 0 until 8) {
                val p = ((tsv xor lo xor x.toLong()) shl 16) or lo
                if (p % 25 != old % 25) continue
                if (species == UNOWN && unownLetter(p) != unownLetter(old)) continue
                if (species == wurmple && wurmpleSilcoon(p) != wurmpleSilcoon(old)) continue
                found += p
            }
        }
        return if (found.isEmpty()) null else found[random.nextInt(found.size)]
    }

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 4) b[off + i] = (v ushr (8 * i)).toByte()
    }

    /** The hacks among [HACK_SHINY_BELOW] on National Dex species ids (pokeemerald-expansion's, and the own-engine ones). */
    val NATIONAL_SHA1S: Set<String> = setOf(
        "7dcdc7e280bc4631487e13dd37e6e0cea04adea6", // Lazarus v2.0
        "b9f4d332d30fc88c379f9e037f9eae3b2755ead4", // Emerald Seaglass v3.0
        "3fa8e61ec1727cc51540c54677623f736a446e1b", // Too Many Types 2 v1.5.2
        "1d20091c4d936f5eb122db8780554dd0829ffb63", // Emerald Imperium v1.3.1
        "06e79d12afa578db97c23c1f7ba771fe2f8fe905", // Super Mariomon v1.5.2
        "831fe0f97301f17b24a9cd4a64ef0520835c4dde", // Super Mariomon v1.5.1
        "043512c2497871c018df606fb90ffbd4c81430f4", // Super Mariomon v1.3
        "08d1c275c0ba4d559b33427292702d61ad49fb5c", // Super Mariomon v1.2.2
        "43dd208b23eade69aa3384301e934ecafbf6c2b3", // Super Mariomon v1.2.1
        "ea5d369cc8a31cbf1cfacb7c9470ea670f08957b", // SoulGold v1.1.4
        "805d880ee229fb6dc3ce03d7b03baf48f0d759d0", // SoulGold v1.2
        "5d6a036260fdbde96f85b5c1b92d0256d3aebafb", // SoulGold v1.2, second build
        "2be7c99aa473308debe1d03ff2015bab7d9addb5", // SoulGold v1.2, third build
        "32988f918ca09923466ae3bc5bbfe5f2a771c728", // SoulGold v1.2.1
        "dacb6e40e7f572ddefd2dfa10e99c0da6835b062", // SoulGold v1.2.1, second build
        "40780347d0b4df6bf491eaf12603e5422caee677", // Team Rocket Edition: Dragonsden Version 1.5
        "2f3e17e55c6afe23da97ec0f96a067eeb142ad15", // Elite Redux 2.65.4.1b
        "5b38a681b6924ef3e0bb9f6cfbf22426c4c4e523", // Elite Redux 2.65.3b
        "81bd0f4bfa1c04ab2c6faab1bddd10e8a390ea77", // R.O.W.E. v2.1.9.1 Experimental
    )

    /** The CFRU hacks (plaintext boxes) among [HACK_SHINY_BELOW]. */
    val PLAINTEXT_SHA1S: Set<String> = setOf(
        "d5b1e77975fcda831e0e9a7b527906bf3f40ecd0", // Gaia v3.2
        "964f951a0fdaf209e4ea1344883ef0d557bb3a80", // Radical Red v4.1
        "8745ddbdbfadf6abaf66de4e9055923b62eb4668", // Odyssey v4.1.1
        "afe4c8e0f9c7186860fcea5f25d8318f5d7929b9", // Sword and Shield Ultimate Plus v1.2.1.2
        "daa2f89934d2c0447d5271dabeba86bfba04e1fd", // Sword and Shield Ultimate Plus, Casual Patch
        "4858cc3d7b335bb5eed553861d7a4a17bad9ce2c", // Sword and Shield Ultimate Plus, Performance Patch
        "932323d1fa3732d8673d1319fa86ed4df41087b9", // Sword and Shield Ultimate Plus, Casual and Performance Patch
        "00e70c0384a5f1698588034201fd5b849d3542e2", // Amethyst v1.3.0
        "91291aade04b4b111cd03ae7b6e2ff460e1edd8a", // Amethyst v1.4.1
        "b4776b82a4c7915d0fadeaa27e013523f99dfd94", // Unbound v2.1.1.1
        "0ce2a880aa097f1dce4e1db8ee513d0e82d15859", // Unbound v2.1.1.1 FR
        "25f8840ecb58e584edf18ffde35f845e94f3f17c", // GS Chronicles v2.7.6
    )

    private fun inRam(addr: Long) = addr in 0x02000000L until 0x02040000L || addr in 0x03000000L until 0x03008000L

    /** One game's foes, watched frame by frame (emu thread). */
    class Roller(private val target: Target, private val random: Random = Random()) {
        // The personality + OT ID last decided on in each slot: a foe is looked at once, when it first shows up.
        private val seen = LongArray(SLOTS) { -1L }
        // A new foe's record as the last frame left it: the game can be halfway through making it when a frame ends
        // (on the RP6 every new foe read mid-CreateMon, its substructs not yet written), so it's decided on only once the
        // same bytes are there two frames running - the battle transition leaves dozens of frames for that.
        private val settling = arrayOfNulls<ByteArray>(SLOTS)

        /**
         * One frame: a foe new in gEnemyParty since the last call, wild (the player's OT ID), not shiny and not in a battle
         * yet, made shiny with [rerollChance]. Returns the slots changed.
         */
        fun tick(odds: Int, read: (Long, Int) -> ByteArray, write: (Long, ByteArray) -> Boolean): Int {
            var changed = 0
            for (slot in 0 until SLOTS) {
                val at = target.enemyParty + slot * target.stride
                val head = read(at, 8)
                val key = u32(head, 0) or (u32(head, 4) shl 32)
                if (key == seen[slot]) { settling[slot] = null; continue }
                val record = read(at, if (target.plaintext) target.stride else Gen3BoxMon.SIZE)
                if (!record.contentEquals(settling[slot])) { settling[slot] = record; continue }
                settling[slot] = null
                seen[slot] = key
                val chance = rerollChance(odds, target.shinyBelow)
                if (chance <= 0.0) continue
                if (read(target.inBattle, 1)[0].toInt() and 2 != 0) continue
                val save2 = if (target.saveBlock2Static) target.saveBlock2Ptr else u32(read(target.saveBlock2Ptr, 4), 0)
                if (!inRam(save2)) continue
                val playerOt = u32(read(save2 + target.trainerIdOff, 4), 0)
                val personality = u32(head, 0)
                val otId = u32(head, 4)
                if (otId != playerOt || personality == 0L) continue
                val species: Int
                val rewrite: (Long) -> ByteArray
                val flags = record[target.flags].toInt()
                if (flags and (target.eggMask or target.badEggMask) != 0) continue
                if (target.plaintext) {
                    // No encryption or checksum (CFRU's boxes, SoulGold, Quetzal, the packed ones): the personality alone.
                    species = ((record[target.plainSpecies].toInt() and 0xFF) or ((record[target.plainSpecies + 1].toInt() and 0xFF) shl 8)) and
                        target.speciesMask
                    if (species == 0) continue
                    if (target.eggIvWord >= 0 && (u32(record, target.eggIvWord) shr 30) and 1L != 0L) continue
                    rewrite = { p -> ByteArray(4).also { putU32(it, 0, p) } }
                } else {
                    val mon = Gen3BoxMon.read(record, 0)
                    if (!mon.checksumOk || mon.isEgg || mon.species == 0) continue
                    species = mon.species and target.speciesMask
                    rewrite = mon::withPersonality
                }
                if (target.shinyBits >= 0) {
                    // Its own shiny bits: set one, the personality untouched.
                    val bits = record[target.shinyBits].toInt()
                    if (bits and target.shinyBitsMask != 0) continue
                    if (random.nextDouble() >= chance) continue
                    if (write(at + target.shinyBits, byteArrayOf((bits or target.shinyBitSet).toByte()))) changed++
                    continue
                }
                // shinyModifier set flips the check: a foe with it is left be either way.
                if (target.shinyModifier > 0 && record[target.shinyModifier].toInt() and 0x40 != 0) continue
                if (shinyValue(personality, otId) < target.shinyBelow) continue
                if (random.nextDouble() >= chance) continue
                val shiny = shinyPersonality(personality, otId, species, random, target.national) ?: continue
                if (write(at, rewrite(shiny))) {
                    seen[slot] = shiny or (otId shl 32)
                    changed++
                }
            }
            return changed
        }

        private companion object {
            // A wild battle's foe is slot 0; a double's second foe slot 1.
            const val SLOTS = 2
        }
    }
}
