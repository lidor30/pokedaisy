package com.pokedaisy.app

import android.view.KeyEvent
import com.pokedaisy.app.companion.data.KeyCodes
import org.junit.Assert.assertEquals
import org.junit.Test

/** KeyCodes' table is KeyEvent's KEYCODE_ constants, every one, and its named constants are KeyEvent's. */
class KeyCodesTest {
    private val keyEvent = KeyEvent::class.java.fields
        .filter { it.name.startsWith("KEYCODE_") && it.type == Int::class.javaPrimitiveType }
        .associate { it.name.removePrefix("KEYCODE_") to it.getInt(null) }

    @Test fun everyKeyEventCode() {
        for ((name, code) in keyEvent) {
            assertEquals(name, code, KeyCodes.code(name))
            assertEquals(name, KeyCodes.name(code))
        }
    }

    @Test fun namedConstants() {
        for (f in KeyCodes::class.java.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && !it.name.startsWith("$") }) {
            assertEquals(f.name, keyEvent.getValue(f.name), f.getInt(null))
        }
    }

    @Test fun unknownCodesAreNumbers() {
        assertEquals("9999", KeyCodes.name(9999))
        assertEquals(9999, KeyCodes.code("9999"))
        assertEquals(null, KeyCodes.code("NOT_A_KEY"))
    }
}
