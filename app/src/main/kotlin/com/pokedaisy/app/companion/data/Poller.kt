package com.pokedaisy.app.companion.data

import com.pokedaisy.app.MgbaCore

/** Told once per game when the Poller has decided what it is (a GameKind's name, and a BEST EFFORT match's title):
 * crash reports name it (CrashReports.gameIdentified, set up in PokeDaisyApp). */
@Volatile var onGameIdentified: ((kind: String, how: String?) -> Unit)? = null

/**
 * Builds a [SnapshotView] from the running core's memory. In-process replacement
 * for android-companion's UDP `telemetryFlow` — no sockets, no reconnect loop.
 *
 * [sample] must run on the emulator thread (EmulatorEngine.onSample) so bus
 * reads never race a frame. Detection (ROM code, size and hash) is cached.
 */
class TelemetrySampler {

    init {
        resetNativeBagCache()
        RomItemText.use(null)
    }

    private var kind: GameKind? = null
    private var nativeCfg: NativeConfig? = null
        set(v) {
            // A hack gets the shiny marks, shiny icons and SHINY ODDS only by its hash, at its own shiny check (measured
            // headless: ShinyOdds.HACK_SHINY_BELOW); retail's config comes with its own.
            field = v?.let(::shinyMarked)
            unmarkedCfg = v
            activeNativeConfig = field
        }
    // The config as detect() picked it: the builds' own tables go by identity (fireRedHackFor, soulGoldV12, ...), and a
    // marked one is a copy.
    private var unmarkedCfg: NativeConfig? = null

    /** [c] with its hack's own shiny check when the ROM's hash is a verified hack's (ShinyOdds.HACK_SHINY_BELOW). */
    private fun shinyMarked(c: NativeConfig): NativeConfig =
        ShinyOdds.HACK_SHINY_BELOW[romSha1]?.takeIf { c.shinyBelow == 0 }?.let { c.copy(shinyBelow = it) } ?: c

    // English Unbound reads through a fallback config (nativeCfg stays null): that one marked too.
    private val unboundCfg: NativeConfig get() = shinyMarked(NATIVE_UNBOUND_WITH_DEX)
    // The ROM's masked SHA-1 once detect() has hashed it (for the hacks' shiny check), and whether its code is the FireRed
    // engine's (BPR* / BPG*: gEnemyParty right before gPlayerParty where a config doesn't name it).
    private var romSha1: String? = null
    private var fireRedEngine = false

    private fun romHash(reader: MemoryReader, size: Long): String = sha1HexChunked(reader, 0x08000000L, size).also { romSha1 = it }
    // A Game Boy game's (Gen 1) addresses instead of [nativeCfg].
    private var gen1Cfg: Gen1Config? = null
    // A Game Boy Color game's (Gen 2) addresses instead.
    private var gen2Cfg: Gen2Config? = null
    // A DS game's (Gen 4) addresses instead.
    private var gen4Cfg: Gen4Config? = null
    private var dsGameCode = ""
    private var gen5Cfg: Gen5Config? = null
    private var lastError: String? = null
    // Set instead of nativeCfg for a large BPRE ROM that isn't recognized as
    // Unbound (see detect()) - a different hack we have no addresses for.
    private var unsupportedHackLabel: String? = null
    // The ROM hashed as retail FireRed / Emerald.
    private var retailHash = false
    // [nativeCfg]'s RAM is known to be this ROM's: a retail hash, or another language's retail
    // path - not an unrecognised same-size hack or a BEST EFFORT match landing on a retail config.
    private var ramVerified = false

    // BEST EFFORT (BestEffort.kt) for an unsupported ROM of a Gen 3 base game: its header code and
    // SHA-1 (what the match is kept by), the match in use and the config it was made from (the
    // version flags below compare by identity, and a partial match reads through a trimmed copy).
    private var beCode: String? = null
    private var beSha1: String? = null
    private var beSize = 0L
    private var beRev = 0
    private var beLabel: String? = null
    private var beMatch: BestEffort.Match? = null
    private var baseCfg: NativeConfig? = null
    // Where the match came from and whether a party has confirmed it (BestEffortStore.Entry).
    private var beSource = BestEffortStore.Source.AUTO
    private var beVerified = false
    // Best effort chose it just now (a notice), it's only a guess from the ROM's names, or a party
    // read since doesn't hold with it (PICK AGAIN / KEEP; beSuggest: the game that is sure instead).
    private var beFresh = false
    private var beGuess = false
    private var beDoubt = false
    private var beSuggest: BestEffort.Candidate? = null
    private var beDeclined = false
    // The ROM's text (once per ROM) and the last look at every config, for the picker's order.
    private var beText: BestEffort.RomText? = null
    private var beVerdict: BestEffort.Verdict? = null
    private var beSamples = 0
    private var beGuessSamples = 0

    // The player's answers from the companion (any thread), taken on the next sample.
    private sealed interface PickAnswer {
        class Pick(val id: String) : PickAnswer
        object None : PickAnswer
        object Keep : PickAnswer
        object Seen : PickAnswer
    }
    private val answers = java.util.concurrent.ConcurrentLinkedQueue<PickAnswer>()

    /** WHICH GAME IS THIS?: read the ROM as candidate [id] ([BestEffort.candidate]). */
    fun pickGame(id: String) { answers += PickAnswer.Pick(id) }
    /** NONE OF THESE: no game - NOT SUPPORTED, until the player opens the picker again. */
    fun pickNone() { answers += PickAnswer.None }
    /** KEEP: the doubted match stays, as the player's own pick. */
    fun keepGame() { answers += PickAnswer.Keep }
    /** The match notice was seen. */
    fun bestEffortSeen() { answers += PickAnswer.Seen }

    /** [label]: the ROM isn't one detect() knows. A match kept for it ([BestEffortStore]) reads it anyway. */
    private fun unsupportedRom(reader: MemoryReader, code: String, size: Long, hash: String?, label: String) {
        unsupportedHackLabel = label
        if (!BestEffort.canTry(code)) return
        beCode = code
        beSize = size
        beLabel = label
        beRev = runCatching { reader.readCoreMemory(0x080000BCL, 1)[0].toInt() and 0xFF }.getOrDefault(0)
        beSha1 = hash ?: runCatching { sha1HexChunked(reader, 0x08000000L, size) }.getOrNull()
        beDeclined = BestEffortStore.declined(beSha1)
        BestEffortStore.load(beSha1)?.let { e ->
            BestEffortStore.matchOf(e)?.let(::useBestEffort)
            beSource = e.source
            beVerified = e.verified
            // Best effort's own choice no party has confirmed yet (a guess, or a row from before the
            // stricter checks): a guess again, which a party confirms or the timeout drops.
            beGuess = e.source == BestEffortStore.Source.AUTO && !e.verified
        }
        // Look at it on the first sample: a guess or a sure match shows up at once.
        beSamples = EVALUATE_EVERY
    }

    private fun useBestEffort(m: BestEffort.Match) {
        kind = m.candidate.kind
        nativeCfg = m.config
        baseCfg = m.candidate.cfg
        unsupportedHackLabel = null
        beMatch = m
        itemTextChosen = false
    }

    /** Back to NOT SUPPORTED (WHICH GAME IS THIS? on the companion). */
    private fun dropBestEffort() {
        beMatch = null
        baseCfg = null
        nativeCfg = null
        kind = if (BestEffort.isFireRedFamily(beCode)) GameKind.FIRERED else GameKind.EMERALD
        unsupportedHackLabel = beLabel
        beFresh = false
        beGuess = false
        beDoubt = false
        beSuggest = null
        itemTextChosen = false
        resetNativeBagCache()
    }

    private fun save(m: BestEffort.Match, source: BestEffortStore.Source, verified: Boolean) {
        beSource = source
        beVerified = verified
        beSha1?.let { BestEffortStore.save(it, m, source, verified) }
    }

    private fun takeAnswers(reader: MemoryReader) {
        while (true) {
            when (val a = answers.poll() ?: return) {
                is PickAnswer.Pick -> {
                    val c = BestEffort.candidate(a.id) ?: continue
                    val code = beCode ?: continue
                    // Its ROM-side parts checked as usual; picked, it's verified once a party holds with it.
                    val fit = verdict(reader, code)?.fits?.firstOrNull { it.candidate === c }
                    val m = fit?.match() ?: BestEffort.Match(c, false, BestEffort.Part.entries.toSet(), 0)
                    dropBestEffort()
                    useBestEffort(m)
                    beDeclined = false
                    save(m, BestEffortStore.Source.PICKED, verified = fit?.sure == true)
                    android.util.Log.i("pokedaisy", "best effort: picked ${c.id} off=${m.off} for sha1=$beSha1")
                }
                PickAnswer.None -> {
                    dropBestEffort()
                    beDeclined = true
                    beSha1?.let(BestEffortStore::decline)
                }
                PickAnswer.Keep -> beMatch?.let { m ->
                    beDoubt = false
                    beSuggest = null
                    save(m, BestEffortStore.Source.PICKED, verified = true)
                }
                PickAnswer.Seen -> beFresh = false
            }
        }
    }

