package com.pokedaisy.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.pokedaisy.app.companion.data.LookArt
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.LookEngine
import com.pokedaisy.app.companion.data.LookPart
import com.pokedaisy.app.companion.data.MonArt
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.RetailDumps
import com.pokedaisy.app.companion.data.TextArt
import com.pokedaisy.app.companion.data.TextBlob
import com.pokedaisy.app.companion.data.TextLook
import java.io.File

/**
 * PLAYER LOOK for the game running ([PlayerLook]): one background pass when a ROM loads scans it (keeping its own
 * heroes' graphics for the other games, [LookArt]), looks through the library's other FireRed / LeafGreen / Ruby /
 * Sapphire / Emerald ROMs for heroes not kept yet (once per file: `rom-art/look/scanned.txt`), and builds the patch for every hero
 * either gender of player could wear - then lets go of the ROM's bytes. [apply] hands the engine the one for the
 * save's gender ([female]: read from the save file at load, then followed in the game's own RAM) and the player's
 * pick for this ROM ([Prefs.playerLookFor]).
 */
class PlayerLookRunner(private val context: Context, private val onPatch: (PlayerLook.Patch?) -> Unit) {
    /** The heroes this game can wear (empty: not a FireRed / LeafGreen / Emerald ROM, or none kept yet) - for SETTINGS. */
    val offered = mutableStateOf<List<LookChar>>(emptyList())

    /** The game is one PLAYER LOOK knows (whether or not another hero is kept yet). */
    val supported = mutableStateOf(false)

    /** The game has a rival of the other gender to dress up too (RIVAL LOOK): Emerald, Ruby, Sapphire. */
    val rivalSupported get() = engine?.let { PlayerLook.hasRival(it) } == true

    /** The hero worn now (null = the game's own) - for the companion's MAP head and CARD. */
    val worn get() = PlayerLook.worn

    /** The pick for this ROM as SETTINGS shows it: [worn], or before the game has a gender the one that will go on. */
    val picked = mutableStateOf<LookChar?>(null)

    /** The player's own hero (the picker's OFF tile); null before the game has a gender. */
    val own = mutableStateOf<LookChar?>(null)

    /** TEXT LOOK: the other games' text looks this one can take ([TextLook]; empty: none kept, or not a game of ours). */
    val textOffered = mutableStateOf<List<LookEngine>>(emptyList())

    /** The text look on now (null = the game's own). */
    val textPicked = mutableStateOf<LookEngine?>(null)

    /** PLAYER LOOK's Pokémon: the party's species (national numbers) whose sprites are kept ([MonArt]), as
     * "MON:<number>" picks, each with its name in the game ([monNames]). */
    val offeredMons = mutableStateOf<List<Int>>(emptyList())
    @Volatile var monNames: Map<Int, String> = emptyMap()
        private set
    @Volatile private var party: List<Int> = emptyList()
    /** The running ROM's scan, kept for the Pokémon's patches (built when one is picked, [monPatch]). */
    @Volatile private var keptScan: PlayerLook.Scan? = null
    @Volatile private var keptKnown: Map<LookChar, Map<LookPart, ByteArray>> = emptyMap()
    private val monPatches = java.util.concurrent.ConcurrentHashMap<Pair<Boolean, Int>, PlayerLook.Patch>()
    private val building = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Boolean, Int>>()

    @Volatile private var romKey: String? = null
    @Volatile private var engine: LookEngine? = null
    /** By (female, look, rival dressed too). */
    @Volatile private var patches: Map<Triple<Boolean, LookChar?, Boolean>, PlayerLook.Patch?> = emptyMap()
    /** TEXT LOOK's blocks by style. */
    @Volatile private var textPatches: Map<LookEngine, List<PlayerLook.Block>> = emptyMap()
    @Volatile var female: Boolean? = null
        private set
    private var generation = 0

