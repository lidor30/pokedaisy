package com.pokedaisy.app

import okio.Path

/** Distinct battery destinations for linked cartridges, including repeats of the same cartridge. */
object LinkSavePaths {
    sealed interface Preparation {
        class Ready(val paths: List<Path>, val primarySlots: List<Int>) : Preparation
        class Failed(val reason: String) : Preparation
    }

    /** Recover pending save edits before resolving destinations or preparing any batteries. */
    fun <T> prepare(
        games: List<T>, recover: () -> Boolean, saveFor: (T) -> Path, prepareBattery: (Path) -> String?,
    ): Preparation {
        if (!recover()) return Preparation.Failed(ClosedSaveEdits.recoveryError())
        val own = games.map(saveFor)
        val paths = forGames(own)
        var failure: String? = null
        for (path in paths) {
            val reason = prepareBattery(path)
            if (failure == null) failure = reason
        }
        if (failure != null) return Preparation.Failed(failure)
        return Preparation.Ready(paths, paths.indices.filter { paths[it] == own[it] })
    }

    fun forGames(own: List<Path>): List<Path> {
        val reserved = own.toSet()
        val used = mutableSetOf<Path>()
        return own.map { save ->
            if (used.add(save)) return@map save
            var copy = 2
            val stem = save.name.substringBeforeLast('.')
            val extension = save.name.substringAfterLast('.', "")
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            val parent = requireNotNull(save.parent)
            var candidate: Path
            do {
                candidate = parent / "$stem (link ${copy++})$suffix"
            } while (candidate in reserved || !used.add(candidate))
            candidate
        }
    }
}
