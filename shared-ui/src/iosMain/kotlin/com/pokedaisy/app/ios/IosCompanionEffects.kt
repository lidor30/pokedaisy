package com.pokedaisy.app.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.pokedaisy.app.CompanionShaders
import org.jetbrains.skia.impl.use
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import platform.Foundation.NSLog

/** Skia transport for the same premultiplied UI shader Android runs through RuntimeShader. */
internal object IosCompanionEffects {
    private val program by lazy {
        runCatching { RuntimeEffect.makeForShader(CompanionShaders.SOURCE) }
            .onFailure { NSLog("PokeDaisy: companion shader failed: %@", it.message) }.getOrNull()
    }

    fun imageFilter(settings: CompanionShaders.Settings, cell: Float): ImageFilter? {
        if (!settings.active) return null
        val effect = program ?: return null
        return RuntimeShaderBuilder(effect).use { builder ->
            builder.uniform("uColors", if (settings.colors) 1f else 0f)
            builder.uniform("uFilter", settings.filter.ordinal.toFloat())
            builder.uniform("uUnit", cell)
            builder.uniform("uGrid", settings.gridX, settings.gridY)
            ImageFilter.makeRuntimeShader(builder, "content", null)
        }
    }

    @Composable
    fun effect(settings: CompanionShaders.Settings, cell: Float): RenderEffect? = remember(settings, cell) {
        imageFilter(settings, cell)?.asComposeRenderEffect()
    }
}
