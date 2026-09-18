package com.nomadnotes.core

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down the stored-file contract of [NotesJson]: exact roundtrips, plain-string ids, and
 * the forward-compatibility rules (modelled `extra` is preserved; unknown top-level keys are
 * tolerated but not kept).
 */
class SerializationTest {

    private fun sampleStroke(id: String, x: Float = 10f) = Stroke(
        id = StrokeId(id),
        tool = Tool.PEN,
        widthBase = 2.5f,
        grayLevel = 200,
        points = listOf(
            StrokePoint(x = x, y = 20f, pressure = 0.4f, timestampDelta = 0L),
            StrokePoint(x = x + 5f, y = 25f, pressure = 0.6f, timestampDelta = 8L),
        ),
    )

    private fun samplePage() = Page(
        id = PageId("page-1"),
        templateRef = "grid-5mm",
        layers = listOf(
            Layer(id = LayerId("layer-main"), name = "Main", strokes = listOf(sampleStroke("s1"))),
            Layer(id = LayerId("layer-2"), name = "Layer 2", visible = false, strokes = emptyList()),
        ),
        mainLayerId = LayerId("layer-main"),
    )

    @Test
    fun `page survives an encode-decode roundtrip unchanged`() {
        val page = samplePage()
        assertEquals(page, NotesJson.decodePage(NotesJson.encodePage(page)))
    }

    @Test
    fun `page with links round-trips`() {
        val page = samplePage().copy(
            links = listOf(
                PageLink(
                    id = LinkId("link-1"),
                    region = PageRect(10f, 20f, 110f, 60f),
                    targetNotebookId = NotebookId("nb-target"),
                    targetPageId = PageId("page-target"),
                ),
            ),
        )
        assertEquals(page, NotesJson.decodePage(NotesJson.encodePage(page)))
    }

    @Test
    fun `page json without links field decodes with empty links`() {
        // A page written before links existed simply has no `links` key; dropping it from an
        // encoded page reproduces that older on-disk shape.
        val legacyJson = NotesJson.encodePage(samplePage()).replace(",\"links\":[]", "")
        assertFalse("precondition: the links key was removed", legacyJson.contains("\"links\""))

        val decoded = NotesJson.decodePage(legacyJson)

        assertTrue(decoded.links.isEmpty())
        assertEquals(1, decoded.formatVersion)
    }

    @Test
    fun `layer json without images field decodes with empty images`() {
        // A page written before images existed simply has no `images` key on its layers; dropping it
        // from an encoded page reproduces that older on-disk shape.
        val legacyJson = NotesJson.encodePage(samplePage()).replace(",\"images\":[]", "")
        assertFalse("precondition: the images key was removed", legacyJson.contains("\"images\""))

        val decoded = NotesJson.decodePage(legacyJson)

        assertTrue(decoded.layers.all { it.images.isEmpty() })
        assertEquals(1, decoded.formatVersion)
    }

    @Test
    fun `a page carrying an image survives an encode-decode roundtrip unchanged`() {
        val page = samplePage().let { page ->
            val layer = page.layers.first()
            page.copy(
                layers = page.layers.map {
                    if (it.id == layer.id) {
                        it.copy(
                            images = listOf(
                                PageImage(
                                    id = ImageId("img-1"),
                                    assetRef = "photo.png",
                                    rect = PageRect(10f, 20f, 210f, 180f),
                                ),
                            ),
                        )
                    } else {
                        it
                    }
                },
            )
        }
        assertEquals(page, NotesJson.decodePage(NotesJson.encodePage(page)))
    }

    @Test
    fun `notebook survives an encode-decode roundtrip unchanged`() {
        val notebook = Notebook(
            id = NotebookId("nb-1"),
            name = "Journal",
            pageIds = listOf(PageId("page-1"), PageId("page-2")),
            createdAtEpochMs = 1_700_000_000_000L,
        )
        assertEquals(notebook, NotesJson.decodeNotebook(NotesJson.encodeNotebook(notebook)))
    }

    @Test
    fun `ids are written as plain JSON strings, not wrapper objects`() {
        val json = NotesJson.encodePage(samplePage())
        assertTrue("page id should be a bare string", json.contains("\"id\":\"page-1\""))
        assertFalse("value class should inline, not nest a value object", json.contains("\"value\""))
    }

    @Test
    fun `extra fields are preserved through a roundtrip`() {
        val page = samplePage().copy(extra = mapOf("linkAnchor" to JsonPrimitive("btn-42")))
        val decoded = NotesJson.decodePage(NotesJson.encodePage(page))
        assertEquals(JsonPrimitive("btn-42"), decoded.extra["linkAnchor"])
        assertEquals(page, decoded)
    }

    @Test
    fun `stroke-level extra fields are preserved through a roundtrip`() {
        val stroke = sampleStroke("s1").copy(extra = mapOf("smoothed" to JsonPrimitive(true)))
        val page = samplePage().copy(
            layers = listOf(Layer(id = LayerId("layer-main"), name = "Main", strokes = listOf(stroke))),
        )
        val decoded = NotesJson.decodePage(NotesJson.encodePage(page))
        assertEquals(JsonPrimitive(true), decoded.layers[0].strokes[0].extra["smoothed"])
    }

    @Test
    fun `an unknown top-level key is tolerated on decode but not re-emitted`() {
        val page = samplePage()
        val withUnknown = NotesJson.encodePage(page)
            .replaceFirst("{", "{\"futureField\":123,")

        val decoded = NotesJson.decodePage(withUnknown)

        // Tolerated: decoding did not throw and produced the same page.
        assertEquals(page, decoded)
        // Not kept: the unknown key did not sneak into extra and is gone on re-encode.
        assertFalse(decoded.extra.containsKey("futureField"))
        assertFalse(NotesJson.encodePage(decoded).contains("futureField"))
    }

    @Test
    fun `defaults are written explicitly so stored files are self-describing`() {
        val json = NotesJson.encodePage(samplePage())
        assertTrue("formatVersion default should be emitted", json.contains("\"formatVersion\":1"))
    }

    @Test
    fun `a point with a nibFactor round-trips it`() {
        val stroke = sampleStroke("s1").copy(
            points = listOf(
                StrokePoint(x = 10f, y = 20f, pressure = 0.4f, timestampDelta = 0L, nibFactor = 0.75f),
            ),
        )
        val page = samplePage().copy(
            layers = listOf(Layer(id = LayerId("layer-main"), name = "Main", strokes = listOf(stroke))),
        )
        val decoded = NotesJson.decodePage(NotesJson.encodePage(page))
        assertEquals(0.75f, decoded.layers[0].strokes[0].points[0].nibFactor)
    }

    @Test
    fun `a point json without a nibFactor key decodes to a null factor`() {
        // The pre-existing shape: every point already in a stored file has no "nibFactor" key at
        // all, not an explicit null, since older backends and the touch fallback never wrote one.
        val json = NotesJson.encodePage(samplePage())
        assertFalse("precondition: no point carries the key yet", json.contains("nibFactor"))

        val decoded = NotesJson.decodePage(json)

        assertEquals(null, decoded.layers[0].strokes[0].points[0].nibFactor)
    }

    @Test
    fun `a null nibFactor is not written, unlike encodeDefaults'd fields`() {
        // encodeDefaults=true would normally re-emit every default-valued field explicitly (see
        // `defaults are written explicitly` above); nibFactor opts out of that (EncodeDefault.NEVER)
        // so a null factor never bloats a point-heavy page with a key that carries no information.
        val json = NotesJson.encodePage(samplePage())
        assertFalse("null nibFactor should be omitted, not written as null", json.contains("nibFactor"))
    }
}
