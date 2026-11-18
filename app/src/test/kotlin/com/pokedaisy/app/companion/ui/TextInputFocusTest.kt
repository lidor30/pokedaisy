package com.pokedaisy.app.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInputFocusTest {
    @Test fun disposingThePreviousFieldDoesNotReleaseTheNewFieldsKeyboard() {
        val changes = mutableListOf<Boolean>()
        val focus = TextInputFocus { changes += it }
        val first = Any(); val second = Any()
        focus.set(first, true); focus.set(second, true); focus.set(first, false)
        assertEquals(listOf(true), changes)
        focus.set(second, false)
        assertEquals(listOf(true, false), changes)
    }
    @Test fun closingTheHostReleasesFocusOnceDespiteLateFieldCallbacks() {
        val changes = mutableListOf<Boolean>()
        val focus = TextInputFocus { changes += it }
        val field = Any()
        focus.set(field, true); focus.set(field, true); focus.clear(); focus.set(field, false); focus.clear()
        assertEquals(listOf(true, false), changes)
    }
}
