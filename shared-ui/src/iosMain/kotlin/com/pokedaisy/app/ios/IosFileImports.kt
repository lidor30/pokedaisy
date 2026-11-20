package com.pokedaisy.app.ios

import com.pokedaisy.app.game.RomPicker
import kotlinx.coroutines.*
import okio.Path
import okio.Path.Companion.toPath
import platform.UIKit.UIViewController

/** One native picker lifetime and off-thread reader for game presentation imports. */
internal object IosFileImports {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var picking = false

    fun <T> pick(from: UIViewController, before: () -> Unit, read: (Path) -> T, done: (T?) -> Unit) {
        if (picking) return
        picking = true
        fun finish(result: T?) { picking = false; done(result) }
        try {
            before()
            val presented = RomPicker.pickData(from) { path ->
                if (path == null) finish(null) else scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { read(path.toPath()) }.getOrNull() }
                    finish(result)
                }
            }
            if (!presented) finish(null)
        } catch (_: Throwable) { finish(null) }
    }
}
