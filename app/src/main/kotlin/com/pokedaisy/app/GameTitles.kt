package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.companion.data.EMERALD_LOCALIZED_CODES
import com.pokedaisy.app.companion.data.RETAIL_PORT_CODE_TITLES
import com.pokedaisy.app.companion.data.RETAIL_PORT_TITLES
import com.pokedaisy.app.companion.data.TelemetrySampler
import java.io.File

/**
 * The name a game goes by in the library ("Pokémon FireRed") instead of its ROM
 * file's ("Pokemon - FireRed Version (USA, Europe) (Rev 1)") - the file name stays
 * in the list row's second line and INFO. A ROM is named by its whole-file SHA1
 * (an archive by the ROM inside, [RomIdentity]): retail by the pret decomps'
 * `*.sha1`, hacks by the hashes [com.pokedaisy.app.companion.data.TelemetrySampler]
 * detects them with. Anything else (hacks it doesn't know, other games) keeps
 * its file name, tidied by [tidy].
 *
 * Hashing is up to 32 MB per ROM (128 MB a DS cart), so [identify] runs off the UI thread and caches
 * each file's answer by path + size + modified time (`rom-titles.tsv`); [label]
 * only reads that cache. Order: the player's own RENAME, then this, then the file.
 */
object GameTitles {

    val BY_SHA1: Map<String, String> by lazy { RETAIL + HACKS + RETAIL_PORT_TITLES }

    /** A retail cart's SHA-1 (any language and revision), not a hack's. */
    fun isRetail(sha1: String): Boolean = sha1 in RETAIL || sha1 in RETAIL_PORT_TITLES

    /** Retail carts (the library's GBA / GB / GBC tag when a game has no cover: [RomCategory]). */
    /** The DS carts [BY_SHA1] names (the website's ROM check reads DS carts by their header: compat.json's `ds`). */
    internal val NDS_SHA1S = setOf(
        "0862ec35b24de5c7e2dcb88c9eea0873110d755c", "4fcded0e2713dc03929845de631d0932ea2b5a37", "f8dc38ea20c17541a43b58c5e6d18c1732c7e582",
        "13b6853aa93121069df1d17446d3ecb36872aecf", "576cdb7eec728c49aed85b9dd7e1e084ba1f08f9",
    )

