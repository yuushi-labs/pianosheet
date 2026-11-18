package com.yuushilabs.pianosheet.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release document is the one JSON the app parses, and it arrives from the
 * network: a truncated body or an HTML error page must come back as null, not
 * as a half-read release.
 */
class JsonTest {

    @Test
    fun `an object keeps its fields`() {
        val root = Json.parseOrNull("""{"tag_name":"v0.4.5-phase2","draft":false,"id":12}""")!!
        assertEquals("v0.4.5-phase2", root["tag_name"]?.asString())
        assertEquals(false, root["draft"]?.asBoolean())
        assertEquals(12L, root["id"]?.asLong())
    }

    @Test
    fun `strings are unescaped`() {
        val root = Json.parseOrNull("""{"body":"line1\nline2 \"quoted\" \u3042"}""")!!
        assertEquals("line1\nline2 \"quoted\" あ", root["body"]?.asString())
    }

    @Test
    fun `arrays of objects are read in order`() {
        val root = Json.parseOrNull(
            """{"assets":[{"name":"a.apk"},{"name":"b.apk"}]}""",
        )!!
        val names = root["assets"]?.asArray()?.map { it["name"]?.asString() }
        assertEquals(listOf("a.apk", "b.apk"), names)
    }

    @Test
    fun `null and missing fields read as absent`() {
        val root = Json.parseOrNull("""{"name":null}""")!!
        assertNull(root["name"]?.asString())
        assertNull(root["tag_name"])
    }

    @Test
    fun `a truncated document is rejected`() {
        assertNull(Json.parseOrNull("""{"tag_name":"v0.4.5"""))
    }

    @Test
    fun `trailing garbage is rejected`() {
        assertNull(Json.parseOrNull("""{"a":1} extra"""))
    }

    @Test
    fun `an html error page is rejected`() {
        assertNull(Json.parseOrNull("<!DOCTYPE html><html><body>Not Found</body></html>"))
    }

    @Test
    fun `an empty body is rejected`() {
        assertNull(Json.parseOrNull(""))
    }

    @Test
    fun `numbers may be negative or fractional`() {
        val root = Json.parseOrNull("""{"a":-3,"b":1.5}""")!!
        assertEquals(-3L, root["a"]?.asLong())
        assertTrue(root["b"] is JsonValue.Num)
    }
}
