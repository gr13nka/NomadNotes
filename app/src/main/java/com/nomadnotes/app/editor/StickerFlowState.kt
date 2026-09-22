package com.nomadnotes.app.editor

import com.nomadnotes.core.LinkId
import com.nomadnotes.core.Notebook
import com.nomadnotes.core.PageId

/**
 * What EditorActivity's sticker panel is doing: attaching a sticker to a link not yet on the page
 * (its region and already-chosen target are pinned; only the sticker is still open) or replacing
 * the sticker of one that already exists. Mirrors the picker's own Create/EditTarget split one step
 * later in the same flow — the panel does not care which brought it here beyond what committing the
 * flow needs to finish.
 */
internal sealed interface StickerFlowMode {
    /** A link the picker has a target for, not yet added to the page — the panel's Done/Skip adds it. */
    data class Create(
        val bounds: SelectionBounds,
        val targetNotebook: Notebook,
        val targetPageId: PageId,
    ) : StickerFlowMode

    /** An existing link whose sticker is being replaced. */
    data class Edit(val linkId: LinkId) : StickerFlowMode
}

/**
 * The sticker panel's live state while it is open: which flow brought it up ([mode]) and the
 * [draft] accumulating the user's pen input. A dedicated type rather than more `uiXxx` fields on
 * `EditorActivity`, which already carries about a dozen — the create and edit sticker flows share
 * this one instead of spreading across still more scattered fields.
 */
internal class StickerFlowState(
    val mode: StickerFlowMode,
    val draft: StickerDraft,
)
