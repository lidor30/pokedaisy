package com.pokedaisy.app.companion.ui

import android.content.Context
import com.pokedaisy.app.AndroidFonts
import com.pokedaisy.app.AndroidPictures
import com.pokedaisy.app.companion.ui.theme.FontSource

/** What PokeDaisyApp sets at start, for a screenshot test (the Application doesn't run there): the app's fonts from
 * [context]'s assets, and its picture codec. */
fun installTestHosts(context: Context) {
    FontSource.current = AndroidFonts(context)
    PictureCodec.current = AndroidPictures
}
