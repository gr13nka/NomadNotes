package com.nomadnotes.core

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Stable identity types for the note model.
 *
 * Every persisted entity carries one of these so that a lasso-drawn "button" (a Phase 2 link
 * from a region of one page to another notebook/page) can reference its target by a value that
 * never changes when the entity is edited, reordered, or re-serialized.
 *
 * Each is a [JvmInline] value class over the UUID text, which means:
 *  - at runtime it is just the wrapped [String] (no allocation), and
 *  - in JSON it is written as that plain string, not as a `{ "value": ... }` object,
 *    because kotlinx-serialization inlines a single-property value class to its content.
 *
 * Use [random] to mint a fresh id; wrap an existing string with the constructor when reading
 * one back from storage.
 */
@JvmInline
@Serializable
value class NotebookId(val value: String) {
    companion object {
        fun random(): NotebookId = NotebookId(UUID.randomUUID().toString())
    }
}

@JvmInline
@Serializable
value class PageId(val value: String) {
    companion object {
        fun random(): PageId = PageId(UUID.randomUUID().toString())
    }
}

@JvmInline
@Serializable
value class LayerId(val value: String) {
    companion object {
        fun random(): LayerId = LayerId(UUID.randomUUID().toString())
    }
}

@JvmInline
@Serializable
value class StrokeId(val value: String) {
    companion object {
        fun random(): StrokeId = StrokeId(UUID.randomUUID().toString())
    }
}

@JvmInline
@Serializable
value class LinkId(val value: String) {
    companion object {
        fun random(): LinkId = LinkId(UUID.randomUUID().toString())
    }
}
