package io.github.lumiagg.gestureevo.utils.root

import org.junit.Assert.assertEquals
import org.junit.Test

class RootShellTest {
    @Test
    fun quotePreservesArgumentsWithSpaces() {
        assertEquals("'cache folder/file.png'", RootShell.quote("cache folder/file.png"))
    }

    @Test
    fun quoteEscapesSingleQuotes() {
        assertEquals("'one'\"'\"'two'", RootShell.quote("one'two"))
    }
}
