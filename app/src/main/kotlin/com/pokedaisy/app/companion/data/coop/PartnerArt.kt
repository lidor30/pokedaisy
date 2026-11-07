package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.data.LookArt
import com.pokedaisy.app.companion.data.LookBlob
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.LookPart
import com.pokedaisy.app.companion.data.PlayerLook
import java.io.File
import java.util.zip.CRC32

/**
 * The co-op partner's art (pk_wide.c's `pk_partner_set`): 42 frames of 32x32 4bpp in reading order, then the
 * 16-colour palette, all from this device's own copy of the hero (never sent over the link: only a look id is).
 *
 * Frames 0-8 walking, 9-17 running, 18-26 Mach Bike, 27-35 Acro Bike - each sheet's first nine, the order of Emerald's
 * hero sheets (stand S / N / W, then S step A, S step B, N A, N B, W A, W B) - 36-38 surfing (S / N / W: the surf sheet's frames 0, 2, 4 of six, as the game's pic table picks them) and 39-41 the
 * surf blob (S / N / W, from the ROM). 16x32 frames are centred ([PlayerLook.widen]); east is west flipped.
 */
object PartnerArt {
    const val FRAMES = 42
    const val ART_BYTES = FRAMES * 512 + 32

    private const val BLOB_BYTES = 3 * 512

    /** (sheet, which of its frames), in the packed order; the blob's three follow. */
    private val FIRST_NINE = (0..8).toList()
    private val SHEETS = listOf(
        LookPart.EM_WALKING to FIRST_NINE, LookPart.EM_RUNNING to FIRST_NINE, LookPart.EM_MACH_BIKE to FIRST_NINE,
        LookPart.EM_ACRO_BIKE to FIRST_NINE, LookPart.EM_SURFING to listOf(0, 2, 4),
    )

    /**
     * [me]'s art ([own], from [LookArt]) packed, worn as [look] ([worn]: its graphics) when both are given, with the
     * surf blob read from [rom] (retail Emerald's: null if its bytes aren't there). Within Emerald [look]'s own palette
     * is used as is; another game's hero goes into [me]'s palette around the colours the blob shares ([PlayerLook.wear]).
     */
    fun pack(own: Map<LookPart, ByteArray>, worn: Map<LookPart, ByteArray>?, me: LookChar, look: LookChar?, rom: ByteArray): ByteArray? {
        val blob = surfBlob(rom) ?: return null
        return assemble(own, worn, me, look, blob)
    }

    /**
     * The partner as this device draws them: [female]'s own hero (Brendan / May) from `rom-art/look/`, or scanned out of
     * [rom] and kept there first; dressed as [look] only if all its graphics are kept here. Null when [rom]
     * isn't retail Emerald (no surf blob), the hero can't be had, or [look] isn't kept here (ask again with null for the plain hero).
     */
    fun build(filesDir: File, rom: ByteArray, female: Boolean, look: LookChar?): ByteArray? {
        if (surfBlob(rom) == null) return null
        val me = if (female) LookChar.MAY else LookChar.BRENDAN
        val own = LookArt.load(filesDir, me) ?: run {
            val scan = PlayerLook.scan(rom)
            LookArt.keep(filesDir, scan)
            LookArt.load(filesDir, me) ?: LookBlob.entries.filter { it.char == me }.associate { b -> b.part to (scan.data(me, b.part) ?: return null) }
        }
        // A look this device lacks is null, not plain: the caller's cache would keep the plain art under the look's key for good.
        val worn = look?.takeIf { it != me }?.let { LookArt.load(filesDir, it) ?: return null }
        return pack(own, worn, me, if (worn != null) look else null, rom)
    }

    /** The blob's three frames in [rom], or null if they aren't retail Emerald's. */
    private fun surfBlob(rom: ByteArray): ByteArray? {
        val at = CoopEmeraldGen.SURF_BLOB_PIC
        if (rom.size < at + BLOB_BYTES) return null
        val bytes = rom.copyOfRange(at, at + BLOB_BYTES)
        return bytes.takeIf { CRC32().apply { update(it) }.value == CoopEmeraldGen.SURF_BLOB_CRC }
    }

    /** [pack] with the blob's bytes already read. */
    internal fun assemble(own: Map<LookPart, ByteArray>, worn: Map<LookPart, ByteArray>?, me: LookChar, look: LookChar?, blob: ByteArray): ByteArray? {
        if (blob.size != BLOB_BYTES) return null
        val ownOf = { p: LookPart -> own[p] }
        val dressed = look != null && worn != null && look != me
        val wear = if (dressed) PlayerLook.wear(me, look!!, worn!!, ownOf) ?: return null else null
        val out = ByteArray(ART_BYTES)
        var at = 0
        for ((part, picks) in SHEETS) {
            val frames = if (dressed) {
                PlayerLook.sheet(part, me, look!!, worn!!, ownOf) ?: return null
            } else {
                val data = own[part] ?: return null
                (0 until data.size / part.frameBytes).map { data.copyOfRange(it * part.frameBytes, (it + 1) * part.frameBytes) }
            }
            if (frames.size <= picks.max()) return null
            for (i in picks) {
                var f = frames[i]
                if (f.size == 256) f = PlayerLook.widen(f)
                if (f.size != 512) return null
                if (wear != null) f = PlayerLook.remap(f, wear.map)
                f.copyInto(out, at)
                at += 512
            }
        }
        // The blob is drawn with the hero's palette slot, whose shared colours [PlayerLook.wear] keeps: never remapped.
        blob.copyInto(out, at)
        at += BLOB_BYTES
        val pal = wear?.pal ?: own[LookPart.OW_PAL] ?: return null
        if (pal.size != 32) return null
        pal.copyInto(out, at)
        return out
    }
}