    private val RETAIL: Map<String, String> = mapOf(
        // Retail (USA, Europe), every revision: pret's pokefirered / pokeemerald / pokeruby.
        "41cb23d8dccc8ebd7c649cd8fbb58eeace6e2fdc" to "Pokémon FireRed",
        "dd5945db9b930750cb39d00c84da8571feebf417" to "Pokémon FireRed",
        "574fa542ffebb14be69902d1d36f1ec0a4afd71e" to "Pokémon LeafGreen",
        "7862c67bdecbe21d1d69ce082ce34327e1c6ed5e" to "Pokémon LeafGreen",
        "f3ae088181bf583e55daf962a92bb46f4f1d07b7" to "Pokémon Emerald",
        // Nintendo DS (USA): pret's pokeplatinum (rev 1) / pokeheartgold (both games).
        "0862ec35b24de5c7e2dcb88c9eea0873110d755c" to "Pokémon Platinum",
        "4fcded0e2713dc03929845de631d0932ea2b5a37" to "Pokémon HeartGold",
        "f8dc38ea20c17541a43b58c5e6d18c1732c7e582" to "Pokémon SoulSilver",
        "13b6853aa93121069df1d17446d3ecb36872aecf" to "Pokémon Goldene Edition HeartGold",
        "576cdb7eec728c49aed85b9dd7e1e084ba1f08f9" to "Pokémon Silberne Edition SoulSilver",
        // Black (USA, Europe): the No-Intro dump (no decomp builds it).
        "26ad0b9967aa279c4a266ee69f52b9b2332399a5" to "Pokémon Black",
        // The other Emeralds, by their own titles (No-Intro's dumps; no decomp builds them).
        "fe1558a3dcb0360ab558969e09b690888b846dd9" to "Pokémon Edición Esmeralda",
        "61c2eb2b380b1a75f0c94b767a2d4c26cd7ce4e3" to "Pokémon Smaragd-Edition",
        "ca666651374d89ca439007bed54d839eb7bd14d0" to "Pokémon Version Émeraude",
        "1692db322400c3141c5de2db38469913ceb1f4d4" to "Pokémon Versione Smeraldo",
        "d7cf8f156ba9c455d164e1ea780a6bf1945465c2" to "ポケットモンスター エメラルド",
        "f28b6ffc97847e94a6c21a63cacf633ee5c8df1e" to "Pokémon Ruby",
        "610b96a9c9a7d03d2bafb655e7560ccff1a6d894" to "Pokémon Ruby",
        "5b64eacf892920518db4ec664e62a086dd5f5bc8" to "Pokémon Ruby",
        "3ccbbd45f8553c36463f13b938e833f652b793e4" to "Pokémon Sapphire",
        "4722efb8cd45772ca32555b98fd3b9719f8e60a9" to "Pokémon Sapphire",
        "89b45fb172e6b55d51fc0e61989775187f6fe63c" to "Pokémon Sapphire",
        // Game Boy (pret/pokeyellow's roms.sha1).
        "cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1" to "Pokémon Yellow",
        "42f3714eec6eca25200d42461ff08d57c98f6d1d" to "Pokémon Gelbe Edition",
        "0aceec0ef7aa2ca5aa831554598d91f61a925591" to "Pokémon Version Jaune",
        "05bb8e99f24d498613930949730afa8024e77d08" to "Pokémon Versione Gialla",
        "1dc242039218fba50928d1afb66b70565b6b9daf" to "Pokémon Edición Amarilla",
        // pret/pokered's roms.sha1 (Blue: the build stands in for the cart).
        "ea9bcae617fdf159b045185467ae58b2e4a48b9a" to "Pokémon Red",
        "d7037c83e1ae5b39bde3c30787637ba1d4c48ce2" to "Pokémon Blue",
        // Game Boy Color (pret/pokecrystal's roms.sha1): rev 0, rev 1, Australia.
        "f4cd194bdee0d04ca4eac29e09b8e4e9d818c133" to "Pokémon Crystal",
        "f2f52230b536214ef7c9924f483392993e226cfb" to "Pokémon Crystal",
        "a0fc810f1d4e124434f7be2c989ab5b5892ddf36" to "Pokémon Crystal",
        // pret/pokegold's roms.sha1.
        "d8b8a3600a465308c9953dfa04f0081c05bdcb94" to "Pokémon Gold",
        "49b163f7e57702bc939d642a18f591de55d92dae" to "Pokémon Silver",
    )

