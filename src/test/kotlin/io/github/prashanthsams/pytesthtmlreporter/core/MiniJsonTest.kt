package io.github.prashanthsams.pytesthtmlreporter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `parses nested documents`() {
        val value = MiniJson.parse("""{"a": [1, 2.5, -3e2, true, false, null], "b": {"c": "d"}, "e": []}""") as Map<*, *>
        assertEquals(listOf(1L, 2.5, -300.0, true, false, null), value["a"])
        assertEquals(mapOf("c" to "d"), value["b"])
        assertEquals(emptyList<Any>(), value["e"])
    }

    @Test
    fun `decodes escapes and unicode`() {
        val value = MiniJson.parse(""""line\nbreak \"q\" \\ \/ ü 名"""")
        assertEquals("line\nbreak \"q\" \\ / ü 名", value)
    }

    @Test
    fun `keeps key order`() {
        val value = MiniJson.parse("""{"z": 1, "a": 2, "m": 3}""") as Map<*, *>
        assertEquals(listOf("z", "a", "m"), value.keys.toList())
    }

    @Test
    fun `rejects malformed input`() {
        listOf("", "{", "[1,]", "{\"a\" 1}", "\"unterminated", "tru", "{} trailing", "\"\u0001\"").forEach { text ->
            assertThrows(text, MiniJson.JsonException::class.java) { MiniJson.parse(text) }
        }
    }

    @Test
    fun `reads typed values`() {
        val value = MiniJson.parse("""{"s": "x", "o": {}, "l": [], "b": true}""") as Map<*, *>
        assertEquals("x", value.stringValue("s"))
        assertEquals(emptyMap<Any, Any>(), value.objectValue("o"))
        assertEquals(emptyList<Any>(), value.listValue("l"))
        assertEquals(true, value.booleanValue("b"))
        assertNull(value.stringValue("o"))
    }
}
