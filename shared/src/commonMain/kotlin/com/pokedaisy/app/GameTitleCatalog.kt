package com.pokedaisy.app

import com.pokedaisy.app.companion.data.EMERALD_LOCALIZED_CODES
import com.pokedaisy.app.companion.data.RETAIL_PORT_CODE_TITLES
import com.pokedaisy.app.companion.data.RETAIL_PORT_TITLES
import com.pokedaisy.app.companion.data.TelemetrySampler
import com.pokedaisy.app.companion.i18n.tr

/** Game names from known ROM identities and the labels shown when the host has only a filename. */
object GameTitleCatalog {
    val BY_SHA1: Map<String, String> by lazy { RETAIL + HACKS + RETAIL_PORT_TITLES + NDS_PORT_TITLES }

    /** The DS language ports' own titles ([com.pokedaisy.app.companion.data.NDS_PORTS]). */
    private val NDS_PORT_TITLES: Map<String, String> by lazy {
        com.pokedaisy.app.companion.data.NDS_PORTS.associate { it.sha1 to it.title }
    }

    /** A retail cart's SHA-1 (any language and revision), not a hack's. */
    fun isRetail(sha1: String): Boolean = sha1 in RETAIL || sha1 in RETAIL_PORT_TITLES

    /** The DS carts [BY_SHA1] names (the website's ROM check reads DS carts by their header: compat.json's `ds`). */
    val NDS_SHA1S = setOf(
        "0862ec35b24de5c7e2dcb88c9eea0873110d755c", "4fcded0e2713dc03929845de631d0932ea2b5a37", "f8dc38ea20c17541a43b58c5e6d18c1732c7e582",
        "13b6853aa93121069df1d17446d3ecb36872aecf", "576cdb7eec728c49aed85b9dd7e1e084ba1f08f9",
        "62f9ec7a412d06bd518f7dc065e4612812e327b9", "dba64620a959d5f69ed1cf3e43fcfcf2786d417e",
        "6ed98d446eb65861c71fc5f94bdc35691667f665", "a559da54839663d8f8f3f03382e5665a61873d22",
        "28f075915dd445cbfd902f1e1a94d5048b03a095", "e5db5b8f79e8ae819be7d60e9e1006221d80ffab",
        com.pokedaisy.app.companion.data.OriginHeartGold.V4_0_3_EN_RC8_SHA1,
        com.pokedaisy.app.companion.data.RenegadePlatinum.V1_3_0_SHA1,
        com.pokedaisy.app.companion.data.RenegadePlatinum.V1_2_0_SHA1,
    ) + com.pokedaisy.app.companion.data.RenegadePlatinum.V1_3_0_PATCH_SHA1S + com.pokedaisy.app.companion.data.HeartfeltGold.SHA1S +
        com.pokedaisy.app.companion.data.SacredGold.SACRED_GOLD_SHA1S + com.pokedaisy.app.companion.data.SacredGold.STORM_SILVER_SHA1S +
        com.pokedaisy.app.companion.data.Black2Redux.BLAZE_BLACK2_SHA1S + com.pokedaisy.app.companion.data.Black2Redux.VOLT_WHITE2_SHA1S +
        com.pokedaisy.app.companion.data.NDS_PORTS.map { it.sha1 }