    /** Hacks (and the Game Boy ones): the library's HACK tag. */
    private val HACKS: Map<String, String> = mapOf(
        // Hacks - the versions Poller.kt's *_SHA1 constants pin.
        "b4776b82a4c7915d0fadeaa27e013523f99dfd94" to "Pokémon Unbound",
        "d5b1e77975fcda831e0e9a7b527906bf3f40ecd0" to "Pokémon Gaia",
        "964f951a0fdaf209e4ea1344883ef0d557bb3a80" to "Pokémon Radical Red",
        "8745ddbdbfadf6abaf66de4e9055923b62eb4668" to "Pokémon Odyssey",
        "00e70c0384a5f1698588034201fd5b849d3542e2" to "Pokémon Amethyst",
        "91291aade04b4b111cd03ae7b6e2ff460e1edd8a" to "Pokémon Amethyst",
        "3cbd5a2e72ce60cfae1e0c28d83e01f855c51537" to "Pokémon Celia's Stupid Romhack",
        "79ee6df0869c1773c8c6a5f764afc1f8d833d8bb" to "Pokémon Heart & Soul",
        "7dcdc7e280bc4631487e13dd37e6e0cea04adea6" to "Pokémon Lazarus",
        "81bd0f4bfa1c04ab2c6faab1bddd10e8a390ea77" to "Pokémon R.O.W.E.",
        "7600af1fe08444c850c3c1227fd7dfd81336ae8e" to "Pokémon Emerald Rogue",
        "3fa8e61ec1727cc51540c54677623f736a446e1b" to "Pokémon Too Many Types 2",
        "b9f4d332d30fc88c379f9e037f9eae3b2755ead4" to "Pokémon Emerald Seaglass",
        "ea5d369cc8a31cbf1cfacb7c9470ea670f08957b" to "Pokémon SoulGold",
        "805d880ee229fb6dc3ce03d7b03baf48f0d759d0" to "Pokémon SoulGold",
        "5d6a036260fdbde96f85b5c1b92d0256d3aebafb" to "Pokémon SoulGold",
        "2be7c99aa473308debe1d03ff2015bab7d9addb5" to "Pokémon SoulGold",
        "e10105d8544469c6a11ca2cf510289981df3c3b0" to "Pokémon Glazed",
        "a7c4e34fafb53f2d5283eb6d43dad285c0dd40a8" to "Pokémon Emerald Kaizo",
        "cbd71af65ee3dcd53280a6a12014bfb11e8f626a" to "Pokémon Emerald Legacy",
        "1d20091c4d936f5eb122db8780554dd0829ffb63" to "Pokémon Emerald Imperium",
        "d0658315da1e8827f66f15c3d3a000fe747e163e" to "Pokémon Quetzal",
        "fe346b5b0eb022e3a103f81e7dcefba6f1dc542f" to "Pokémon Quetzal",
        "c108cec750a35775084c0806cc704d8abd1994e7" to "Pokémon Quetzal",
        "ff008b70b6f2b330da9ffa8a405fc951a9e54147" to "Pokémon Quetzal",
        "6806c7146aff457a4be85ef2030e9ddcdb17b665" to "Pokémon Quetzal",
        "b6542c7dbb40cf6ea96714a7a0e30cb468102861" to "Pokémon Quetzal",
        "f1035f615c13c46394a18fa99181c24c3cd15e12" to "Moemon Quetzal",
        "ae5d88bb823a8f9d0d07e8784da2376ff7c1ae43" to "Moemon Quetzal",
        "0ce2a880aa097f1dce4e1db8ee513d0e82d15859" to "Pokémon Unbound",
        "8bac897de515f88e6ce92d0712ce2c018a394b37" to "Pokémon Orange Islands",
        "d2e3800e69e44c9649c3d9a15dc1cd12c1d90501" to "Pokémon Orange Islands",
        "3b6c51d8fb6b513bdd01b7bda3ed8965bc985d58" to "Pokémon Ultra Violet",
        "033d05374ae34eb84fae62c693ca8f3da23dd1bd" to "Pokémon FireRed Rocket Edition",
        "1ee1557db1e4c2e0e94c6306efcc40506bc40cc9" to "Pokémon Ash Gray",
        "92ed82145c013069472153391c7aab1cfbf37b75" to "Pokémon Ash Gray Fork Bug Fix",
        "5b38a681b6924ef3e0bb9f6cfbf22426c4c4e523" to "Pokémon Elite Redux",
        "2f3e17e55c6afe23da97ec0f96a067eeb142ad15" to "Pokémon Elite Redux",
        "40780347d0b4df6bf491eaf12603e5422caee677" to "Pokémon Team Rocket Edition: Dragonsden Version",
        "60182b934d2e8eb3eef226a9bbc25e5df6becd4f" to "Pokémon Wishes of Tomorrow",
        "20e91aecb3243eea7cd610659846f37062f5d709" to "Pokémon Dreamstone Mysteries",
        "20dfaea1d5317cf95fe0b493208edce7d50b865f" to "Pokémon Ghost Grey",
        "74337272566a74625d6dedefe47d2d153eb48ec1" to "Pokémon Ghost Grey",
        "0dafc0116b74406a2df491e26ccb40dd362fb0b7" to "Pokémon Ghost Grey",
        "afe4c8e0f9c7186860fcea5f25d8318f5d7929b9" to "Pokémon Sword and Shield Ultimate Plus",
        "daa2f89934d2c0447d5271dabeba86bfba04e1fd" to "Pokémon Sword and Shield Ultimate Plus",
        "4858cc3d7b335bb5eed553861d7a4a17bad9ce2c" to "Pokémon Sword and Shield Ultimate Plus",
        "932323d1fa3732d8673d1319fa86ed4df41087b9" to "Pokémon Sword and Shield Ultimate Plus",
        "be5033598e91d0e80fa93e551406f02a7876d7c2" to "Pokémon Recharged Yellow",
        "ac30d1ed80e75104a3fb92f71e2b9a51c26e80c8" to "Pokémon Recharged Yellow",
        "7d9fae6cf31c07bc7fa5cf7afe137ad5385cefb1" to "Pokémon Recharged Yellow",
        "ed60654d7525ba005bfc8f799bf0ecba2ef08a2e" to "Pokémon Recharged Yellow",
        "67f44fb4cdbb16c0d1a495ac219f2979b1e3005f" to "Pokémon Recharged Emerald",
        "56c9777215f5be627f7c3099e68985aefc10f2b7" to "Pokémon Recharged Emerald",
        "08b9c7903ae4c4e26d80ef945a90c004ae1e0ebb" to "Pokémon Recharged Emerald Rebalanced",
        "43dd208b23eade69aa3384301e934ecafbf6c2b3" to "Super Mariomon",
        "08d1c275c0ba4d559b33427292702d61ad49fb5c" to "Super Mariomon",
        "043512c2497871c018df606fb90ffbd4c81430f4" to "Super Mariomon",
        "831fe0f97301f17b24a9cd4a64ef0520835c4dde" to "Super Mariomon",
        "06e79d12afa578db97c23c1f7ba771fe2f8fe905" to "Super Mariomon",
        "25f8840ecb58e584edf18ffde35f845e94f3f17c" to "Pokémon GS Chronicles",
        // Crystal Clear: v2.5.11 and v2.6.0, each in Standard Colors and Color Filter.
        "a1c4da6b0057c98fe4ebea45d6812a484fc69abd" to "Pokémon Crystal Clear",
        "6fc677bf7a9db103cb9ee11ac08d26782e69ae41" to "Pokémon Crystal Clear",
        "84d5c4924c3f1f3fc690669148b7e392948fb23d" to "Pokémon Crystal Clear",
        "534876e4e4d0d79a82fef3bbe5bea11c40451727" to "Pokémon Crystal Clear",
        // Prism v0.95 build 254 (Hotfix 5).
        "752076692ae3387cf426ce5f51a98c6b60e8df6a" to "Pokémon Prism",
        // Polished Crystal 3.2.0 - 3.2.3: every official release build (normal, faithful, their debug builds).
        "6930b48af5844d373e3c9130f26d6dd1084cf4ed" to "Pokémon Polished Crystal",
        "beb8b38280af59e9ef84be12684a6ecf09021c9b" to "Pokémon Polished Crystal",
        "79ebf9984eac8081f488d15a35a6c5d11b4f84e2" to "Pokémon Polished Crystal",
        "e55859484c5cda70e05daed59b14662e0a0f8eac" to "Pokémon Polished Crystal",
        "457d61dfc28a17388b87792595433ba20bd1c1ed" to "Pokémon Polished Crystal",
        "bea9804dc88a4ee370af62ff3139fdf1380277bf" to "Pokémon Polished Crystal",
        "9c13585eb34efdcd881c2e68f029b820bddf349d" to "Pokémon Polished Crystal",
        "11d461629363402fc15b5005ef51b8f44e726140" to "Pokémon Polished Crystal",
        "3ff091a8e94a0c031372b6f0275e12620fe3d766" to "Pokémon Polished Crystal",
        "e63b1e52a9f71c3948e3e7e515e9939d728fc679" to "Pokémon Polished Crystal",
        "666f8edfb9efd8c301adbc14d38ccfaae33a047d" to "Pokémon Polished Crystal",
        "b012f6d55c34c972778f5566e1cc910962dd7ab5" to "Pokémon Polished Crystal",
        "b5393eb27fec51970cc9186f36c34e94494bfd39" to "Pokémon Polished Crystal",
        "772858dd5a3e66dea4454d5f83b48c83d732ca69" to "Pokémon Polished Crystal",
        "c4bd263e58325c6c04c34462c1b4a37fba085e9c" to "Pokémon Polished Crystal",
        "c0a2c70d73ce68830a891ddd292300c90766acdd" to "Pokémon Polished Crystal",
        // PureRed / PureBlue v2.7.6 (Vortyne/pureRGB builds both byte for byte).
        "2e94d09c1e16a57eb079404c030f26fdeeac949d" to "Pokémon PureRed",
        "d419fe244fa17196f7df46ddab47c840e7573652" to "Pokémon PureBlue",
    )

