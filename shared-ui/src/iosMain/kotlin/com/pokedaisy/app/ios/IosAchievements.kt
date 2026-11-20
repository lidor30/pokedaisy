package com.pokedaisy.app.ios

import com.pokedaisy.app.achievements.RaSession
import com.pokedaisy.app.companion.data.RomIdentityReader
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.game.IosRaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

internal object IosAchievements {
    val session = RaSession(IosRaClient, { iosPreferences }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    fun start() {
        val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
        session.init("PokeDaisy/$version (iOS ${UIDevice.currentDevice.systemVersion})")
    }

    fun attach(rom: Path, restoredProgress: ByteArray?, track: Boolean) {
        val hash = lazy(LazyThreadSafetyMode.NONE) { if (track) RomIdentityReader.forPath(rom).raHash() else null }
        session.onCoreStarted({ hash.value }, restoredProgress)
    }

    fun writeState(path: Path): Boolean {
        if (!PkApiCore.saveState(path.toString())) return false
        session.writeProgressForState(path)
        return true
    }
}
