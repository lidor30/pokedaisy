package com.pokedaisy.app

import com.pokedaisy.app.companion.data.FfMusicKey
import com.pokedaisy.app.companion.data.Gen1Music
import com.pokedaisy.app.companion.data.Gen1MusicTables
import com.pokedaisy.app.companion.data.MemoryReader
import okio.Path

/** Pairs a game's live song keys with the renderer that writes clips for those keys. */
sealed class SteadyMusicSource {
    private data object Gba : SteadyMusicSource()
    private class GameBoy(val tables: Gen1MusicTables) : SteadyMusicSource()

    fun current(reader: MemoryReader): String? = when (this) {
        Gba -> FfMusicKey.current(reader)
        is GameBoy -> Gen1Music.current(reader, tables)
    }

    fun renderer(
        rom: Path,
        crc: String,
        cache: FfMusicCache,
        host: MusicRenderHost,
        click: RenderedGameSound? = null,
        fanfare: RenderedGameSound? = null,
        prefetch: Boolean = true,
    ): SongRenderer = when (this) {
        Gba -> FfMusicRenderer(rom, crc, cache, host, click, fanfare, prefetch)
        is GameBoy -> Gen1MusicRenderer(rom, tables, cache, host)
    }

    companion object {
        /** The host supplies the SHA-1 for a Game Boy cart. Other systems have their own audio paths. */
        fun forCartridge(gba: Boolean, gameBoySha1: () -> String?): SteadyMusicSource? =
            if (gba) Gba else gameBoySha1()?.let(Gen1Music::forSha1)?.let(::GameBoy)
    }
}
