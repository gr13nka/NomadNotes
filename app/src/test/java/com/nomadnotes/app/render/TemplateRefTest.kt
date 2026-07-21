package com.nomadnotes.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing of stored template refs into [TemplateRef]; the pure, Android-free half of templates. */
class TemplateRefTest {

    @Test
    fun `a null ref is a blank page`() {
        assertEquals(TemplateRef.Blank, TemplateRef.parse(null))
    }

    @Test
    fun `the three built-in refs parse to their templates`() {
        assertEquals(TemplateRef.Blank, TemplateRef.parse("builtin:blank"))
        assertEquals(TemplateRef.Lines, TemplateRef.parse("builtin:lines"))
        assertEquals(TemplateRef.Grid, TemplateRef.parse("builtin:grid"))
    }

    @Test
    fun `a user ref carries its filename verbatim`() {
        assertEquals(TemplateRef.UserImage("sketch.png"), TemplateRef.parse("user:sketch.png"))
    }

    @Test
    fun `a user filename may contain dots`() {
        assertEquals(TemplateRef.UserImage("my.notes.v2.jpeg"), TemplateRef.parse("user:my.notes.v2.jpeg"))
    }

    @Test
    fun `an empty user filename is unknown`() {
        assertNull(TemplateRef.parse("user:"))
    }

    @Test
    fun `an unrecognised ref is unknown`() {
        assertNull(TemplateRef.parse("builtin:dots"))
        assertNull(TemplateRef.parse("garbage"))
        assertNull(TemplateRef.parse(""))
    }

    @Test
    fun `toRef is the inverse of parse for every non-null result`() {
        for (ref in listOf("builtin:blank", "builtin:lines", "builtin:grid", "user:a.png", "user:x.y.z.webp")) {
            val parsed = TemplateRef.parse(ref)!!
            assertEquals(ref, TemplateRef.toRef(parsed))
        }
    }
}
