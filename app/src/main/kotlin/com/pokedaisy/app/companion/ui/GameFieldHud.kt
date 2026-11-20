package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.hasData

/** Both hosts show the field overlay only after the core's settled field signal. */
fun fieldHudShown(enabled: Boolean, portrait: Boolean, onField: Boolean, snapshot: SnapshotView) =
    enabled && !portrait && onField && snapshot.hasData && !snapshot.unsupported && !snapshot.inBattle && snapshot.blocked == null