    private val RETAIL: Map<String, String> = mapOf(
        // Nintendo DS (USA): pret's pokeplatinum (rev 1) / pokeheartgold (both games).
        "0862ec35b24de5c7e2dcb88c9eea0873110d755c" to "Pokémon Platinum",
        "4fcded0e2713dc03929845de631d0932ea2b5a37" to "Pokémon HeartGold",
        "f8dc38ea20c17541a43b58c5e6d18c1732c7e582" to "Pokémon SoulSilver",
        // German HeartGold / SoulSilver, rev 0 (the contributor's dumps; GitHub #349).
        "13b6853aa93121069df1d17446d3ecb36872aecf" to "Pokémon Goldene Edition HeartGold",
        "576cdb7eec728c49aed85b9dd7e1e084ba1f08f9" to "Pokémon Silberne Edition SoulSilver",
        "62f9ec7a412d06bd518f7dc065e4612812e327b9" to "Pokémon Version Platine",
        "dba64620a959d5f69ed1cf3e43fcfcf2786d417e" to "Pokémon Version Or HeartGold",
        "6ed98d446eb65861c71fc5f94bdc35691667f665" to "Pokémon Version Argent SoulSilver",
        "a559da54839663d8f8f3f03382e5665a61873d22" to "Pokémon Version Noire",
        "28f075915dd445cbfd902f1e1a94d5048b03a095" to "Pokémon Version Blanche",
        // Origin HeartGold v4.0.3, the English patch 1.0.0-rc8 on the US HeartGold (OriginHeartGold).
        com.pokedaisy.app.companion.data.OriginHeartGold.V4_0_3_EN_RC8_SHA1 to "Pokémon Origin HeartGold",
        // Renegade Platinum v1.3.0, retail Platinum (USA rev 1) edited in place (RenegadePlatinum).
        com.pokedaisy.app.companion.data.RenegadePlatinum.V1_3_0_SHA1 to "Pokémon Renegade Platinum",
        com.pokedaisy.app.companion.data.RenegadePlatinum.V1_2_0_SHA1 to "Pokémon Renegade Platinum",
    ) + com.pokedaisy.app.companion.data.RETAIL_LOOK_TITLES + com.pokedaisy.app.companion.data.RenegadePlatinum.V1_3_0_PATCH_SHA1S.associateWith { "Pokémon Renegade Platinum" } +
        // Heartfelt Gold v1.0.15 (and its Zoomed In patch): hg-engine over the US HeartGold (HeartfeltGold).
        com.pokedaisy.app.companion.data.HeartfeltGold.SHA1S.associateWith { "Pokémon Heartfelt Gold" } +
        // Sacred Gold / Storm Silver v1.05 (Complete, and Sacred Gold's Classic): Drayano's, edited in place (SacredGold).
        com.pokedaisy.app.companion.data.SacredGold.SACRED_GOLD_SHA1S.associateWith { "Pokémon Sacred Gold" } +
        com.pokedaisy.app.companion.data.SacredGold.STORM_SILVER_SHA1S.associateWith { "Pokémon Storm Silver" } +
        // Blaze Black 2 / Volt White 2 Redux v1.4.1 (and their Classic / EVless patches), Black 2 / White 2 edited in place (Black2Redux).
        com.pokedaisy.app.companion.data.Black2Redux.BLAZE_BLACK2_SHA1S.associateWith { "Pokémon Blaze Black 2 Redux" } +
        com.pokedaisy.app.companion.data.Black2Redux.VOLT_WHITE2_SHA1S.associateWith { "Pokémon Volt White 2 Redux" } + mapOf(
        // Black and White (USA, Europe): the No-Intro dumps (no decomp builds them).
        "26ad0b9967aa279c4a266ee69f52b9b2332399a5" to "Pokémon Black",
        "bc696a0dfb448c7b3a8a206f0f8214411a039208" to "Pokémon White",
        // Black 2 and White 2 (USA, Europe): the No-Intro dumps.
        "e51e6dfb8678a3d19dcd2a10691b96a569ca0abb" to "Pokémon Black 2",
        "b5d7490be7b415b8f1e672a53e978a9cc667e56a" to "Pokémon White 2",
        // Japanese Black, the "5216" release: No-Intro's cart (gamedata/nds/ports.toml's IRBJ) with 512 bytes of overlay 12
        // changed, which nothing here reads; headless it ran screen for screen as the No-Intro cart on the same save.
        "e5db5b8f79e8ae819be7d60e9e1006221d80ffab" to "ポケットモンスター ブラック",
        // Game Boy (pret/pokeyellow's roms.sha1).
        "cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1" to "Pokémon Yellow",
        "42f3714eec6eca25200d42461ff08d57c98f6d1d" to "Pokémon Gelbe Edition",
        "0aceec0ef7aa2ca5aa831554598d91f61a925591" to "Pokémon Version Jaune",
        "05bb8e99f24d498613930949730afa8024e77d08" to "Pokémon Versione Gialla",
        "1dc242039218fba50928d1afb66b70565b6b9daf" to "Pokémon Edición Amarilla",
        // pret/pokered's roms.sha1 (Blue: the build stands in for the cart).
        "ea9bcae617fdf159b045185467ae58b2e4a48b9a" to "Pokémon Red",
        "d7037c83e1ae5b39bde3c30787637ba1d4c48ce2" to "Pokémon Blue",
        "87d523fe1a0c548db7c5477b451ddec1eb083c06" to "Pokémon Rote Edition",
        "20e72dc6f41493eee1fdd0cef54214e6c3389688" to "Pokémon Blaue Edition",
        // Game Boy Color (pret/pokecrystal's roms.sha1): rev 0, rev 1, Australia.
        "f4cd194bdee0d04ca4eac29e09b8e4e9d818c133" to "Pokémon Crystal",
        "f2f52230b536214ef7c9924f483392993e226cfb" to "Pokémon Crystal",
        "a0fc810f1d4e124434f7be2c989ab5b5892ddf36" to "Pokémon Crystal",
        // Germany.
        "accb584293ba056152f1fd908439b019017ff2fe" to "Pokémon Kristall",
        // pret/pokegold's roms.sha1.
        "d8b8a3600a465308c9953dfa04f0081c05bdcb94" to "Pokémon Gold",
        "49b163f7e57702bc939d642a18f591de55d92dae" to "Pokémon Silver",
        // Germany.
        "9254195d461ea942eaaa08cc4b83de3cf82aea0d" to "Pokémon Goldene Edition",
        "8ecc58d621faaedf2a934bd2583d527220df7bb9" to "Pokémon Silberne Edition",
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
        "32988f918ca09923466ae3bc5bbfe5f2a771c728" to "Pokémon SoulGold",
        "dacb6e40e7f572ddefd2dfa10e99c0da6835b062" to "Pokémon SoulGold",
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
        "8114e0f81eb3581f699c74a5891c7b75a130269d" to "Pokémon Unbound",
        "7eca3ef1580e21b96027b721ecd3ba1aa36a6ea2" to "Pokémon Clover",
        "d2440fd6bae5a12dfc3cecc129b9bbff22d74b2e" to "Pokémon Elysium (Part A)",
        "71bf40178a1f5c69dc1c22099623434ba1e74318" to "Pokémon Elysium (Part B)",
        "b0d6b91356ee4d4c8f8c70020fe0352c391a12e2" to "Pokémon Rhythm",
        "4234507f92101a322aa5c0518a6aa1f1fe807832" to "Pokémon Oops All Eevees!",
        "8bac897de515f88e6ce92d0712ce2c018a394b37" to "Pokémon Orange Islands",
        "d2e3800e69e44c9649c3d9a15dc1cd12c1d90501" to "Pokémon Orange Islands",
        "3b6c51d8fb6b513bdd01b7bda3ed8965bc985d58" to "Pokémon Ultra Violet",
        "033d05374ae34eb84fae62c693ca8f3da23dd1bd" to "Pokémon FireRed Rocket Edition",
        "1ee1557db1e4c2e0e94c6306efcc40506bc40cc9" to "Pokémon Ash Gray",
        "92ed82145c013069472153391c7aab1cfbf37b75" to "Pokémon Ash Gray Fork Bug Fix",
        "ab3bc012598d73dce965450df79893aeff97c834" to "Pokémon Modern Emerald",
        "8decf0571ba3f17d31c76168346e240a14e248ce" to "Pokémon Epsilon Emerald",
        "5b38a681b6924ef3e0bb9f6cfbf22426c4c4e523" to "Pokémon Elite Redux",
        "2f3e17e55c6afe23da97ec0f96a067eeb142ad15" to "Pokémon Elite Redux",
        "40780347d0b4df6bf491eaf12603e5422caee677" to "Pokémon Team Rocket Edition: Dragonsden Version",
        "0d8bdaeeca0856175037a75679c1a2b73ad2b68a" to "Pokémon Team Rocket Edition 2026 (ES)",
        "32d9e438ab791a0477fffe9f1cca750cab5a4a80" to "Pokémon Team Rocket Edition 2026 (ES)",
        "ae1c4be9be8d706bb989602bc05731203d42860d" to "Pokémon Monomyth",
        "eb660caffbc26e1f09f86714d03af4c3eccd9e4d" to "Pokémon Monomyth",
        "742e62ffa5a8d607171fdb8477eb1f417dc36086" to "Pokémon Monomyth",
        "60182b934d2e8eb3eef226a9bbc25e5df6becd4f" to "Pokémon Wishes of Tomorrow",
        "2b0b608666cc2d553ce08d88fa76dce1fa577e51" to "Pokémon Ceruleum",
        "8df1c9720842c668448d5ad880e9c48bb29d7200" to "Pokémon RecordKeepers",
        "20e91aecb3243eea7cd610659846f37062f5d709" to "Pokémon Dreamstone Mysteries",
        "20dfaea1d5317cf95fe0b493208edce7d50b865f" to "Pokémon Ghost Grey",
        "74337272566a74625d6dedefe47d2d153eb48ec1" to "Pokémon Ghost Grey",
        "0dafc0116b74406a2df491e26ccb40dd362fb0b7" to "Pokémon Ghost Grey",
        "62bb900925118796fb963b7df92d02cb548c404e" to "Pokémon Let's Go Pikachu",
        "11f6ee3890a0aaeb95903471ec73413c846e86fc" to "Pokémon Let's Go Pikachu (Translation Only)",
        "4a41d539cbf6108cfe3344db5ba997fc5660d77d" to "Pokémon Let's Go Eevee",
        "5b6146adec1a30f88c565e8f555ab7a578825598" to "Pokémon Let's Go Eevee (Translation Only)",
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
        "2ac87de42afc70ef6016805acb3b206644417f97" to "Pokémon Yellow Legacy",
        "74a6727e4406c35bc2d4f81347a5c5fdb8970663" to "Pokémon Crystal Legacy",
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
        // Pokémon Brown 6.1.2 / 6.0.1 (Koolboyman's Rijon, a Red hack: GEN1_BROWN).
        "24eeda80ee78fa214a6f4a85164a2492d2b017ad" to "Pokémon Brown",
        "2d6d1f1a6010f578d67045802611a288ebd8748b" to "Pokémon Brown",
        "d419fe244fa17196f7df46ddab47c840e7573652" to "Pokémon PureBlue",
        // Pokémon Red (Gen II Graphics V2.0)
        "ad1adc824456f593f5bdb4cbfeb0ec12b24abb6d" to "Pokémon Red (Gen II Graphics)",
        // Pokémon Yellow (Gen II Graphics V1.1)
        "8e3d5a2b508ad832b86ae1dea72dd6d646b634c0" to "Pokémon Yellow (Gen II Graphics)",
    )

