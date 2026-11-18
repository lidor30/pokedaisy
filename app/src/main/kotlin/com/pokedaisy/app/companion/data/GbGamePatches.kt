package com.pokedaisy.app.companion.data

/**
 * GAMEPLAY's ROM options on the Game Boy games (2026-11-18): INSTANT TEXT, REUSABLE TMS and LOW HP BEEP, each one small edit of
 * the cart's own code, found by its shape (pret's pokered / pokeyellow / pokegold / pokecrystal), every one unique in the cart
 * or none. Patched like the GBA's (`pk_cheats_patch`): only where the cart holds the shape, the cart's bytes kept for the
 * Poller's hash; addresses are [TAG] + the cart offset (bank * 0x4000 + the address in its bank).
 *
 * - INSTANT TEXT: PrintLetterDelay (its two flag checks first, `ld a, [nn]; bit n, a; ret nz` twice) returns at once: a `ret`
 *   on its first byte. Every letter goes down without a frame's wait; the A / B press still closes a page.
 * - REUSABLE TMS, Gen 1: ItemUseTMHM's end, `ld a, [wCurItem]; call IsItemHM; ret c; jp RemoveUsedItem`, then BootedUpTMText:
 *   the `ret c` made a `ret`. Gen 2: TeachTMHM's `ld a, [wCurItem]; call IsHM; ret c; ld c, ..; callfar ChangeHappiness;
 *   call ConsumeTM; jr`: the `call ConsumeTM` three `nop`s (the happiness the move brings stays).
 * - FIELD POISON off: Gen 1's ApplyOutOfBattlePoisonDamage skips its damage (the day care's EXP and Pikachu's mood still run),
 *   Gen 2's DoPoisonStep takes its "no party" way out.
 * - LOW HP BEEP off: the HUD's low-health check sets the alarm's bit (`ld hl, wLowHealthAlarm; set 7, [hl]`), Gen 1's
 *   DrawPlayerHUDAndHPBar.setLowHealthAlarm, Gen 2's CheckDanger.danger: the `set` two `nop`s. The music then never hears it.
 *
 * Found in Red, Blue, Yellow (every language), Gold, Silver, Crystal (rev 0, rev 1, Australia) and the hacks on that code
 * (Brown, Yellow Legacy, Crystal Clear, Crystal Legacy); PureRed / PureBlue's text check is three places, so INSTANT TEXT
 * isn't offered there. Played headless (mgba_dump `rompatch`).
 */
object GbGamePatches {
    const val TAG = 0x10000000L

    class Found(val tms: List<ReusableTms.Site>, val text: InstantText.Patch?, val beep: InstantText.Patch?, val poison: InstantText.Patch? = null)

    private const val ANY = -1
    private val TEXT = sig("fa????cb??c0fa????cb??c8")
    private val TM1 = sig("fa????cd????d8c3????17??????50")
    private val TM2 = sig("fa????cd????d80e??21????3e??cfcd????18")
    private val BEEP1 = sig("21????cb7e3600c8afea????c921????cbfec9")
    private val BEEP2 = sig("21????cbbe18??21????cbfec9")
    private val POISON1 = sig("fa????e603c2????ea????21????")
    private val POISON2 = sig("fa????a728??af0e??21????")

    /** What [rom] (a whole Game Boy cart) has; null for anything that isn't one. */
    fun find(rom: ByteArray): Found? {
        if (rom.size < 0x8000 || rom.size > 0x800000) return null
        fun one(s: IntArray): Int? = hits(rom, s).singleOrNull()
        // Each write checks the cart's bytes from its own address: the whole shape for the text, the `set 7, [hl]; ret` for the beep.
        val text = one(TEXT)?.let { at ->
            InstantText.Patch(listOf(InstantText.Write(TAG + at, rom.copyOfRange(at, at + TEXT.size), byteArrayOf(0xC9.toByte()))))
        }
        val tms = (one(TM1)?.let { at -> Triple(at, TM1, 6 to byteArrayOf(0xC9.toByte())) }
            ?: one(TM2)?.let { at -> Triple(at, TM2, 15 to byteArrayOf(0, 0, 0)) })
            ?.let { (at, s, p) -> listOf(ReusableTms.Site(TAG + at, rom.copyOfRange(at, at + s.size), TAG + at + p.first, p.second)) }
            .orEmpty()
        val beep = (one(BEEP1)?.let { it + 16 } ?: one(BEEP2)?.let { it + 10 })?.let { at ->
            InstantText.Patch(listOf(InstantText.Write(TAG + at, rom.copyOfRange(at, at + 3), byteArrayOf(0, 0))))
        }
        // FIELD POISON off: Gen 1's `jp nz, .skipPoisonEffectAndSound` (every fourth step) made a `jp` - the day care's EXP and
        // Pikachu's mood before it still run; Gen 2's DoPoisonStep's `jr z, .no_faint` (no party) made a `jr`.
        val poison = one(POISON1)?.let { at -> Triple(at, POISON1, 5 to 0xC3) } ?: one(POISON2)?.let { at -> Triple(at, POISON2, 4 to 0x18) }
        val poisonPatch = poison?.let { (at, s, p) ->
            InstantText.Patch(listOf(InstantText.Write(TAG + at + p.first, rom.copyOfRange(at + p.first, at + s.size), byteArrayOf(p.second.toByte()))))
        }
        return Found(tms, text, beep, poisonPatch)
    }

    /** A Game Boy cart file: its header's logo check (0x104) and size. */
    fun find(path: okio.Path): Found? = runCatching {
        val bytes = okio.FileSystem.SYSTEM.read(path) { readByteArray() }
        if (!isGameBoyRom(bytes)) null else find(bytes)
    }.getOrNull()

    private fun isGameBoyRom(b: ByteArray) = b.size >= 0x150 && (b[0x104].toInt() and 0xFF) == 0xCE && (b[0x105].toInt() and 0xFF) == 0xED

    private fun hits(rom: ByteArray, s: IntArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + s.size <= rom.size) {
            var ok = true
            for (k in s.indices) if (s[k] != ANY && (rom[i + k].toInt() and 0xFF) != s[k]) { ok = false; break }
            if (ok) out += i
            i++
        }
        return out
    }

    private fun sig(hex: String) = IntArray(hex.length / 2) { i ->
        val p = hex.substring(i * 2, i * 2 + 2)
        if (p == "??") ANY else p.toInt(16)
    }
}
