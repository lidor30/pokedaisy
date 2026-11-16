package com.pokedaisy.app

import com.pokedaisy.app.companion.data.RomIdentityReader
import java.io.File
import okio.source

/** Android's archive-aware file adapter; all header/fingerprint rules live in the common reader. */
object RomIdentity {
    private fun reader(rom: File) = RomIdentityReader(
        readAtSource = { at, count -> RomArchive.readAt(rom, at, count) },
        openSource = { RomArchive.open(rom).source() })
    fun gameCode(rom: File) = reader(rom).gameCode()
    fun isGameBoy(rom: File) = reader(rom).isGameBoy()
    fun isGameBoyColor(rom: File) = reader(rom).isGameBoyColor()
    fun isNds(rom: File) = reader(rom).isNds()
    fun ndsGameCode(rom: File) = reader(rom).ndsGameCode()
    fun ndsRevision(rom: File) = reader(rom).ndsRevision()
    fun looksLikeRom(rom: File) = reader(rom).looksLikeRom()
    fun headerTitle(rom: File) = reader(rom).headerTitle()
    fun revision(rom: File) = reader(rom).revision()
    fun header(rom: File, nds: Boolean = false) = reader(rom).header(nds)
    fun sha1(rom: File) = reader(rom).sha1()
    fun md5(rom: File) = reader(rom).md5()
    fun raHash(rom: File) = reader(rom).raHash()
    fun raConsole(rom: File) = reader(rom).raConsole()
    fun ndsRaHash(rom: File) = reader(rom).ndsRaHash()
}
