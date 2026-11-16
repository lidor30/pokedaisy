package com.pokedaisy.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.pokedaisy.app.companion.ui.DocsSource
import okio.Path.Companion.toOkioPath

/** Android supplies the installed cache location, app version and bitmap decoder to the common implementation. */
class StoreDocsSource(context: Context) : DocsSource by CachedDocsSource(DocsStore.cache(context), { bytes ->
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
})

/** Compatibility entry points used by the library's background prefetch. */
object DocsStore {
    internal fun cache(context: Context) =
        DocsCache.at(context.applicationContext.filesDir.toOkioPath() / "docs", BuildConfig.VERSION_NAME)
    fun prefetch(context: Context) = cache(context).prefetch()
}