    /** DS carts worth hashing (128 MB each, once per file): the DS games above. */
    private val NDS_CODES = setOf("CPUE", "IPKE", "IPKD", "IPGE", "IPGD", "IRBO")

    /** Only these games' ROMs (and Game Boy carts) are worth hashing: every entry above is one of them. */
    private val CODES = setOf("BPRE", "BPGE", "BPEE", "AXVE", "AXPE") + EMERALD_LOCALIZED_CODES +
        RETAIL_PORT_CODE_TITLES.keys + TelemetrySampler.HACK_GAME_CODES

    /** What the library shows for [rom]: the player's RENAME, else [defaultLabel]. Cheap. */
    fun label(context: Context, prefs: Prefs, rom: File): String =
        prefs.romDisplayName(rom) ?: defaultLabel(context, rom)

    /** [rom]'s name with no RENAME: the game's ([title]) if known, else the tidied file name. */
    fun defaultLabel(context: Context, rom: File): String =
        title(context, rom) ?: tidy(RomArchive.baseName(rom))

    /** The game's name, if [identify] has already recognised this exact file. Cheap. */
    fun title(context: Context, rom: File): String? =
        cache(context)[rom.absolutePath]?.takeIf { it.matches(rom) }?.title

    /** What kind of game [rom] is, if [identify] has looked at this exact file. Cheap. */
    fun category(context: Context, rom: File): RomCategory? =
        cache(context)[rom.absolutePath]?.takeIf { it.matches(rom) }?.category

