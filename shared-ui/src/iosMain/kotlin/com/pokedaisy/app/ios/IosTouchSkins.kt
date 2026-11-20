package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.DeltaSkinStore
import com.pokedaisy.app.companion.SkinSystem
import com.pokedaisy.app.companion.TouchSkinSession
import platform.UIKit.UIViewController

/** Only Files presentation and off-thread import scheduling; Delta parsing, storage and selection policy are shared. */
internal object IosTouchSkins {
    val store by lazy { DeltaSkinStore(appFilesDir() / "skins") }
    private val imports by lazy {
        TouchSkinSession(store, { IosTouchPreferences.style }, { IosTouchPreferences.style = it })
    }
    fun selected(gameBoy: Boolean, shown: Boolean = true) = if (!shown) null else
        IosTouchPreferences.style.skinFor(if (gameBoy) SkinSystem.GB else SkinSystem.GBA)?.let(store::load)

    fun pick(from: UIViewController, before: () -> Unit, done: (Pair<String, String>?) -> Unit) {
        IosFileImports.pick(from, before, read = imports::import, done = { result ->
            var notice: Pair<String, String>? = null
            try {
                notice = result?.let(imports::complete)
            } finally { done(notice) }
        })
    }

    fun delete(id: String) { imports.delete(id) }
}
