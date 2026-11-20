package com.pokedaisy.app.companion.data.save

/** Read the saves for collection and Missing Dex in the library's order. Blocking. */
object SaveLibrary {
    class Collection<T>(val sources: List<T>, val sides: List<TradeSide>, val items: List<SaveCollection.Item>)

    class Missing<T>(val sources: List<T>, val sides: List<TradeSide>, val entries: List<MissingDex.Entry>)

    fun <K, T : Any> collection(
        candidates: List<K>, read: (K) -> T?, side: (T) -> TradeSide,
    ): Collection<T> {
        val sources = candidates.mapNotNull(read)
        val sides = sources.map(side)
        return Collection(sources, sides, SaveCollection.gather(sides))
    }

    fun <K, T : Any> missing(
        target: TradeSide, candidates: List<K>, read: (K) -> T?, side: (T) -> TradeSide,
    ): Missing<T> {
        val sources = candidates.mapNotNull(read)
        val sides = sources.map(side)
        val entries = runCatching { MissingDex.find(target, sides) }.getOrDefault(emptyList())
        return Missing(sources, sides, entries)
    }
}