    /** DS carts worth hashing (128 MB each, once per file): the DS games above. */
    private val NDS_CODES = setOf(
        "CPUE", "CPUF", "IPKE", "IPKD", "IPKF", "IPGE", "IPGD", "IPGF", "IRBO", "IRBF", "IRAO", "IRAF", "IREO", "IRDO", "IPKJ",
    ) + com.pokedaisy.app.companion.data.DsPorts.codes

    /** Only these games' ROMs (and Game Boy carts) are worth hashing: every entry above is one of them. */
    private val CODES = setOf("BPRE", "BPGE", "BPEE", "AXVE", "AXPE") + EMERALD_LOCALIZED_CODES +
        RETAIL_PORT_CODE_TITLES.keys + TelemetrySampler.HACK_GAME_CODES

    fun knowsGbaCode(code: String?): Boolean = code in CODES

    fun knowsDsCode(code: String?): Boolean = code in NDS_CODES

    fun knownHack(sha1: String): Boolean = sha1 in HACKS

    /** The English marker stored in Android's title cache for randomized retail carts. */
    private const val RANDOMIZED = " (Randomized)"

    fun randomizedTitle(retailSha1: String): String? = RETAIL[retailSha1]?.let { it + RANDOMIZED }

    fun shown(title: String): String =
        if (title.endsWith(RANDOMIZED)) tr("{0} (Randomized)", title.removeSuffix(RANDOMIZED)) else title

    /** Callers provide their own archive-aware filename or direct cartridge filename. */
    fun label(
        fileName: () -> String,
        rename: String? = null,
        preferFileName: () -> Boolean = { false },
        recognized: () -> String? = { null },
    ): String = rename ?: if (preferFileName()) fileName() else recognized() ?: tidy(fileName())

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
}

/** The cartridge filename without its last extension. */
fun romFileStem(fileName: String): String = fileName.substringBeforeLast('.')