    /** Which console [rom] runs on (GB / GBC / GBA / NDS, never HACK: a hack is its base game's), if [identify] has
     * looked at this exact file. Cheap. The library's quick views filter on it. */
    fun platform(context: Context, rom: File): RomCategory? =
        cache(context)[rom.absolutePath]?.takeIf { it.matches(rom) }?.platform

    /** [rom]'s console from its header: [categoryOf] without the hack check. */
    internal fun platformOf(rom: File, sha1: () -> String?): RomCategory = when {
        RomIdentity.isNds(rom) -> RomCategory.NDS
        RomIdentity.isGameBoy(rom) -> gameBoyPlatform(rom, sha1)
        else -> RomCategory.GBA
    }

    /**
     * GB or GBC. The header's 0x143 says Color-only (0xC0: Crystal) or not at all (0x00: Red / Blue), but a dual-mode cart
     * (0x80) can be either: Yellow is a Game Boy game with colours added, Gold / Silver are Color games that also run on
     * the Game Boy (GitHub #271), and Polished Crystal sets 0x80 but only runs on a Color (#237). Known carts go by
     * [sha1] ([COLOR_DUAL]); any other dual-mode cart by its file's extension, as the dump sets name them (No-Intro's
     * "(GB Compatible).gbc" vs "(CGB+SGB Enhanced).gb").
     */
    private fun gameBoyPlatform(rom: File, sha1: () -> String?): RomCategory {
        val flag = RomArchive.readAt(rom, 0x143L, 1)?.firstOrNull()?.toInt()?.and(0xFF)
        return when {
            flag == 0xC0 -> RomCategory.GBC
            flag != 0x80 -> RomCategory.GB
            sha1().let { it in COLOR_DUAL || it !in TelemetrySampler.SUPPORTED_GB_SHA1S && romExtension(rom) == "gbc" } ->
                RomCategory.GBC
            else -> RomCategory.GB
        }
    }

