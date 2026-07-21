package com.nomadnotes.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Signals that a text handed to [NotesJson] is not a valid stored document of the requested type:
 * it is malformed JSON, or JSON that does not match the page/notebook schema.
 *
 * It exists so the JSON library stays an implementation detail of [NotesJson]. Callers (notably the
 * storage layer) catch this single core type to recognise a corrupt file, rather than depending on
 * kotlinx-serialization's own exception classes.
 */
class NotesFormatException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * The one place the note model is turned into stored JSON and back.
 *
 * Every persisted file goes through this object so the on-disk format has a single definition.
 * The configured [Json] is kept module-internal as [format]; callers reach the format only
 * through the typed helpers, so there is no way to encode the model with a different config.
 *
 * Two settings shape the forward/backward-compatibility contract, and both matter:
 *  - `ignoreUnknownKeys = true` — a file written by a *newer* app version can carry top-level
 *    keys this version has never heard of; decoding tolerates them instead of throwing.
 *  - `encodeDefaults = true` — default-valued fields (versions, `visible`, empty `extra`) are
 *    always written, so a stored file is explicit and self-describing rather than relying on
 *    the reader to supply defaults.
 *
 * **Preservation contract.** Tolerating an unknown key is not the same as keeping it: an
 * unknown key sitting at top level is dropped on the next encode. The *only* fields that
 * survive a decode -> encode roundtrip untouched are those a newer version deliberately placed
 * inside a modelled `extra` map (see [Page.extra], [Stroke.extra]). Design rule for evolving
 * the format: put forward-compatible data under `extra`, never as a new bare top-level key,
 * or an older build will silently discard it.
 */
object NotesJson {
    internal val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodePage(page: Page): String = format.encodeToString(page)

    /** Decodes a page document; throws [NotesFormatException] if [text] is not a valid page. */
    fun decodePage(text: String): Page =
        try {
            format.decodeFromString(text)
        } catch (e: SerializationException) {
            throw NotesFormatException("Not a valid page document", e)
        }

    fun encodeNotebook(notebook: Notebook): String = format.encodeToString(notebook)

    /** Decodes a notebook document; throws [NotesFormatException] if [text] is not a valid notebook. */
    fun decodeNotebook(text: String): Notebook =
        try {
            format.decodeFromString(text)
        } catch (e: SerializationException) {
            throw NotesFormatException("Not a valid notebook document", e)
        }
}