    /** A ROM loaded: forget the last one's patches and start this one's pass (off the UI thread). */
    fun load(rom: File, key: String, code: String, save: File?) {
        val gen = ++generation
        romKey = key
        engine = PlayerLook.engineFor(code)
        patches = emptyMap()
        textPatches = emptyMap()
        keptScan = null
        monPatches.clear()
        textOffered.value = emptyList()
        textPicked.value = null
        offered.value = emptyList()
        worn.value = null
        picked.value = null
        own.value = null
        supported.value = engine != null
        female = save?.let { runCatching { genderInSave(it.readBytes()) }.getOrNull() }
        val engine = engine ?: run {
            // No look for this game, but FOLLOWER may still be on: its Pokémon (this ROM's, if it has following
            // Pokémon, and the library's) are kept all the same.
            Thread({
                try {
                    val filesDir = context.filesDir
                    MonArt.keep(filesDir, rom.readBytes())
                    if (MonArt.sources(filesDir).size < MonArt.Source.entries.size) scanLibrary(rom)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (gen == generation) offeredMons.value = party.filter { it in MonArt.available(filesDir) }
                    }
                } catch (t: Throwable) {
                    Log.w("pokedaisy", "player look: Pokémon scan failed", t)
                }
            }, "player-look-mon").apply { isDaemon = true; start() }
            return
        }
        Thread({
            try {
                val t0 = System.nanoTime()
                val bytes = rom.readBytes()
                val scan = PlayerLook.scan(bytes, TextBlob.entries)
                val filesDir = context.filesDir
                LookArt.keep(filesDir, scan)
                TextArt.keep(filesDir, scan)
                MonArt.keep(filesDir, bytes)
                // Only retail carts (a collection dump of one too, or one randomized: the randomizer leaves the hero's
                // pictures alone) and the hacks played dressed up headless ([PlayerLook.HACKS]) wear another look: a hack
                // may load its hero's pictures or palettes its own way. Every ROM's heroes are still kept for the other games.
                val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
                val rev = bytes.getOrNull(0xBC)?.toInt()?.and(0xFF)
                val retail = GameTitles.isRetail(sha1) || sha1 in PlayerLook.HACKS ||
                    RetailDumps.matchStream(code, rev, bytes.size.toLong()) { bytes.inputStream() } != null ||
                    com.pokedaisy.app.companion.data.RandomizedRetail.matchStream(code, rev, bytes.size.toLong()) { bytes.inputStream() } != null
                if (!retail) {
                    Log.i("pokedaisy", "player look: not a retail cart or a known hack, none offered")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { if (gen == generation) supported.value = false }
                    return@Thread
                }
                if (LookArt.available(filesDir).size < LookChar.entries.size || TextLook.STYLES.any { TextArt.load(filesDir, it) == null } ||
                    MonArt.sources(filesDir).size < MonArt.Source.entries.size) scanLibrary(rom)
                val known = LookChar.entries.mapNotNull { c -> LookArt.load(filesDir, c)?.let { c to it } }.toMap()
                val built = HashMap<Triple<Boolean, LookChar?, Boolean>, PlayerLook.Patch?>()
                val rivals = if (PlayerLook.hasRival(engine)) listOf(false, true) else listOf(false)
                for (f in listOf(false, true)) {
                    val me = LookChar.of(engine, f)
                    for ((look, art) in known) if (look != me) for (r in rivals) built[Triple(f, look, r)] = PlayerLook.build(scan, me, look, art, known, r)
                    // The game's own: no blocks, any palette a state left from another look put back.
                    val off = PlayerLook.Patch(emptyList(), PlayerLook.settleBoth(scan, me, null, null, known), scan.data(me, LookPart.OW_PAL))
                    for (r in rivals) built[Triple(f, null, r)] = off
                }
                val host = TextLook.styleOf(engine)
                val texts = if (host == null) emptyMap() else TextLook.STYLES.filter { it != host }.mapNotNull { st ->
                    TextArt.load(filesDir, st)?.let { art -> TextLook.build(scan, host, st, art).takeIf { it.isNotEmpty() }?.let { st to it } }
                }.toMap()
                Log.i("pokedaisy", "player look: ${scan.chars} in this ROM, kept ${known.keys}, text looks ${texts.keys}, " +
                    "${built.count { it.value?.blocks?.isNotEmpty() == true }} patches in ${(System.nanoTime() - t0) / 1_000_000} ms")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (gen != generation) return@post
                    patches = built
                    textPatches = texts
                    keptScan = scan
                    keptKnown = known
                    offeredMons.value = party.filter { it in MonArt.available(filesDir) }
                    apply()
                }
            } catch (t: Throwable) {
                Log.w("pokedaisy", "player look: scan failed", t)
            }
        }, "player-look").apply { isDaemon = true; start() }
    }

    /** The game's own gender read (SaveBlock2's playerGender; -1 = not yet): a new game picks one in the intro. */
    fun onGender(gender: Int) {
        if (gender !in 0..1 || engine == null) return
        val f = gender == 1
        if (female == f) return
        female = f
        apply()
    }

    /** The pick for this ROM ([Prefs.playerLookFor]) changed, or the patches / gender came in: the engine gets the
     * patch now wanted. */
    fun apply() {
        val key = romKey ?: return
        if (patches.isEmpty()) return
        val prefs = Prefs(context)
        textOffered.value = textPatches.keys.toList()
        val text = textPatches.keys.firstOrNull { it.name == prefs.textLookFor(key) }
        textPicked.value = text
        val textBlocks = text?.let { textPatches[it] }.orEmpty()
        val f = female
        if (f == null) {
            // No gender yet (a new game, before the intro asks): every hero either player could wear; the pick goes
            // on once the game has one.
            offered.value = LookChar.entries.filter { c -> listOf(false, true).any { patches[Triple(it, c, false)]?.blocks?.isNotEmpty() == true } }
            worn.value = null
            own.value = null
            picked.value = LookChar.byName(prefs.playerLookFor(key))?.takeIf { it in offered.value }
            onPatch(PlayerLook.Patch(textBlocks, emptyList()).takeIf { textBlocks.isNotEmpty() })
            return
        }
        val me = LookChar.of(engine ?: return, f)
        own.value = me
        offered.value = LookChar.entries.filter { it != me && patches[Triple(f, it, false)]?.blocks?.isNotEmpty() == true }
        val saved = prefs.playerLookFor(key)
        // A Pokémon: its patch built off the UI thread the first time, then this again.
        MonArt.monOf(saved)?.takeIf { it in offeredMons.value }?.let { n ->
            pickedMon.value = n
            worn.value = null
            picked.value = null
            val patch = monPatch(f, me, n) ?: return
            onPatch(if (textBlocks.isEmpty()) patch else PlayerLook.Patch(patch.blocks + textBlocks, patch.palettes, patch.playerPal))
            return
        }
        pickedMon.value = null
        val pick = LookChar.byName(saved)?.takeIf { it in offered.value }
        worn.value = pick
        picked.value = pick
        val rival = prefs.rivalLook && PlayerLook.hasRival(me.engine)
        val look = patches[Triple(f, pick, rival)] ?: patches[Triple(f, pick, false)]
        onPatch(if (textBlocks.isEmpty()) look else PlayerLook.Patch(look?.blocks.orEmpty() + textBlocks, look?.palettes.orEmpty(), look?.playerPal))
    }

    /** The Pokémon picked (a national number; null: a hero or the game's own). */
    val pickedMon = mutableStateOf<Int?>(null)

    /** The party changed: its species (national numbers, with their names in the game) are the Pokémon offered. */
    fun onParty(nationals: List<Int>, names: Map<Int, String>) {
        if (nationals == party) return
        party = nationals
        monNames = names
        if (keptScan == null) return
        offeredMons.value = nationals.filter { it in MonArt.available(context.filesDir) }
        apply()
    }

    /** [n]'s patch for the player ([female], [me]), or null while it's built (on its own thread, then [apply] again). */
    private fun monPatch(female: Boolean, me: LookChar, n: Int): PlayerLook.Patch? {
        monPatches[female to n]?.let { return it }
        val scan = keptScan ?: return null
        if (!building.add(female to n)) return null
        val gen = generation
        Thread({
            try {
                val mon = MonArt.load(context.filesDir, n) ?: return@Thread
                val own = { p: LookPart -> scan.data(me, p) }
                // The party's other Pokémon: a state made wearing one of them may have loaded its palette.
                val extra = offeredMons.value.filter { it != n }.mapNotNull { MonArt.load(context.filesDir, it) }.mapNotNull { PlayerLook.wearMon(me, it, own) }
                val patch = PlayerLook.buildMon(scan, me, mon, keptKnown, extra)
                if (patch != null) monPatches[female to n] = patch
                Log.i("pokedaisy", "player look: ${if (patch == null) "no" else patch.blocks.size} blocks for Pokémon $n")
            } catch (t: Throwable) {
                Log.w("pokedaisy", "player look: Pokémon $n failed", t)
            } finally {
                building.remove(female to n)
                android.os.Handler(android.os.Looper.getMainLooper()).post { if (gen == generation && monPatches.containsKey(female to n)) apply() }
            }
        }, "player-look-mon").apply { isDaemon = true; start() }
        return null
    }

    /** Heroes, text looks and following Pokémon still missing are looked for in the library's other FireRed / LeafGreen
     * / Ruby / Sapphire / Emerald ROMs (the hacks with following Pokémon carry those game codes), each once. */
    private fun scanLibrary(running: File) {
        val filesDir = context.filesDir
        val seenFile = File(LookArt.dir(filesDir), "scanned.txt")
        val seen = runCatching { seenFile.readLines().toMutableSet() }.getOrDefault(mutableSetOf())
        fun done() = LookArt.available(filesDir).size == LookChar.entries.size && TextLook.STYLES.all { TextArt.load(filesDir, it) != null } &&
            MonArt.sources(filesDir).size == MonArt.Source.entries.size
        for (f in runCatching { RomFolder.libraryRoms(context, Prefs(context)) }.getOrDefault(emptyList())) {
            if (done()) break
            val id = "$LIBRARY_SCAN_VERSION|${f.absolutePath}|${f.length()}|${f.lastModified()}"
            if (id in seen || runCatching { f.canonicalPath == running.canonicalPath }.getOrDefault(false)) continue
            seen += id
            if (PlayerLook.engineFor(RomIdentity.gameCode(f)) == null) continue
            val bytes = runCatching { RomArchive.open(f).use { it.readBytes() } }.getOrNull() ?: continue
            val scan = PlayerLook.scan(bytes, TextBlob.entries)
            val kept = LookArt.keep(filesDir, scan)
            val text = TextArt.keep(filesDir, scan)
            val mons = MonArt.keep(filesDir, bytes)
            Log.i("pokedaisy", "player look: ${f.name} gave $kept, text $text, $mons Pokémon")
        }
        runCatching { seenFile.parentFile?.mkdirs(); seenFile.writeText(seen.joinToString("\n")) }
    }

    companion object {
        /** In every `scanned.txt` line: a bump looks through the library again (2: Ruby / Sapphire's heroes, 3: Heart
         * and Soul's, 4: the text looks, 5: Heart and Soul's Pokémon, 6: R.O.W.E.'s, Recharged Yellow's, Seaglass's). */
        private const val LIBRARY_SCAN_VERSION = 6

        /**
         * The save's player gender (SaveBlock2 +8: after the 8-byte name in every Gen 3 game) from the save file's newer
         * slot - sector id 0 (SaveBlock2) with the higher save counter. Null if no sector checks out.
         */
        fun genderInSave(save: ByteArray): Boolean? {
            var best: Long = -1
            var gender: Int? = null
            for (s in 0 until minOf(32, save.size / 0x1000)) {
                val base = s * 0x1000
                fun u32(o: Int) = (save[base + o].toLong() and 0xFF) or ((save[base + o + 1].toLong() and 0xFF) shl 8) or
                    ((save[base + o + 2].toLong() and 0xFF) shl 16) or ((save[base + o + 3].toLong() and 0xFF) shl 24)
                val id = (save[base + 0xFF4].toInt() and 0xFF) or ((save[base + 0xFF5].toInt() and 0xFF) shl 8)
                if (u32(0xFF8) != 0x08012025L || id != 0) continue
                val counter = u32(0xFFC)
                val g = save[base + 8].toInt() and 0xFF
                if (counter > best && g <= 1) { best = counter; gender = g }
            }
            return gender?.let { it == 1 }
        }
    }
}