    /** [rom]'s extension, or its ROM's inside an archive. */
    private fun romExtension(rom: File): String =
        (if (RomArchive.isArchive(rom)) RomArchive.romEntry(rom)?.name?.substringAfterLast('/') else rom.name)
            ?.substringAfterLast('.', "")?.lowercase().orEmpty()

    /** Color games with a dual-mode header (0x80) that the companion knows. Yellow says the same but is a GB game. */
    private val COLOR_DUAL: Set<String>
        get() = TelemetrySampler.POLISHED_CRYSTAL_SHA1S + TelemetrySampler.GOLD_SHA1 + TelemetrySampler.SILVER_SHA1

    /** [rom]'s [RomCategory] (reads its header; [sha1] when [identify] hashed it). A Pokémon cart hashed but known as
     * neither retail nor a hack is a hack too (an unsupported one); a cart nobody hashes is its platform's. */
    private fun categoryOf(rom: File, sha1: String?, hashed: Boolean): RomCategory = when {
        RomIdentity.isNds(rom) -> RomCategory.NDS
        sha1 != null && sha1 in HACKS -> RomCategory.HACK
        hashed && sha1 != null && sha1 !in RETAIL && sha1 !in RETAIL_PORT_TITLES -> RomCategory.HACK
        RomIdentity.isGameBoy(rom) -> gameBoyPlatform(rom) { sha1 }
        else -> RomCategory.GBA
    }

    /**
     * Recognises every ROM in [roms] not already cached (or changed since). Blocking
     * (hashes) - never on the UI thread. True when a new answer was cached, i.e. a
     * name may have changed and the list should redraw.
     *
     * The hashing (up to 512 MB a DS cart) runs outside the cache's lock, which the UI thread's
     * [label] / [category] / [platform] take: a pass holds it only to copy the cache and to put
     * each answer in. Passes run one at a time ([passLock]), so no file is hashed twice and the
     * cache file has one writer.
     */
    fun identify(context: Context, roms: List<File>): Boolean = synchronized(passLock) {
        val snapshot = synchronized(this) { HashMap(cache(context)) }
        var changed = false
        for (rom in roms) {
            val known = snapshot[rom.absolutePath]?.takeIf { it.matches(rom) && it.category != null }
            if (!rom.isFile || known?.platform != null) continue
            // Named before platforms were kept: only the header to read, no hash.
            val entry = if (known != null) {
                Entry(known.size, known.modified, known.title, known.category, platformOf(rom) { RomIdentity.sha1(rom) })
            } else {
                val nds = RomIdentity.isNds(rom)
                val worthHashing = if (nds) RomIdentity.ndsGameCode(rom) in NDS_CODES
                    else RomIdentity.gameCode(rom) in CODES || RomIdentity.isGameBoy(rom)
                val sha1 = if (worthHashing) RomIdentity.sha1(rom) else null
                val title = sha1?.let { BY_SHA1[it] }
                Entry(rom.length(), rom.lastModified(), title, categoryOf(rom, sha1, worthHashing), platformOf(rom) { sha1 })
            }
            synchronized(this) { cache(context)[rom.absolutePath] = entry }
            changed = true
        }
        // Forget files that are gone, so the cache doesn't grow forever.
        val gone = snapshot.keys.filter { !File(it).isFile }
        val pruned = gone.isNotEmpty() && synchronized(this) { cache(context).keys.removeAll(gone.toSet()) }
        if (changed || pruned) save(context, synchronized(this) { HashMap(cache(context)) })
        changed
    }

    /** Held for a whole [identify] pass (never by the UI thread's lookups). */
    private val passLock = Any()

    private val TAGS = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val NUMBERING = Regex("""^\d{1,5}\s*-\s+""")

    /**
     * A file name without its dump-set tags: "(USA, Europe)", "(Rev 1)", "(v1.3.1)",
     * "[!]", a leading "1636 - " release number, underscores for spaces. The whole
     * name back when nothing would be left.
     */
    fun tidy(name: String): String =
        name.replace('_', ' ')
            .replace(TAGS, "")
            .replace(NUMBERING, "")
            .replace(Regex("""\s+"""), " ")
            .trim().trimEnd('-', ' ')
            .ifEmpty { name }

