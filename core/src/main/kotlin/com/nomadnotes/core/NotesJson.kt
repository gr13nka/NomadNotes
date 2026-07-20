package com.nomadnotes.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun decodePage(text: String): Page = format.decodeFromString(text)

    fun encodeNotebook(notebook: Notebook): String = format.encodeToString(notebook)

    fun decodeNotebook(text: String): Notebook = format.decodeFromString(text)
}