    private fun verdict(reader: MemoryReader, code: String): BestEffort.Verdict? {
        val text = beText ?: runCatching {
            val t0 = System.nanoTime()
            BestEffort.romText(reader, code, beSize).also {
                android.util.Log.i("pokedaisy", "best effort: ROM text read in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
        }.getOrNull()?.also { beText = it } ?: return null
        return BestEffort.evaluate(reader, code, beSize, text).also { beVerdict = it }
    }

    /**
     * Every [EVALUATE_EVERY] samples, while there's nothing to read the ROM with yet or the match
     * hasn't been confirmed by a party: what best effort makes of the running game. A SURE match is
     * used (and kept) without asking; with no party yet, a guess from the ROM's names stands in until
     * one reads. A match a party contradicts goes - or, the player's own pick, asks.
     */
    private fun runBestEffort(reader: MemoryReader) {
        val code = beCode ?: return
        val unresolved = beMatch == null && !beDeclined
        val unverified = beMatch != null && !beVerified && !beDoubt
        if (beVerdict != null && !unresolved && !unverified) return
        if (beGuess && ++beGuessSamples > GUESS_SAMPLES) {
            // A guess no party ever confirmed (the RAM isn't the guessed game's): the player picks.
            android.util.Log.i("pokedaisy", "best effort: guess ${beMatch?.candidate?.id} never read a party, asking")
            dropBestEffort()
            return
        }
        if (++beSamples < EVALUATE_EVERY) return
        beSamples = 0
        val v = verdict(reader, code) ?: return
        val sure = v.sure
        // The player's pick (here or in the library's INFO) reads with its ROM-side parts as this ROM has them.
        beMatch?.takeIf { beSource == BestEffortStore.Source.PICKED }?.let { m ->
            val checked = v.fits.firstOrNull { it.candidate === m.candidate }?.match() ?: return@let
            if (checked.off != m.off || checked.full != m.full) { useBestEffort(checked); save(checked, beSource, beVerified) }
        }
        val current = beMatch
        // A confirmed match isn't judged again (that look was only for the picker's order).
        if (current != null && beVerified) return
        when {
            current == null -> if (!beDeclined) {
                val pick = sure ?: v.guess ?: return
                val m = pick.match()
                useBestEffort(m)
                beFresh = true
                beGuess = sure == null
                beGuessSamples = 0
                save(m, BestEffortStore.Source.AUTO, verified = sure != null)
                android.util.Log.i("pokedaisy", "best effort: ${if (sure != null) "sure" else "guess"} ${m.candidate.id} off=${m.off} for sha1=$beSha1")
            }
            !v.sawParty -> {}
            sure != null && sure.candidate.kind == current.candidate.kind -> {
                // Confirmed - by the very build, or a better one of the same game.
                val m = if (beSource == BestEffortStore.Source.AUTO) sure.match() else current
                if (m.candidate !== current.candidate) useBestEffort(m)
                beGuess = false
                save(m, beSource, verified = true)
            }
            beSource == BestEffortStore.Source.AUTO -> {
                // Best effort's own choice didn't hold: the sure one instead, else the player picks.
                dropBestEffort()
                sure?.let { useBestEffort(it.match()); beFresh = true; save(it.match(), BestEffortStore.Source.AUTO, verified = true) }
                    ?: beSha1?.let(BestEffortStore::forget)
            }
            else -> {
                val fit = v.fits.firstOrNull { it.candidate === current.candidate }
                if (sure == null && fit?.party != null && fit.shownHolds) {
                    save(current, beSource, verified = true)
                } else {
                    beDoubt = true
                    beSuggest = sure?.candidate
                }
            }
        }
    }

    /** The latest snapshot, tagged with the game it came from (null while still detecting). */
    fun sample(reader: MemoryReader): SnapshotView {
        val s = sampleUntagged(reader)
        // An unsupported ROM has no game look to borrow - the app's own backdrop.
        return s.copy(
            game = if (s.unsupported) null else kind ?: if (s.connected) activeGame else null,
            textCode = romGameCode,
            textLanguage = romLanguage,
            bestEffort = beMatch?.let { m ->
                BestEffortView(
                    m.candidate.title, m.full, m.off, beFresh,
                    report = beSha1?.takeIf { beFresh && !beGuess }?.let { BestEffortReport(it.lowercase(), beSize, beCode.orEmpty(), beRev, m.candidate.id, m.full, m.off) },
                    id = m.candidate.id,
                    picked = beSource == BestEffortStore.Source.PICKED,
                    guess = beGuess,
                    doubt = beDoubt,
                    suggest = beSuggest?.title,
                )
            },
            gamePicker = beCode?.let { code -> gamePicker(code) },
        )
    }

    /** The picker's list: the last look's order, every config of the base game (before one, in the list's
     * own order) - one row per title (two builds of SoulGold v1.2 share one), the one in use where it's one. */
    private fun gamePicker(code: String): GamePickerView {
        val v = beVerdict
        val current = beMatch?.candidate?.id
        val all = v?.fits?.map { f -> GameOption(f.candidate.id, f.candidate.title, v.likely(f), f.party != null) }
            ?: BestEffort.candidatesFor(code).map { GameOption(it.id, it.title, likely = false, readsParty = false) }
        val options = all.groupBy { it.title }.values.map { same -> same.firstOrNull { it.id == current } ?: same.first() }
        return GamePickerView(options, current = current, miss = if (beMatch == null) v?.miss else null, declined = beDeclined)
    }

    private fun sampleUntagged(reader: MemoryReader): SnapshotView {
        try {
            if (kind == null) detect(reader)
            if (beCode != null) {
                takeAnswers(reader)
                runBestEffort(reader)
            }
            if (kind != null && !itemTextChosen) {
                itemTextChosen = true
                onGameIdentified?.invoke(kind!!.name, beMatch?.candidate?.title)
                PokedexSource.romChanged()
                (nativeCfg?.language ?: gen1Cfg?.language ?: dsGameCode.getOrNull(3))?.let { com.pokedaisy.app.companion.i18n.L10n.applyGameLanguage(it) }
                RomItemText.use(itemTable(reader))
            }
            activeGame = kind ?: GameKind.FIRERED
            val base = baseCfg ?: unmarkedCfg
            soulGoldV12 = base === NATIVE_SOULGOLD_V1_2 || base === NATIVE_SOULGOLD_V1_2B || base === NATIVE_SOULGOLD_V1_2C
            amethystV141 = base === NATIVE_AMETHYST_V1_4_1
            eliteRedux265_3 = base === NATIVE_ELITE_REDUX_V2_65_3B
            fireRedHack = fireRedHackFor(base)
            emeraldHack = emeraldHackFor(base)
            mariomonV13 = base === NATIVE_MARIOMON_V1_3 || base === NATIVE_MARIOMON_V1_2_2 || base === NATIVE_MARIOMON_V1_2_1
            quetzalV084 = base?.quetzalV084 == true
            quetzalV1 = base?.quetzalV1 == true
            gen2Cfg?.let { activeGen2 = it }
            // A DS cart: its own text's language (NdsArt.names: a French Sacred Gold is an IPKE HeartGold, GitHub #230).
            romLanguage = nativeCfg?.language ?: gen1Cfg?.language ?: NdsArt.names?.takeIf { kind?.isDsGame == true }?.language ?: dsGameCode.getOrNull(3) ?: 'E'
            romGameCode = nativeCfg?.gameCode ?: gen1Cfg?.gameCode ?: dsGameCode
            // Quetzal picks each kind of name's language from its own options (a few bytes a sample).
            quetzalNames = nativeCfg?.takeIf { it.quetzalPlacesDefault >= 0 }
                ?.let { runCatching { readQuetzalNames(reader, it) }.getOrNull() } ?: QuetzalNames()

            val telemetry = when (kind) {
                // English Unbound leaves nativeCfg null (see detect()); its French translation sets its own.
                GameKind.UNBOUND -> readNativeTelemetry(reader, nativeCfg ?: unboundCfg)
                GameKind.YELLOW, GameKind.RED, GameKind.BLUE, GameKind.PURE_RED, GameKind.PURE_BLUE ->
                    readGen1Telemetry(reader, gen1Cfg ?: GEN1_YELLOW)
                GameKind.CRYSTAL, GameKind.CRYSTAL_CLEAR, GameKind.PRISM, GameKind.GOLD, GameKind.SILVER, GameKind.POLISHED_CRYSTAL ->
                    readGen2Telemetry(reader, gen2Cfg ?: GEN2_CRYSTAL)
                GameKind.PLATINUM, GameKind.HEARTGOLD, GameKind.SOULSILVER -> readGen4Telemetry(reader, gen4Cfg ?: GEN4_PLATINUM_US)
                GameKind.BLACK -> readGen5Telemetry(reader, gen5Cfg ?: GEN5_BLACK)
                else -> {
                    unsupportedHackLabel?.let { return SnapshotView(connected = false, error = it, unsupported = true) }
                    val cfg = nativeCfg ?: return SnapshotView(connected = false, error = "no telemetry source")
                    readNativeTelemetry(reader, cfg)
                }
            }
            lastError = null
            return buildSnapshotView(telemetry)
        } catch (e: TelemetryDecodeException) {
            lastError = e.message
            return SnapshotView(connected = false, error = e.message)
        } catch (e: Exception) {
            return SnapshotView(connected = false, error = e.message ?: e.toString())
        }
    }

    private var itemTextChosen = false

    /** Where the detected game's item descriptions are ([RomItemText]): its config's
     * table when the ROM has it there, else the vanilla table found by its shape. */
    private fun itemTable(reader: MemoryReader): ItemDescTable? {
        if (kind.isGen1 || kind.isGen2 || kind.isDsGame || unsupportedHackLabel != null) return null
        val cfg = if (kind == GameKind.UNBOUND) nativeCfg ?: NATIVE_UNBOUND_WITH_DEX else nativeCfg
        val t = cfg?.itemDescs
        if (t != null && RomItemText.matchesRom(reader, t)) return t
        if (cfg != null && t == null) return null
        val size = runCatching { MgbaCore.pkRomSize() }.getOrDefault(0L)
        return RomItemText.findVanillaItems(reader, size)
    }

    private fun detect(reader: MemoryReader) {
        when (runCatching { MgbaCore.pkPlatform() }.getOrDefault(0)) {
            MgbaCore.PLATFORM_GB -> return detectGameBoy()
            MgbaCore.PLATFORM_NDS -> return detectDs()
        }
        val code = runCatching { MgbaCore.pkRomCode() }.getOrNull().orEmpty()
        val size = runCatching { MgbaCore.pkRomSize() }.getOrDefault(0L)
        romSha1 = null
        fireRedEngine = code.startsWith("BPR") || code.startsWith("BPG")

        // LeafGreen / Ruby / Sapphire / the other Emeralds: retail only, read
        // natively straight away. LeafGreen looks like FireRed, the rest like
        // Emerald (same species / item / map-section ids).
        if (code in OTHER_RETAIL_CODES) {
            val rev = runCatching { reader.readCoreMemory(0x080000BCL, 1)[0].toInt() and 0xFF }.getOrDefault(-1)
            val cfg = otherRetailConfig(code, rev)
            kind = if (code.startsWith("BPR") || code.startsWith("BPG")) GameKind.FIRERED else GameKind.EMERALD
            when {
                size > 0x1000000L -> unsupportedRom(reader, code, size, null,
                    "unrecognized $code-based ROM hack (${size / (1024 * 1024)} MB) - no known RAM addresses for this build")
                cfg == null -> unsupportedRom(reader, code, size, null, "${gameLabel(code)} revision $rev isn't supported yet")
                else -> {
                    // The shiny marks, shiny icons and SHINY ODDS: only on a ROM whose hash is retail's (ShinyOdds.RETAIL_SHA1S).
                    val hash = runCatching { romHash(reader, size) }.getOrNull()
                    nativeCfg = withCheckedTables(reader, cfg).let { if (hash in ShinyOdds.RETAIL_SHA1S) it.copy(shinyBelow = 8) else it }
                    ramVerified = true
                }
            }
            return
        }

        // Pokémon Ultra Violet: FireRed 1.0 under a game code of its own (PUVV) - by its hash.
        if (code == ULTRA_VIOLET_CODE) {
            val hash = runCatching { romHash(reader, size) }.getOrNull()
            kind = GameKind.FIRERED
            if (hash == ULTRA_VIOLET_V1_22_SHA1) nativeCfg = NATIVE_ULTRA_VIOLET
            else unsupportedRom(reader, code, size, hash, "unrecognized Ultra Violet build (sha1 ${hash?.take(12) ?: "unknown"}…)")
            return
        }

        // Not FireRed/Emerald-based at all (another GBA game): nothing here
        // knows its RAM - say so rather than read it as FireRed.
        // [CompanionSupport] makes the same call from the file at import.
        if (code.length == 4 && code != "BPRE" && code != "BPEE") {
            kind = GameKind.FIRERED
            // A Gen 3 base game's code under another letter (a regional build nothing maps) may still read as one.
            unsupportedRom(reader, code, size, null, "game code $code isn't a supported Pokémon game")
            return
        }

        var smallHash: String? = null   // a ≤16 MB FireRed / Emerald ROM's, from the checks below
        // Not every BPRE hack expands past 16 MB either (Orange Islands is exactly 16 MB, like
        // retail): hash once, like the small-BPEE check below. An unknown hash falls through to
        // the retail check at the end.
        if (code == "BPRE" && size <= 0x1000000L) {
            val hash = runCatching { romHash(reader, size) }.getOrNull()
            if (hash == ORANGE_ISLANDS_UNVERSIONED_SHA1 || hash == ORANGE_ISLANDS_V5_7_BETA_SHA1) {
                kind = GameKind.ORANGE_ISLANDS
                nativeCfg = NATIVE_ORANGE_ISLANDS
                return
            }
            // FireRed 1.0 hacks edited in place, run as FireRed with their own names (ActiveTables' fireRedHack).
            fireRedHackConfig(hash)?.let {
                kind = GameKind.FIRERED
                nativeCfg = it
                ramVerified = true   // rev 0's battle code and cursors: a written cell + A checked headless on each
                return
            }
            // Recharged Yellow: a 16 MB rebuild of pokefirered, one config per release.
            rechargedYellowConfig(hash)?.let {
                kind = GameKind.RECHARGED_YELLOW
                nativeCfg = it
                return
            }
            retailHash = hash in RETAIL_SHA1S || retailDump(reader, code, size)
            smallHash = hash
        }

        // A big (>16 MB) BPRE ROM isn't unique to Unbound - other FireRed-based
        // hacks (Gaia, Radical Red, ...) share the same game code and also
        // expand past the vanilla 16 MB size for their added content. Size and
        // code alone can't tell them apart, and assuming Unbound would apply
        // its (wrong) RAM addresses to a different hack's memory layout,
        // producing silently wrong/garbage telemetry instead of an honest
        // "not supported" - so confirm with an exact whole-ROM hash instead of
        // guessing. One-time cost per launch (a few hundred ms at most to read
        // and hash up to 32 MB), not worth caching more cleverly than "only
        // runs once, since detect() itself is only called while kind == null".
        if (code == "BPRE" && size > 0x1000000L) {
            // Chunked, not one big readCoreMemory(size) call: the native
            // pkReadBytes bridge caps a single read at 16 MB (len > 1<<24 ->
            // null), so a whole-ROM read here would silently fail for any
            // ROM past that (i.e. exactly the large hacks this check exists
            // for) and the hash would always come back null.
            val hash = runCatching { romHash(reader, size) }.getOrNull()
            // FireRed 1.0 hacks edited in place past 16 MB (Ghost Grey): run as FireRed with their own names.
            fireRedHackConfig(hash)?.let {
                kind = GameKind.FIRERED
                nativeCfg = it
                ramVerified = true   // rev 0's battle code and cursors: a written cell + A checked headless on each
                return
            }
            when (hash) {
                UNBOUND_V2_1_1_1_SHA1 -> kind = GameKind.UNBOUND
                UNBOUND_V2_1_1_1_FR_SHA1 -> { kind = GameKind.UNBOUND; nativeCfg = NATIVE_UNBOUND_FR }
                GAIA_V3_2_SHA1 -> { kind = GameKind.GAIA; nativeCfg = NATIVE_GAIA_V3_2 }
                RADICAL_RED_V4_1_SHA1 -> { kind = GameKind.RADICAL_RED; nativeCfg = NATIVE_RADICAL_RED_V4_1 }
                ODYSSEY_V4_1_1_SHA1 -> { kind = GameKind.ODYSSEY; nativeCfg = NATIVE_ODYSSEY }
                AMETHYST_V1_3_0_SHA1 -> { kind = GameKind.AMETHYST; nativeCfg = NATIVE_AMETHYST }
                AMETHYST_V1_4_1_SHA1 -> { kind = GameKind.AMETHYST; nativeCfg = NATIVE_AMETHYST_V1_4_1 }
                CELIA_V1_1_4_SHA1 -> { kind = GameKind.CELIA; nativeCfg = NATIVE_CELIA }
                SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_SHA1, SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_CASUAL_SHA1,
                SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_PERFORMANCE_SHA1, SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_CASUAL_PERFORMANCE_SHA1,
                -> { kind = GameKind.SWORD_SHIELD; nativeCfg = NATIVE_SWORD_SHIELD }
                GS_CHRONICLES_V2_7_6_SHA1 -> { kind = GameKind.GS_CHRONICLES; nativeCfg = NATIVE_GS_CHRONICLES }
                else -> {
                    kind = GameKind.FIRERED
                    unsupportedRom(reader, code, size, hash, "unrecognized FireRed-based ROM hack " +
                        "(BPRE, ${size / (1024 * 1024)} MB, sha1 ${hash?.take(12) ?: "unknown"}…) " +
                        "- no known RAM addresses for this build")
                    // Full hash, not just the 12-char prefix in the UI-facing
                    // message above - needed to add a new *_SHA1 constant.
                    // This is the LIVE-BUS hash (see RADICAL_RED_V4_1_SHA1's
                    // comment on why it can differ from a plain `shasum` on
                    // the file - RTC/GPIO registers mapped into the ROM's
                    // address space), so don't substitute a file-hash here.
                    android.util.Log.i("pokedaisy", "unrecognized BPRE hack: full sha1=$hash size=$size")
                }
            }
            // A hash-matched hack's RAM is as vouched for as retail's: a config carrying the battle menus' cursors
            // (Unbound, SoulGold) gets the one-press picks (knownCursorAddrs); an unrecognised hash never does.
            ramVerified = hash != null && hash in SUPPORTED_HACK_SHA1S
            return
        }

        // Same idea for Emerald-based hacks (BPEE) - see the BPRE block above
        // for why a hash, not a guess: a large BPEE ROM read with retail
        // Emerald's addresses would show silently wrong data for a real hack.
        if (code == "BPEE" && size > 0x1000000L) {
            val hash = runCatching { romHash(reader, size) }.getOrNull()
            when (hash) {
                HEART_AND_SOUL_V2_0_6_SHA1 -> { kind = GameKind.HEART_AND_SOUL; nativeCfg = NATIVE_HEART_AND_SOUL }
                LAZARUS_V2_0_SHA1 -> { kind = GameKind.LAZARUS; nativeCfg = NATIVE_LAZARUS }
                ROWE_V2_1_9_1_SHA1 -> { kind = GameKind.ROWE; nativeCfg = NATIVE_ROWE }
                EMERALD_ROGUE_V2_2_1_EX_SHA1 -> { kind = GameKind.EMERALD_ROGUE; nativeCfg = NATIVE_EMERALD_ROGUE }
                TMT2_V1_5_2_SHA1 -> { kind = GameKind.TMT2; nativeCfg = NATIVE_TMT2 }
                SOULGOLD_V1_1_4_SHA1 -> { kind = GameKind.SOULGOLD; nativeCfg = NATIVE_SOULGOLD }
                SOULGOLD_V1_2_SHA1 -> { kind = GameKind.SOULGOLD; nativeCfg = NATIVE_SOULGOLD_V1_2 }
                SOULGOLD_V1_2B_SHA1 -> { kind = GameKind.SOULGOLD; nativeCfg = NATIVE_SOULGOLD_V1_2B }
                SOULGOLD_V1_2C_SHA1 -> { kind = GameKind.SOULGOLD; nativeCfg = NATIVE_SOULGOLD_V1_2C }
                GLAZED_V9_2_0_SHA1 -> { kind = GameKind.GLAZED; nativeCfg = NATIVE_GLAZED }
                IMPERIUM_V1_3_1_SHA1 -> { kind = GameKind.IMPERIUM; nativeCfg = NATIVE_IMPERIUM }
                QUETZAL_V9_0_ALPHA_SHA1 -> { kind = GameKind.QUETZAL; nativeCfg = NATIVE_QUETZAL }
                QUETZAL_V9_0_ALPHA_ES_SHA1 -> { kind = GameKind.QUETZAL; nativeCfg = NATIVE_QUETZAL_ES }
                QUETZAL_V9_1_ALPHA_SHA1 -> { kind = GameKind.QUETZAL; nativeCfg = NATIVE_QUETZAL_V1 }
                QUETZAL_V9_1_ALPHA_ES_SHA1 -> { kind = GameKind.QUETZAL; nativeCfg = NATIVE_QUETZAL_ES_V1 }
                QUETZAL_V0_8_4_ALPHA_SHA1 -> { kind = GameKind.QUETZAL; nativeCfg = NATIVE_QUETZAL_V084 }
                QUETZAL_V0_8_4_ALPHA_ES_SHA1 -> { kind = GameKind.QUETZAL; nativeCfg = NATIVE_QUETZAL_V084_ES }
                MOEMON_QUETZAL_V0_8_4_ALPHA_Hotfix_SHA1 -> { kind = GameKind.QUETZAL; nativeCfg = NATIVE_QUETZAL_MOEMON_V084 }
                MOEMON_QUETZAL_V0_8_4_ALPHA_ES_Hotfix_SHA1 -> { kind = GameKind.QUETZAL; nativeCfg = NATIVE_QUETZAL_MOEMON_V084_ES }
                MARIOMON_V1_5_2_Anniversary_SHA1 -> { kind = GameKind.MARIOMON; nativeCfg = NATIVE_MARIOMON_V1_5_2 }
                MARIOMON_V1_5_1_Anniversary_SHA1 -> { kind = GameKind.MARIOMON; nativeCfg = NATIVE_MARIOMON_V1_5_1 }
                MARIOMON_V1_3_SHA1 -> { kind = GameKind.MARIOMON; nativeCfg = NATIVE_MARIOMON_V1_3 }
                MARIOMON_V1_2_2_SHA1 -> { kind = GameKind.MARIOMON; nativeCfg = NATIVE_MARIOMON_V1_2_2 }
                MARIOMON_V1_2_1_SHA1 -> { kind = GameKind.MARIOMON; nativeCfg = NATIVE_MARIOMON_V1_2_1 }
                ELITE_REDUX_V2_65_3B_SHA1 -> { kind = GameKind.ELITE_REDUX; nativeCfg = NATIVE_ELITE_REDUX_V2_65_3B }
                ELITE_REDUX_V2_65_4_1B_SHA1 -> { kind = GameKind.ELITE_REDUX; nativeCfg = NATIVE_ELITE_REDUX_V2_65_4_1B }
                TEAM_ROCKET_DRAGONSDEN_V1_5_SHA1 -> { kind = GameKind.TEAM_ROCKET; nativeCfg = NATIVE_TEAM_ROCKET }
                WISHES_OF_TOMORROW_V1_2_6_SHA1 -> { kind = GameKind.WISHES; nativeCfg = NATIVE_WISHES }
                DREAMSTONE_MYSTERIES_UNVERSIONED_SHA1 -> { kind = GameKind.DREAMSTONE; nativeCfg = NATIVE_DREAMSTONE }
                else -> {
                    kind = GameKind.EMERALD
                    unsupportedRom(reader, code, size, hash, "unrecognized Emerald-based ROM hack " +
                        "(BPEE, ${size / (1024 * 1024)} MB, sha1 ${hash?.take(12) ?: "unknown"}…) " +
                        "- no known RAM addresses for this build")
                    android.util.Log.i("pokedaisy", "unrecognized BPEE hack: full sha1=$hash size=$size")
                }
            }
            // A hash-matched hack's RAM is as vouched for as retail's: a config carrying the battle menus' cursors
            // (Unbound, SoulGold) gets the one-press picks (knownCursorAddrs); an unrecognised hash never does.
            ramVerified = hash != null && hash in SUPPORTED_HACK_SHA1S
            return
        }

        // Not every BPEE hack expands past 16 MB (Emerald Seaglass is exactly
        // 16 MB, same as retail) - hash those too, before the retail check below.
        if (code == "BPEE") {
            val hash = runCatching { romHash(reader, size) }.getOrNull()
            if (hash == EMERALD_SEAGLASS_V3_0_SHA1) {
                kind = GameKind.EMERALD_SEAGLASS
                nativeCfg = NATIVE_EMERALD_SEAGLASS
                return
            }
            // Emerald Kaizo: retail Emerald edited in place - retail's RAM, its own moves and wild table.
            if (hash == EMERALD_KAIZO_V2_0_SHA1) {
                kind = GameKind.EMERALD
                nativeCfg = NATIVE_EMERALD_KAIZO
                ramVerified = true   // retail's battle code and cursors: a written cell + A checked headless on it
                return
            }
            // Recharged Emerald: a 16 MB rebuild of pokeemerald, run as Emerald with its own RAM and names.
            rechargedEmeraldConfig(hash)?.let {
                kind = GameKind.EMERALD
                nativeCfg = it
                ramVerified = true   // its cursors: walked, and a written cell + A checked headless on each build
                return
            }
            // Emerald Legacy: pokeemerald rebuilt from its own source - its own RAM and code (NATIVE_EMERALD_LEGACY).
            if (hash == EMERALD_LEGACY_V1_1_4_SHA1) {
                kind = GameKind.EMERALD
                nativeCfg = NATIVE_EMERALD_LEGACY
                ramVerified = true   // its build's own symbols; the battle cursors kept retail's EWRAM (checked headless)
                return
            }
            retailHash = hash in RETAIL_SHA1S || retailDump(reader, code, size)
            smallHash = hash
        }

        // FireRed / Emerald: retail by its hash. Any other ROM under those codes is a hack
        // nothing here maps - NOT SUPPORTED, or BEST EFFORT's pick.
        val emerald = code == "BPEE"
        kind = if (emerald) GameKind.EMERALD else GameKind.FIRERED
        if (!retailHash) {
            unsupportedRom(reader, code, size, smallHash, "unrecognized ${if (emerald) "Emerald" else "FireRed"}-based ROM hack " +
                "($code, ${size / (1024 * 1024)} MB, sha1 ${smallHash?.take(12) ?: "unknown"}…) - no known RAM addresses for this build")
            android.util.Log.i("pokedaisy", "unrecognized $code ROM: full sha1=$smallHash size=$size")
            return
        }
        val rev = runCatching { reader.readCoreMemory(0x080000BCL, 1)[0].toInt() and 0xFF }.getOrDefault(1)
        val cfg = when {
            emerald -> NATIVE_EMERALD_RETAIL
            rev >= 1 -> NATIVE_FIRERED_REV1
            // NATIVE_FIRERED_REV0 is the hacks' base too: only retail gets the card.
            else -> NATIVE_FIRERED_REV0.copy(trainerCard = TRAINER_CARD_FIRERED)
        }
        // The SHINY marks: retail's shiny check (8 of 65536), on this copy only.
        nativeCfg = withCheckedTables(reader, cfg).copy(shinyBelow = 8)
        ramVerified = true
    }

    /** A trimmed / save-patched retail dump ([RetailDumps]): retail's RAM and code, so read as retail. */
    private fun retailDump(reader: MemoryReader, code: String, size: Long): Boolean {
        val rev = runCatching { reader.readCoreMemory(0x080000BCL, 1)[0].toInt() and 0xFF }.getOrNull()
        return runCatching {
            RetailDumps.match(code, rev, size) { off, len -> reader.readCoreMemory(0x08000000L + off, len) }
        }.getOrNull()?.also { android.util.Log.i("pokedaisy", "$code: a retail dump with its save code patched / end trimmed (retail $it)") } != null
    }

    /** No DEX tab, and no HERE / NEXT BOSS guide pages, unless the config's
     * tables are really where it expects them in this ROM. */
    private fun withCheckedTables(reader: MemoryReader, cfg: NativeConfig): NativeConfig {
        val checked = cfg.pokedex?.takeIf { !pokedexMatchesRom(reader, it) }?.let { cfg.copy(pokedex = null) } ?: cfg
        return checked.guideTables?.takeIf { !guideTablesMatchRom(reader, it) }
            ?.let { checked.copy(guideTables = null, enemyParty = 0) } ?: checked
    }

    /**
     * Fast, 2-byte peek at battleActiveBattler/battleInputState — unlike
     * [sample], not throttled to ~1 Hz, for driving the bottom-screen touch
     * battle control (see PLAN.md Phase 5). Returns null before
     * [sample] has resolved which game/path is active, or on a pre-v3 ROM
     * (structSize too small to have these fields at all).
     */
    /** The detected game's verified gMain and its inBattle offset (its native config), or null
     * (not detected yet) - for SMART FF's menu watch ([FfMenuWatch]). */
    val knownGMain: Pair<Long, Long>?
        get() = (if (kind == GameKind.UNBOUND) NATIVE_UNBOUND else nativeCfg)?.let { it.gMain to it.inBattleOff }

    /** A Game Boy / Color cart, by the whole ROM's SHA1 (read from the cart: it's bank-switched). */
    /**
     * A DS cart: by its header's game code and revision (a 128 MB+ ROM isn't hashed live). Platinum USA rev 1 and
     * HeartGold / SoulSilver USA / Germany are mapped (pret/pokeplatinum and pret/pokeheartgold build them), Black (USA / Europe) from
     * headless captures (Gen5Reader); anything else gets the
     * "not supported" notice.
     */
    private fun detectDs() {
        val code = runCatching { MgbaCore.pkRomCode() }.getOrNull().orEmpty()
        dsGameCode = code
        val rev = runCatching { reader0x1E() }.getOrDefault(-1)
        when {
            ndsSupported(code, rev) -> when (code) {
                "IPKE" -> { kind = GameKind.HEARTGOLD; gen4Cfg = GEN4_HEARTGOLD_US }
                "IPKD" -> { kind = GameKind.HEARTGOLD; gen4Cfg = GEN4_HEARTGOLD_DE }
                "IPGD" -> { kind = GameKind.SOULSILVER; gen4Cfg = GEN4_SOULSILVER_DE }
                "IPGE" -> { kind = GameKind.SOULSILVER; gen4Cfg = GEN4_SOULSILVER_US }
                "IRBO" -> { kind = GameKind.BLACK; gen5Cfg = GEN5_BLACK }
                else -> { kind = GameKind.PLATINUM; gen4Cfg = GEN4_PLATINUM_US }
            }
            else -> {
                kind = GameKind.FIRERED
                unsupportedHackLabel = "DS game $code (rev $rev) isn't supported yet"
            }
        }
    }

    /** The DS header's revision byte (0x1E), from the cart's ROM window (pk_nds_read: 0x08000000 + offset). */
    private fun reader0x1E(): Int = MgbaCore.pkReadBytes(0x0800001EL, 1)?.get(0)?.toInt()?.and(0xFF) ?: -1

    private fun detectGameBoy() {
        val size = runCatching { MgbaCore.pkRomSize() }.getOrDefault(0L)
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        var off = 0L
        while (off < size) {
            val len = minOf(1L shl 20, size - off).toInt()
            digest.update(MgbaCore.pkRomRead(off, len) ?: break)
            off += len
        }
        val hash = if (off == size && size > 0) digest.digest().joinToString("") { "%02x".format(it) } else null
        when {
            GAME_BOY_SUPPORT && hash == RED_SHA1 -> { kind = GameKind.RED; gen1Cfg = GEN1_RED }
            GAME_BOY_SUPPORT && hash == BLUE_SHA1 -> { kind = GameKind.BLUE; gen1Cfg = GEN1_BLUE }
            GAME_BOY_SUPPORT && hash == PURE_RED_V2_7_6_SHA1 -> { kind = GameKind.PURE_RED; gen1Cfg = GEN1_PURE_RED }
            GAME_BOY_SUPPORT && hash == PURE_BLUE_V2_7_6_SHA1 -> { kind = GameKind.PURE_BLUE; gen1Cfg = GEN1_PURE_BLUE }
            GAME_BOY_SUPPORT && gen1ConfigFor(hash) != null -> { kind = GameKind.YELLOW; gen1Cfg = gen1ConfigFor(hash) }
            hash in CRYSTAL_SHA1S && GAME_BOY_SUPPORT -> { kind = GameKind.CRYSTAL; gen2Cfg = GEN2_CRYSTAL }
            GAME_BOY_SUPPORT && hash == GOLD_SHA1 -> { kind = GameKind.GOLD; gen2Cfg = GEN2_GOLD }
            GAME_BOY_SUPPORT && hash == SILVER_SHA1 -> { kind = GameKind.SILVER; gen2Cfg = GEN2_SILVER }
            GAME_BOY_SUPPORT && crystalClearConfigFor(hash) != null -> { kind = GameKind.CRYSTAL_CLEAR; gen2Cfg = crystalClearConfigFor(hash) }
            GAME_BOY_SUPPORT && hash == PRISM_V0_95_254_SHA1 -> { kind = GameKind.PRISM; gen2Cfg = GEN2_PRISM }
            GAME_BOY_SUPPORT && hash in POLISHED_CRYSTAL_SHA1S ->
                { kind = GameKind.POLISHED_CRYSTAL
                  gen2Cfg = (if (hash in POLISHED_CRYSTAL_FAITHFUL_SHA1S) GEN2_POLISHED_CRYSTAL_FAITHFUL else GEN2_POLISHED_CRYSTAL)
                      .copy(pokedex = polishedCrystalPokedex(hash!!)) }
            else -> {
                kind = GameKind.FIRERED
                unsupportedHackLabel = "this Game Boy game isn't supported yet (sha1 ${hash?.take(12) ?: "unknown"}…)"
            }
        }
    }

    /** The battle menus' cursors (gActionSelectionCursor, gMoveSelectionCursor), only where the RAM is
     * known to be this ROM's ([ramVerified]: a retail hash or a hash-matched hack, never a BEST EFFORT
     * match): a wrong address that happened to hold the cell wanted would confirm the wrong choice. */
    val knownCursorAddrs: Pair<Long, Long>?
        get() = (if (kind == GameKind.UNBOUND) nativeCfg ?: NATIVE_UNBOUND else nativeCfg)
            ?.takeIf { ramVerified && beMatch == null && it.actionCursor != 0L && it.moveCursor != 0L }
            ?.let { it.actionCursor to it.moveCursor }

    /** The detected game's gPartyMenu + gPlayerParty when its config has them (SoulGold), or null. */
    val knownPartyMenu: Pair<Long, Long>?
        get() = nativeCfg?.takeIf { it.partyMenu != 0L }?.let { it.partyMenu to it.playerParty }

    /** SHINY ODDS' foe party and player ID: on a config the Poller marked by its ROM's hash ([NativeConfig.shinyBelow]),
     * never a hack on retail's RAM - CFRU's plaintext boxes would be scrambled by a re-encryption. */
    val knownShinyTarget: ShinyOdds.Target?
        get() = (nativeCfg ?: unboundCfg.takeIf { kind == GameKind.UNBOUND })?.takeIf { it.shinyBelow > 0 && beMatch == null }
            ?.let { ShinyOdds.targetFor(it, fireRedFamily = fireRedEngine, plaintext = romSha1 in ShinyOdds.PLAINTEXT_SHA1S,
                national = romSha1 in ShinyOdds.NATIONAL_SHA1S) }

    /** The detected game's own USE / GIVE / TAKE tables when its config has them (Recharged Emerald), or null. */
    val knownFieldItems: FieldItemFns?
        get() = nativeCfg?.fieldItems

    /** A Game Boy game's battle party menu, for the POKéMON switch ([GbPartySwitch]): Yellow (every language) and the
     * Red / Blue and the Gen 2 games, each checked headless (a switch from PkMn / PKMN and the pick after a faint); null otherwise. */
    val gbPartySwitch: GbPartySwitch?
        get() = when {
            kind.isGen1 -> gen1Cfg?.let { c -> gbSwitchFor(c) { Gen1PartySwitch(c) } }
            kind.isGen2 -> gen2Cfg?.let { c -> gbSwitchFor(c) { Gen2PartySwitch(c) } }
            else -> null
        }

    // One switch reader per config, not one a sample.
    private var gbSwitch: Pair<Any, GbPartySwitch>? = null
    private fun gbSwitchFor(cfg: Any, make: () -> GbPartySwitch): GbPartySwitch =
        gbSwitch?.takeIf { it.first === cfg }?.second ?: make().also { gbSwitch = cfg to it }

    /** The open battle menu's cursor from the last fast sample, for a Gen 1 game ([readGen1BattleInput]); -1 otherwise. */
    @Volatile var battleMenuCursor = -1
        private set

    fun sampleBattleInputFast(reader: MemoryReader): Pair<Int, Int>? {
        if (kind.isDsGame) return null   // no battle reads on DS yet
        if (kind.isGen1) {
            val (state, cursor) = runCatching { readGen1BattleInput(reader, gen1Cfg ?: GEN1_YELLOW) }.getOrNull() ?: return null
            battleMenuCursor = cursor
            return 0 to state
        }
        if (kind.isGen2) {
            val (state, cursor) = runCatching { readGen2BattleInput(reader, gen2Cfg ?: GEN2_CRYSTAL) }.getOrNull() ?: return null
            battleMenuCursor = cursor
            return 0 to state
        }
        // Unbound's `kind` is decided directly in detect() without ever
        // populating nativeCfg (see the GameKind.UNBOUND branch there) -
        // sample() has its own matching special case for the same reason.
        if (kind == GameKind.UNBOUND) return readNativeBattleInputFast(reader, NATIVE_UNBOUND)
        return nativeCfg?.let { readNativeBattleInputFast(reader, it) }
    }

    companion object {
        // Pokémon Unbound v2.1.1.1 - the only version this project's Unbound
        // support (native-RAM addresses, gen3mon decode, icon fetch, etc.) has
        // ever been built or verified against. A different Unbound version
        // would need re-verifying the same way, same as any other hack.
        //
        // Equals a plain `shasum` on the file (b4776b82a4c7915d0fadeaa27e01
        // 3523f99dfd94, also what CLAUDE.md's project notes pin) now that
        // sha1HexChunked masks the RTC/GPIO hole (see its own comment) -
        // wasn't always true, see git history if this needs re-deriving on an
        // older checkout.
        /** The other retail games, read natively: LeafGreen, Ruby, Sapphire, the other languages. */
        val OTHER_RETAIL_CODES = setOf("BPGE", "AXVE", "AXPE") + EMERALD_LOCALIZED_CODES +
            RETAIL_PORTS.keys.map { it.dropLast(1) }

        /** The config for [code] at header revision [rev], or null if that revision isn't mapped. */
        fun otherRetailConfig(code: String, rev: Int): NativeConfig? = RETAIL_PORTS["$code$rev"]?.invoke() ?: when (code) {
            "BPGE" -> when (rev) { 0 -> NATIVE_LEAFGREEN_REV0; 1 -> NATIVE_LEAFGREEN_REV1; else -> null }
            // Revs 1 and 2 share every address (both built from pokeruby); rev 0 wasn't checked.
            "AXVE" -> if (rev == 1 || rev == 2) NATIVE_RUBY else null
            "AXPE" -> if (rev == 1 || rev == 2) NATIVE_SAPPHIRE else null
            // Each European Emerald had one release (rev 0).
            "BPES" -> if (rev == 0) NATIVE_EMERALD_ES else null
            "BPED" -> if (rev == 0) NATIVE_EMERALD_DE else null
            "BPEF" -> if (rev == 0) NATIVE_EMERALD_FR else null
            "BPEI" -> if (rev == 0) NATIVE_EMERALD_IT else null
            "BPEJ" -> if (rev == 0) NATIVE_EMERALD_JA else null
            else -> null
        }

        fun gameLabel(code: String) = when (code) {
            "BPGE" -> "LeafGreen"
            "AXVE" -> "Ruby"
            "AXPE" -> "Sapphire"
            "BPES", "BPED", "BPEF", "BPEI", "BPEJ" -> "Emerald (${code.last()})"
            else -> RETAIL_PORT_CODE_TITLES[code] ?: code
        }

        const val UNBOUND_V2_1_1_1_SHA1 = "b4776b82a4c7915d0fadeaa27e013523f99dfd94"
        // Its French fan translation (the same release's code and RAM, French text) - host-side
        // masked hash (GPIO bytes zero, so a plain `shasum`).
        const val UNBOUND_V2_1_1_1_FR_SHA1 = "0ce2a880aa097f1dce4e1db8ee513d0e82d15859"
        // Pokémon Gaia v3.2 - see NATIVE_GAIA_V3_2 in NativeReader.kt for the
        // verification this address reuse is based on.
        const val GAIA_V3_2_SHA1 = "d5b1e77975fcda831e0e9a7b527906bf3f40ecd0"
        // Pokémon Radical Red v4.1 - equals a plain `shasum` on the file now
        // that sha1HexChunked masks the RTC/GPIO hole (see its own comment) -
        // this hack maps a real-time-clock feature into the classic
        // 0x080000C4-C9 GPIO hole a few official carts and several modern
        // hacks use, and reading through it unmasked returns live,
        // NOT-necessarily-stable-across-a-session hardware state instead of
        // static ROM data (confirmed 2026-09-22 via Heart and Soul: matched a
        // file hash on a truly fresh boot, came out completely different
        // after resuming a session with real elapsed play time).
        const val RADICAL_RED_V4_1_SHA1 = "964f951a0fdaf209e4ea1344883ef0d557bb3a80"
        // Pokemon Odyssey (English) v4.1.1 - confirmed live on-device 2026-09-22.
        const val ODYSSEY_V4_1_1_SHA1 = "8745ddbdbfadf6abaf66de4e9055923b62eb4668"
        // Emerald-based (BPEE) hacks - confirmed live on-device 2026-09-22 via
        // the full-hash debug log in the BPEE `else` branch above.
        const val HEART_AND_SOUL_V2_0_6_SHA1 = "79ee6df0869c1773c8c6a5f764afc1f8d833d8bb"
        const val LAZARUS_V2_0_SHA1 = "7dcdc7e280bc4631487e13dd37e6e0cea04adea6"
        const val ROWE_V2_1_9_1_SHA1 = "81bd0f4bfa1c04ab2c6faab1bddd10e8a390ea77"
        // Emerald Rogue v2.2.1-EX - confirmed live on-device 2026-09-22; equals
        // a plain `shasum` on the file (7600af1fe08444c850c3c1227fd7dfd81336ae8e).
        const val EMERALD_ROGUE_V2_2_1_EX_SHA1 = "7600af1fe08444c850c3c1227fd7dfd81336ae8e"
        // Pokemon Amethyst v1.3.0 - a BPRE (FireRed-based) hack, unlike the
        // other two above - confirmed live on-device 2026-09-22; equals a
        // plain `shasum` on the file (00e70c0384a5f1698588034201fd5b849d3542e2).
        const val AMETHYST_V1_3_0_SHA1 = "00e70c0384a5f1698588034201fd5b849d3542e2"
        // Pokemon Amethyst v1.4.1 - host-side masked hash (GPIO bytes zero, so a plain `shasum`).
        const val AMETHYST_V1_4_1_SHA1 = "91291aade04b4b111cd03ae7b6e2ff460e1edd8a"
        // Retail FireRed rev 0 / rev 1 and Emerald (USA, Europe): pret's sha1s, which equal the
        // masked hash (their GPIO bytes are zero).
        val RETAIL_SHA1S = setOf(
            "41cb23d8dccc8ebd7c649cd8fbb58eeace6e2fdc",
            "dd5945db9b930750cb39d00c84da8571feebf417",
            "f3ae088181bf583e55daf962a92bb46f4f1d07b7",
        )
        // Emerald Seaglass v3.0 - GPIO-hole-masked hash computed host-side from
        // the file (its 0x080000C4-C9 bytes are zero, so it also equals a plain
        // `shasum`). NOT yet confirmed via the on-device live-bus log.
        const val EMERALD_SEAGLASS_V3_0_SHA1 = "b9f4d332d30fc88c379f9e037f9eae3b2755ead4"
        // Pokemon Celia's Stupid Romhack v1.1.4 - same host-side masked hash as
        // Seaglass (GPIO bytes are zero, so it equals a plain `shasum`).
        const val CELIA_V1_1_4_SHA1 = "3cbd5a2e72ce60cfae1e0c28d83e01f855c51537"
        // Pokemon Too Many Types 2 v1.5.2 - host-side masked hash (GPIO bytes zero).
        const val TMT2_V1_5_2_SHA1 = "3fa8e61ec1727cc51540c54677623f736a446e1b"
        // Pokémon SoulGold v1.1.4 (BPEE, 32 MB) - host-side masked hash (GPIO bytes zero).
        const val SOULGOLD_V1_1_4_SHA1 = "ea5d369cc8a31cbf1cfacb7c9470ea670f08957b"
        // Pokémon SoulGold v1.2 - host-side masked hash (GPIO bytes zero).
        const val SOULGOLD_V1_2_SHA1 = "805d880ee229fb6dc3ce03d7b03baf48f0d759d0"
        // A second build released as SoulGold v1.2 (its title screen says v1.2 too) - masked hash
        // (GPIO bytes zero).
        const val SOULGOLD_V1_2B_SHA1 = "5d6a036260fdbde96f85b5c1b92d0256d3aebafb"
        // A third build released as SoulGold v1.2 ("v1.2" on its title screen too) - masked hash
        // (GPIO bytes zero).
        const val SOULGOLD_V1_2C_SHA1 = "2be7c99aa473308debe1d03ff2015bab7d9addb5"
        // Pokémon Emerald Kaizo v2.0 (BPEE, 16 MB) - its GPIO bytes are zero, so the masked hash is the file's.
        const val EMERALD_KAIZO_V2_0_SHA1 = "a7c4e34fafb53f2d5283eb6d43dad285c0dd40a8"
        // Pokémon Emerald Legacy v1.1.4 (BPEE, 16 MB) - its source at tag V1.1.4 built with agbcc is this file byte for byte.
        const val EMERALD_LEGACY_V1_1_4_SHA1 = "cbd71af65ee3dcd53280a6a12014bfb11e8f626a"
        // Pokémon Glazed 9.2.0 (BPEE, 32 MB) - host-side masked hash (GPIO bytes zero).
        const val GLAZED_V9_2_0_SHA1 = "e10105d8544469c6a11ca2cf510289981df3c3b0"
        // Pokémon Emerald Imperium v1.3.1 (BPEE, 32 MB) - host-side masked hash (GPIO bytes zero).
        const val IMPERIUM_V1_3_1_SHA1 = "1d20091c4d936f5eb122db8780554dd0829ffb63"
        // Pokémon Quetzal English Alpha 9 v0 (BPEE, 32 MB) - host-side masked hash (GPIO bytes zero).
        const val QUETZAL_V9_0_ALPHA_SHA1 = "d0658315da1e8827f66f15c3d3a000fe747e163e"
        // Pokémon Quetzal Spanish Alpha 9 v0 (BPEE, 32 MB) - host-side masked hash (GPIO bytes zero).
        const val QUETZAL_V9_0_ALPHA_ES_SHA1 = "fe346b5b0eb022e3a103f81e7dcefba6f1dc542f"
        // Pokémon Quetzal English / Spanish Alpha 9 v1 (BPEE, 32 MB): plain `shasum`s (their GPIO bytes are zero).
        const val QUETZAL_V9_1_ALPHA_SHA1 = "c108cec750a35775084c0806cc704d8abd1994e7"
        const val QUETZAL_V9_1_ALPHA_ES_SHA1 = "ff008b70b6f2b330da9ffa8a405fc951a9e54147"
        // Pokémon Quetzal Alpha 0.8.4, English / Spanish, and the Moemon Hotfix builds of both (BPEE, 32 MB) - host-side
        // masked hashes (GPIO bytes zero, so a plain `shasum`).
        const val QUETZAL_V0_8_4_ALPHA_SHA1 = "6806c7146aff457a4be85ef2030e9ddcdb17b665"
        const val QUETZAL_V0_8_4_ALPHA_ES_SHA1 = "b6542c7dbb40cf6ea96714a7a0e30cb468102861"
        const val MOEMON_QUETZAL_V0_8_4_ALPHA_Hotfix_SHA1 = "f1035f615c13c46394a18fa99181c24c3cd15e12"
        const val MOEMON_QUETZAL_V0_8_4_ALPHA_ES_Hotfix_SHA1 = "ae5d88bb823a8f9d0d07e8784da2376ff7c1ae43"
        // Pokémon Orange Islands (BPRE rev 0, 16 MB - the size of retail, so checked by hash before
        // the retail check): Beta 5.7, and a build with no version on it that differs from it
        // in 80 bytes of map text and data (a typo Beta 5.7 doesn't have - likely an earlier beta).
        // Both host-side masked hashes (GPIO bytes zero).
        const val ORANGE_ISLANDS_V5_7_BETA_SHA1 = "d2e3800e69e44c9649c3d9a15dc1cd12c1d90501"
        const val ORANGE_ISLANDS_UNVERSIONED_SHA1 = "8bac897de515f88e6ce92d0712ce2c018a394b37"
        // Pokémon Ultra Violet v1.22 (16 MB, FireRed rev 0 code under the game code PUVV) - host-side
        // masked hash (GPIO bytes zero, so a plain `shasum`).
        const val ULTRA_VIOLET_V1_22_SHA1 = "3b6c51d8fb6b513bdd01b7bda3ed8965bc985d58"
        const val ULTRA_VIOLET_CODE = "PUVV"
        // Pokémon Sword and Shield Ultimate Plus v1.2.1.2 (BPRE, 32 MB, CFRU) - host-side masked hash
        // (GPIO bytes zero).
        const val SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_SHA1 = "afe4c8e0f9c7186860fcea5f25d8318f5d7929b9"
        // Its three patched releases: the same tables and RAM (the Casual patch changes some item prices and
        // trainers, the Performance patch some code), so the same config. Masked hashes (GPIO bytes zero).
        const val SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_CASUAL_SHA1 = "daa2f89934d2c0447d5271dabeba86bfba04e1fd"
        const val SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_PERFORMANCE_SHA1 = "4858cc3d7b335bb5eed553861d7a4a17bad9ce2c"
        const val SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_CASUAL_PERFORMANCE_SHA1 = "932323d1fa3732d8673d1319fa86ed4df41087b9"
        // Pokémon GS Chronicles v2.7.6 (BPRE, 32 MB, CFRU; Ruki Studios) - host-side masked hash (GPIO bytes zero).
        const val GS_CHRONICLES_V2_7_6_SHA1 = "25f8840ecb58e584edf18ffde35f845e94f3f17c"

        // Pokémon FireRed Rocket Edition (BPRE rev 0, 16 MB; no version on it) - host-side masked hash (GPIO bytes zero).
        const val FIRERED_ROCKET_EDITION_UNVERSIONED_SHA1 = "033d05374ae34eb84fae62c693ca8f3da23dd1bd"
        // Pokémon Ash Gray v4.5.3 and its Fork Bug Fix v1.0 build (BPRE rev 0, 16 MB, the same tables) -
        // host-side masked hashes (GPIO bytes zero).
        const val ASH_GRAY_V4_5_3_SHA1 = "1ee1557db1e4c2e0e94c6306efcc40506bc40cc9"
        const val ASH_GRAY_FORK_BUG_FIX_V1_0_SHA1 = "92ed82145c013069472153391c7aab1cfbf37b75"

        // Pokémon Elite Redux 2.65.3b and 2.65.4.1b (BPEE, 32 MB; a pokeemerald rebuild of its own) - host-side masked
        // hashes (GPIO bytes zero, so a plain `shasum`). Each its own build: RAM and ROM tables moved between them.
        const val ELITE_REDUX_V2_65_3B_SHA1 = "5b38a681b6924ef3e0bb9f6cfbf22426c4c4e523"
        const val ELITE_REDUX_V2_65_4_1B_SHA1 = "2f3e17e55c6afe23da97ec0f96a067eeb142ad15"
        // Pokémon Team Rocket Edition: Dragonsden Version 1.5 (its START menu says so; BPEE, 32 MB; DragonsdenTV's own build)
        // - host-side masked hash (GPIO bytes zero, so a plain `shasum`).
        const val TEAM_ROCKET_DRAGONSDEN_V1_5_SHA1 = "40780347d0b4df6bf491eaf12603e5422caee677"
        // Pokémon Wishes of Tomorrow v1.2.6 (FlatFoot Labs; BPEE, 32 MB, pokeemerald-expansion 1.15.3) - host-side masked
        // hash (GPIO bytes zero, so a plain `shasum`).
        const val WISHES_OF_TOMORROW_V1_2_6_SHA1 = "60182b934d2e8eb3eef226a9bbc25e5df6becd4f"
        // Pokémon Dreamstone Mysteries (BPEE, 32 MB; dsmyst; the game shows no version: its title, START menu and save screen
        // have none) - host-side masked hash (GPIO bytes zero, so a plain `shasum`).
        const val DREAMSTONE_MYSTERIES_UNVERSIONED_SHA1 = "20e91aecb3243eea7cd610659846f37062f5d709"
        // Pokémon Ghost Grey v1.0.1, v1.0.2 and v1.0.4 (BPRE rev 0, 32 MB; JoeyZeed) - host-side masked hashes (GPIO
        // bytes zero, so a plain `shasum`).
        const val GHOST_GREY_V1_0_1_SHA1 = "20dfaea1d5317cf95fe0b493208edce7d50b865f"
        const val GHOST_GREY_V1_0_2_SHA1 = "74337272566a74625d6dedefe47d2d153eb48ec1"
        const val GHOST_GREY_V1_0_4_SHA1 = "0dafc0116b74406a2df491e26ccb40dd362fb0b7"

        /** The config of a FireRed 1.0 hack edited in place that runs as FireRed (Rocket Edition, Ash Gray, Ghost Grey), or null. */
        fun fireRedHackConfig(hash: String?): NativeConfig? = when (hash) {
            FIRERED_ROCKET_EDITION_UNVERSIONED_SHA1 -> NATIVE_ROCKET_EDITION
            ASH_GRAY_V4_5_3_SHA1, ASH_GRAY_FORK_BUG_FIX_V1_0_SHA1 -> NATIVE_ASH_GRAY
            GHOST_GREY_V1_0_2_SHA1, GHOST_GREY_V1_0_4_SHA1 -> NATIVE_GHOST_GREY
            GHOST_GREY_V1_0_1_SHA1 -> NATIVE_GHOST_GREY_V1_0_1
            else -> null
        }

        /** Hacks under a game code of their own that detect() reads as their base game (by hash). */
        val HACK_GAME_CODES = setOf(ULTRA_VIOLET_CODE)
        // Pokémon Recharged Yellow (BPRE rev 0, 16 MB, a pokefirered rebuild): four releases, each
        // its own build (NATIVE_RECHARGED_YELLOW_*). Host-side masked hashes (GPIO bytes zero).
        const val RECHARGED_YELLOW_V1_8_0_SHA1 = "be5033598e91d0e80fa93e551406f02a7876d7c2"
        const val RECHARGED_YELLOW_V1_9_0_SHA1 = "ac30d1ed80e75104a3fb92f71e2b9a51c26e80c8"
        const val RECHARGED_YELLOW_V1_9_4_SHA1 = "7d9fae6cf31c07bc7fa5cf7afe137ad5385cefb1"
        const val RECHARGED_YELLOW_V1_9_7_SHA1 = "ed60654d7525ba005bfc8f799bf0ecba2ef08a2e"

        /** Recharged Yellow's config for a release's hash; null for any other ROM. */
        fun rechargedYellowConfig(hash: String?): NativeConfig? = when (hash) {
            RECHARGED_YELLOW_V1_8_0_SHA1 -> NATIVE_RECHARGED_YELLOW_V1_8_0
            RECHARGED_YELLOW_V1_9_0_SHA1 -> NATIVE_RECHARGED_YELLOW_V1_9_0
            RECHARGED_YELLOW_V1_9_4_SHA1 -> NATIVE_RECHARGED_YELLOW_V1_9_4
            RECHARGED_YELLOW_V1_9_7_SHA1 -> NATIVE_RECHARGED_YELLOW_V1_9_7
            else -> null
        }

        // Pokémon Recharged Emerald (Jaizu; BPEE, 16 MB, a pokeemerald rebuild): three builds, each its own
        // (NATIVE_RECHARGED_EMERALD_*). Their GPIO bytes are zero, so the masked hashes are the files'.
        const val RECHARGED_EMERALD_V2_2_5_SHA1 = "67f44fb4cdbb16c0d1a495ac219f2979b1e3005f"
        const val RECHARGED_EMERALD_V2_2_8_SHA1 = "56c9777215f5be627f7c3099e68985aefc10f2b7"
        const val RECHARGED_EMERALD_REBALANCED_V2_2_8_SHA1 = "08b9c7903ae4c4e26d80ef945a90c004ae1e0ebb"

        /** Recharged Emerald's config for a build's hash; null for any other ROM. */
        fun rechargedEmeraldConfig(hash: String?): NativeConfig? = when (hash) {
            RECHARGED_EMERALD_V2_2_5_SHA1 -> NATIVE_RECHARGED_EMERALD_V2_2_5
            RECHARGED_EMERALD_V2_2_8_SHA1 -> NATIVE_RECHARGED_EMERALD_V2_2_8
            RECHARGED_EMERALD_REBALANCED_V2_2_8_SHA1 -> NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8
            else -> null
        }

        // Super Mariomon (Too Many Productions; BPEE, 32 MB): every release the user has, host-side
        // masked hashes (GPIO bytes zero, so a plain `shasum`).
        const val MARIOMON_V1_2_1_SHA1 = "43dd208b23eade69aa3384301e934ecafbf6c2b3"
        const val MARIOMON_V1_2_2_SHA1 = "08d1c275c0ba4d559b33427292702d61ad49fb5c"
        const val MARIOMON_V1_3_SHA1 = "043512c2497871c018df606fb90ffbd4c81430f4"
        const val MARIOMON_V1_5_1_Anniversary_SHA1 = "831fe0f97301f17b24a9cd4a64ef0520835c4dde"
        const val MARIOMON_V1_5_2_Anniversary_SHA1 = "06e79d12afa578db97c23c1f7ba771fe2f8fe905"

        // Pokémon Yellow (USA, Europe) - the Game Boy cart (pret/pokeyellow builds it byte for byte).
        const val YELLOW_SHA1 = "cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1"
        // The German, French, Italian and Spanish carts (GBC, SGB Enhanced): plain `shasum`s.
        const val YELLOW_DE_SHA1 = "42f3714eec6eca25200d42461ff08d57c98f6d1d"
        const val YELLOW_FR_SHA1 = "0aceec0ef7aa2ca5aa831554598d91f61a925591"
        const val YELLOW_IT_SHA1 = "05bb8e99f24d498613930949730afa8024e77d08"
        const val YELLOW_ES_SHA1 = "1dc242039218fba50928d1afb66b70565b6b9daf"

        // Pokémon Red and Blue (USA, Europe): pret/pokered builds both byte for byte.
        const val RED_SHA1 = "ea9bcae617fdf159b045185467ae58b2e4a48b9a"
        const val BLUE_SHA1 = "d7037c83e1ae5b39bde3c30787637ba1d4c48ce2"

        // PureRed / PureBlue v2.7.6 (Vortyne/pureRGB): its source at tag v2.7.6 builds both byte for byte.
        const val PURE_RED_V2_7_6_SHA1 = "2e94d09c1e16a57eb079404c030f26fdeeac949d"
        const val PURE_BLUE_V2_7_6_SHA1 = "d419fe244fa17196f7df46ddab47c840e7573652"

        /** A Yellow cart's config by its hash, or null for any other Game Boy game. */
        fun gen1ConfigFor(hash: String?): Gen1Config? = when (hash) {
            YELLOW_SHA1 -> GEN1_YELLOW
            YELLOW_DE_SHA1 -> GEN1_YELLOW_DE
            YELLOW_FR_SHA1 -> GEN1_YELLOW_FR
            YELLOW_IT_SHA1 -> GEN1_YELLOW_IT
            YELLOW_ES_SHA1 -> GEN1_YELLOW_ES
            else -> null
        }

        // Pokémon Crystal - rev 0 (USA), rev 1 (USA, Europe) and Australia: pret/pokecrystal builds all three byte for byte.
        const val CRYSTAL_SHA1 = "f4cd194bdee0d04ca4eac29e09b8e4e9d818c133"
        const val CRYSTAL_REV1_SHA1 = "f2f52230b536214ef7c9924f483392993e226cfb"
        const val CRYSTAL_AU_SHA1 = "a0fc810f1d4e124434f7be2c989ab5b5892ddf36"
        val CRYSTAL_SHA1S = setOf(CRYSTAL_SHA1, CRYSTAL_REV1_SHA1, CRYSTAL_AU_SHA1)

        // Pokémon Gold and Silver (USA, Europe): pret/pokegold builds both byte for byte.
        const val GOLD_SHA1 = "d8b8a3600a465308c9953dfa04f0081c05bdcb94"
        const val SILVER_SHA1 = "49b163f7e57702bc939d642a18f591de55d92dae"

        // Pokémon Crystal Clear (ShockSlayer's open-world Crystal hack): v2.5.11 and v2.6.0, each a Standard Colors
        // build and a Color Filter one (the same code and tables, other palettes): one RAM, a version's ROM tables.
        const val CRYSTAL_CLEAR_V2_5_11_SHA1 = "a1c4da6b0057c98fe4ebea45d6812a484fc69abd"
        const val CRYSTAL_CLEAR_V2_5_11_COLOR_FILTER_SHA1 = "6fc677bf7a9db103cb9ee11ac08d26782e69ae41"
        const val CRYSTAL_CLEAR_V2_6_0_SHA1 = "84d5c4924c3f1f3fc690669148b7e392948fb23d"
        const val CRYSTAL_CLEAR_V2_6_0_COLOR_FILTER_SHA1 = "534876e4e4d0d79a82fef3bbe5bea11c40451727"
        val CRYSTAL_CLEAR_SHA1S = setOf(CRYSTAL_CLEAR_V2_5_11_SHA1, CRYSTAL_CLEAR_V2_5_11_COLOR_FILTER_SHA1,
            CRYSTAL_CLEAR_V2_6_0_SHA1, CRYSTAL_CLEAR_V2_6_0_COLOR_FILTER_SHA1)

        fun crystalClearConfigFor(sha1: String?): Gen2Config? = when (sha1) {
            CRYSTAL_CLEAR_V2_5_11_SHA1, CRYSTAL_CLEAR_V2_5_11_COLOR_FILTER_SHA1 -> GEN2_CRYSTAL_CLEAR_V2_5_11
            CRYSTAL_CLEAR_V2_6_0_SHA1, CRYSTAL_CLEAR_V2_6_0_COLOR_FILTER_SHA1 -> GEN2_CRYSTAL_CLEAR_V2_6_0
            else -> null
        }

        // Pokémon Prism (RainbowDevs' Crystal-engine hack): v0.95 build 254 (Hotfix 5), the final release. A file named
        // as a patched Crystal that calls itself "LOL PRISM" isn't Prism: Crystal rev 0 with its first 32 KB replaced by a
        // program that fills the screen with "LOL" and halts - nothing to read.
        const val PRISM_V0_95_254_SHA1 = "752076692ae3387cf426ce5f51a98c6b60e8df6a"

        // Polished Crystal 3.2.0 - 3.2.3 (Rangi42's open-source Crystal hack): every official release build - normal,
        // faithful (the original games' types / moves / item names) and their debug builds - plain `shasum`s.
        const val POLISHED_CRYSTAL_V3_2_3_SHA1 = "6930b48af5844d373e3c9130f26d6dd1084cf4ed"
        const val POLISHED_CRYSTAL_V3_2_2_SHA1 = "beb8b38280af59e9ef84be12684a6ecf09021c9b"
        const val POLISHED_CRYSTAL_V3_2_1_SHA1 = "79ebf9984eac8081f488d15a35a6c5d11b4f84e2"
        const val POLISHED_CRYSTAL_V3_2_0_SHA1 = "e55859484c5cda70e05daed59b14662e0a0f8eac"
        const val POLISHED_CRYSTAL_V3_2_3_Debug_SHA1 = "457d61dfc28a17388b87792595433ba20bd1c1ed"
        const val POLISHED_CRYSTAL_V3_2_2_Debug_SHA1 = "bea9804dc88a4ee370af62ff3139fdf1380277bf"
        const val POLISHED_CRYSTAL_V3_2_1_Debug_SHA1 = "9c13585eb34efdcd881c2e68f029b820bddf349d"
        const val POLISHED_CRYSTAL_V3_2_0_Debug_SHA1 = "11d461629363402fc15b5005ef51b8f44e726140"
        const val POLISHED_CRYSTAL_V3_2_3_Faithful_SHA1 = "3ff091a8e94a0c031372b6f0275e12620fe3d766"
        const val POLISHED_CRYSTAL_V3_2_2_Faithful_SHA1 = "e63b1e52a9f71c3948e3e7e515e9939d728fc679"
        const val POLISHED_CRYSTAL_V3_2_1_Faithful_SHA1 = "666f8edfb9efd8c301adbc14d38ccfaae33a047d"
        const val POLISHED_CRYSTAL_V3_2_0_Faithful_SHA1 = "b012f6d55c34c972778f5566e1cc910962dd7ab5"
        const val POLISHED_CRYSTAL_V3_2_3_Faithful_Debug_SHA1 = "b5393eb27fec51970cc9186f36c34e94494bfd39"
        const val POLISHED_CRYSTAL_V3_2_2_Faithful_Debug_SHA1 = "772858dd5a3e66dea4454d5f83b48c83d732ca69"
        const val POLISHED_CRYSTAL_V3_2_1_Faithful_Debug_SHA1 = "c4bd263e58325c6c04c34462c1b4a37fba085e9c"
        const val POLISHED_CRYSTAL_V3_2_0_Faithful_Debug_SHA1 = "c0a2c70d73ce68830a891ddd292300c90766acdd"
        val POLISHED_CRYSTAL_FAITHFUL_SHA1S = setOf(
            POLISHED_CRYSTAL_V3_2_3_Faithful_SHA1, POLISHED_CRYSTAL_V3_2_2_Faithful_SHA1, POLISHED_CRYSTAL_V3_2_1_Faithful_SHA1,
            POLISHED_CRYSTAL_V3_2_0_Faithful_SHA1, POLISHED_CRYSTAL_V3_2_3_Faithful_Debug_SHA1, POLISHED_CRYSTAL_V3_2_2_Faithful_Debug_SHA1,
            POLISHED_CRYSTAL_V3_2_1_Faithful_Debug_SHA1, POLISHED_CRYSTAL_V3_2_0_Faithful_Debug_SHA1,
        )
        val POLISHED_CRYSTAL_SHA1S = POLISHED_CRYSTAL_FAITHFUL_SHA1S + setOf(
            POLISHED_CRYSTAL_V3_2_3_SHA1, POLISHED_CRYSTAL_V3_2_2_SHA1, POLISHED_CRYSTAL_V3_2_1_SHA1, POLISHED_CRYSTAL_V3_2_0_SHA1,
            POLISHED_CRYSTAL_V3_2_3_Debug_SHA1, POLISHED_CRYSTAL_V3_2_2_Debug_SHA1, POLISHED_CRYSTAL_V3_2_1_Debug_SHA1,
            POLISHED_CRYSTAL_V3_2_0_Debug_SHA1,
        )

        /** The Game Boy / Color carts the companion reads ([CompanionSupport] checks imports against it). */
        val SUPPORTED_GB_SHA1S = setOf(YELLOW_SHA1, YELLOW_DE_SHA1, YELLOW_FR_SHA1, YELLOW_IT_SHA1, YELLOW_ES_SHA1, RED_SHA1, BLUE_SHA1) +
            CRYSTAL_SHA1S + CRYSTAL_CLEAR_SHA1S + PRISM_V0_95_254_SHA1 + GOLD_SHA1 + SILVER_SHA1 + POLISHED_CRYSTAL_SHA1S +
            PURE_RED_V2_7_6_SHA1 + PURE_BLUE_V2_7_6_SHA1

        /**
         * Game Boy / Color (Pokémon Yellow): the library, folder scan, frontend launch and
         * archives take .gb / .gbc (RomArchive.ROM_EXTENSIONS) and a Yellow cart is read by the
         * Gen 1 reader. False turns all of it off again (then drop the manifest's .gb / .gbc
         * VIEW patterns too): a cart that arrives anyway gets the "not supported" notice.
         */
        const val GAME_BOY_SUPPORT = true

        /**
         * Nintendo DS (on melonDS): the library, folder scan, frontend launch and archives take .nds and a known cart
         * ([NDS_GAMES]) is read by the Gen 4 reader. False turns all of it off again (then drop the manifest's .nds
         * VIEW pattern too): a DS cart is then never listed.
         */
        const val NDS_SUPPORT = true

        /** The DS carts the companion reads, by their header's game code (0x0C) and revision (0x1E): Platinum USA rev 1,
         * HeartGold / SoulSilver USA / Germany (rev 0), Black (USA / Europe, rev 0). The website's ROM check gets them through compat.json. */
        val NDS_GAMES = mapOf("CPUE" to 1, "IPKE" to 0, "IPKD" to 0, "IPGE" to 0, "IPGD" to 0, "IRBO" to 0)

        /** [detectDs] and the library's [com.pokedaisy.app.CompanionSupport] both ask this. */
        fun ndsSupported(code: String?, revision: Int?): Boolean =
            NDS_SUPPORT && revision != null && NDS_GAMES[code] == revision

        /** Every hack detect() has RAM addresses for - the >16 MB ones it
         * hashes, plus Seaglass, Emerald Kaizo, Emerald Legacy, Orange Islands, Recharged Yellow / Emerald and Ultra Violet (16 MB). [CompanionSupport] checks imports against it. */
        val SUPPORTED_HACK_SHA1S = setOf(
            UNBOUND_V2_1_1_1_SHA1, GAIA_V3_2_SHA1, RADICAL_RED_V4_1_SHA1, ODYSSEY_V4_1_1_SHA1,
            AMETHYST_V1_3_0_SHA1, AMETHYST_V1_4_1_SHA1, CELIA_V1_1_4_SHA1, HEART_AND_SOUL_V2_0_6_SHA1, LAZARUS_V2_0_SHA1,
            ROWE_V2_1_9_1_SHA1, EMERALD_ROGUE_V2_2_1_EX_SHA1, TMT2_V1_5_2_SHA1, EMERALD_SEAGLASS_V3_0_SHA1, EMERALD_KAIZO_V2_0_SHA1, EMERALD_LEGACY_V1_1_4_SHA1,
            SOULGOLD_V1_1_4_SHA1, SOULGOLD_V1_2_SHA1, SOULGOLD_V1_2B_SHA1, SOULGOLD_V1_2C_SHA1, GLAZED_V9_2_0_SHA1, IMPERIUM_V1_3_1_SHA1,
            QUETZAL_V9_0_ALPHA_SHA1, QUETZAL_V9_0_ALPHA_ES_SHA1, QUETZAL_V9_1_ALPHA_SHA1, QUETZAL_V9_1_ALPHA_ES_SHA1, UNBOUND_V2_1_1_1_FR_SHA1,
            QUETZAL_V0_8_4_ALPHA_SHA1, QUETZAL_V0_8_4_ALPHA_ES_SHA1, MOEMON_QUETZAL_V0_8_4_ALPHA_Hotfix_SHA1,
            MOEMON_QUETZAL_V0_8_4_ALPHA_ES_Hotfix_SHA1,
            ORANGE_ISLANDS_V5_7_BETA_SHA1, ORANGE_ISLANDS_UNVERSIONED_SHA1,
            FIRERED_ROCKET_EDITION_UNVERSIONED_SHA1, ASH_GRAY_V4_5_3_SHA1, ASH_GRAY_FORK_BUG_FIX_V1_0_SHA1,
            GHOST_GREY_V1_0_1_SHA1, GHOST_GREY_V1_0_2_SHA1, GHOST_GREY_V1_0_4_SHA1,
            ELITE_REDUX_V2_65_3B_SHA1, ELITE_REDUX_V2_65_4_1B_SHA1, TEAM_ROCKET_DRAGONSDEN_V1_5_SHA1,
            WISHES_OF_TOMORROW_V1_2_6_SHA1,
            DREAMSTONE_MYSTERIES_UNVERSIONED_SHA1,
            ULTRA_VIOLET_V1_22_SHA1, SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_SHA1, SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_CASUAL_SHA1,
            SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_PERFORMANCE_SHA1, SWORD_SHIELD_ULTIMATE_PLUS_V1_2_1_2_CASUAL_PERFORMANCE_SHA1,
            RECHARGED_YELLOW_V1_8_0_SHA1, RECHARGED_YELLOW_V1_9_0_SHA1, RECHARGED_YELLOW_V1_9_4_SHA1,
            RECHARGED_YELLOW_V1_9_7_SHA1,
            RECHARGED_EMERALD_V2_2_5_SHA1, RECHARGED_EMERALD_V2_2_8_SHA1, RECHARGED_EMERALD_REBALANCED_V2_2_8_SHA1,
            MARIOMON_V1_2_1_SHA1, MARIOMON_V1_2_2_SHA1, MARIOMON_V1_3_SHA1, MARIOMON_V1_5_1_Anniversary_SHA1,
            MARIOMON_V1_5_2_Anniversary_SHA1,
            GS_CHRONICLES_V2_7_6_SHA1,
        )
    }
}

// The classic GBA cart RTC/rumble GPIO hole: 3 memory-mapped u16 registers
// (data/direction/control) a handful of official carts and several modern
// hacks map into what's otherwise plain ROM address space. Reading through
// it returns LIVE hardware state, not static ROM bytes - and unlike what the
// RADICAL_RED_V4_1_SHA1/UNBOUND_V2_1_1_1_SHA1 comments originally assumed,
// that state is NOT necessarily stable across a session (confirmed
// 2026-09-22: Heart and Soul's live-bus hash matched a plain file hash on a
// truly fresh boot, then came out completely different after resuming a
// session with real elapsed play time - almost certainly the RTC clock
// itself having ticked forward). Masking these 6 bytes out before hashing
// makes detection stable regardless of how long the game's been running,
// instead of only working on the very first boot.
/** Samples (~1 a second) between best effort's looks while it has something to settle. */
private const val EVALUATE_EVERY = 5
/** How long a guess from the ROM's names stands without a party to confirm it (~10 minutes). */
private const val GUESS_SAMPLES = 600

private val GPIO_HOLE_START = 0x080000C4L
private const val GPIO_HOLE_SIZE = 6

private fun sha1HexChunked(reader: MemoryReader, baseAddr: Long, size: Long): String {
    val chunkSize = 1 shl 20 // 1 MB - comfortably under pkReadBytes' 16 MB cap
    val digest = java.security.MessageDigest.getInstance("SHA-1")
    var off = 0L
    while (off < size) {
        val len = minOf(chunkSize.toLong(), size - off).toInt()
        val chunk = reader.readCoreMemory(baseAddr + off, len)
        val holeStart = GPIO_HOLE_START - (baseAddr + off)
        if (holeStart in 0 until len) {
            val holeEnd = minOf(len.toLong(), holeStart + GPIO_HOLE_SIZE)
            for (i in holeStart until holeEnd) chunk[i.toInt()] = 0
        }
        digest.update(chunk)
        off += len
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