    private class Entry(
        val size: Long, val modified: Long, val title: String?, val category: RomCategory?,
        val platform: RomCategory? = category?.takeIf { it != RomCategory.HACK },
    ) {
        fun matches(rom: File) = size == rom.length() && modified == rom.lastModified()
    }

    private var loaded: MutableMap<String, Entry>? = null

    /** Read the file again (ui-preview writes one per shot). */
    @Synchronized internal fun reload() { loaded = null }

    @Synchronized
    private fun cache(context: Context): MutableMap<String, Entry> = loaded ?: load(context).also { loaded = it }

    private fun cacheFile(context: Context) = File(context.filesDir, "rom-titles.tsv")

    /** Which games [BY_SHA1] knew when the cache was written: a file found nameless then may have a name now. */
    private val KNOWN = "#known\t" + Integer.toHexString(BY_SHA1.keys.sorted().hashCode())

    /** How [RomCategory] was decided when the cache was written: a cache from before asks every file again. */
    private const val CATEGORIES = "#categories\t4"
    /** The ones before: only their GB games are asked again (Polished Crystal is GBC since 3, Gold / Silver since 4). */
    private val CATEGORIES_GB_STALE = setOf("#categories\t2", "#categories\t3")

    private fun load(context: Context): MutableMap<String, Entry> {
        val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()
        val f = cacheFile(context)
        if (!f.isFile) return map
        runCatching {
            var stale = true
            var categoriesStale = true
            var gbStale = false
            f.forEachLine { line ->
                if (line == KNOWN) stale = false
                if (line == CATEGORIES) categoriesStale = false
                if (line in CATEGORIES_GB_STALE) { categoriesStale = false; gbStale = true }
                val p = line.split('\t')
                // A 4-column line is from before categories: no category, so identify() reads it again. A 5-column
                // one from before platforms: a hack's is read again (the others' platform is their category).
                if (p.size !in 4..6) return@forEachLine
                val size = p[1].toLongOrNull() ?: return@forEachLine
                val modified = p[2].toLongOrNull() ?: return@forEachLine
                val category = p.getOrNull(4)?.let { c -> RomCategory.entries.firstOrNull { it.name == c } }
                val platform = p.getOrNull(5)?.let { c -> RomCategory.entries.firstOrNull { it.name == c } }
                map[p[0]] = if (platform != null) Entry(size, modified, p[3].ifEmpty { null }, category, platform)
                    else Entry(size, modified, p[3].ifEmpty { null }, category)
            }
            // Written before a game was added (an app update): ask the nameless files again.
            if (stale) map.values.removeAll { it.title == null }
            // Categories decided another way (Yellow was GBC once): every file's is asked again.
            if (categoriesStale) map.replaceAll { _, e -> Entry(e.size, e.modified, e.title, null, null) }
            // A GB game from before these Color games were known: identify() reads it again (a retail one's category
            // is its platform, so both go).
            else if (gbStale) map.replaceAll { _, e ->
                if (e.platform == RomCategory.GB) Entry(e.size, e.modified, e.title, e.category.takeIf { it == RomCategory.HACK }, null) else e
            }
        }
        return map
    }

    private fun save(context: Context, entries: Map<String, Entry>) {
        val f = cacheFile(context)
        val tmp = File(f.parentFile, "${f.name}.tmp")
        runCatching {
            tmp.writeText(KNOWN + "\n" + CATEGORIES + "\n" + entries.entries.joinToString("") { (path, e) ->
                "$path\t${e.size}\t${e.modified}\t${e.title.orEmpty()}\t${e.category?.name.orEmpty()}\t${e.platform?.name.orEmpty()}\n"
            })
            tmp.renameTo(f)
        }
    }
}

/** What kind of game a ROM is, for the library's tag when it has no cover art: its platform, or a hack. */
enum class RomCategory(val label: String) { GB("GB"), GBC("GBC"), GBA("GBA"), NDS("NDS"), HACK("HACK") }
