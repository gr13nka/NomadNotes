package com.nomadnotes.app.ui.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import com.nomadnotes.app.InkShade
import com.nomadnotes.app.LayerRow
import com.nomadnotes.app.StrokeWidth
import com.nomadnotes.core.LayerId
import com.nomadnotes.core.Tool

/**
 * The shapes the editor chrome is built from (`docs/superpowers/specs/2026-09-18-things-eink-ui-design.md`,
 * mockups in the sibling `.html`). `EditorActivity` keeps the state itself, exactly as it does
 * today — these are plain, immutable snapshots of it — and implements one `XxxActions` interface
 * per panel with one-line delegates to the mutating methods that already exist
 * (`selectTool`, `setWidth`, `goToPage`, `toggleLayerVisible`, ...). `EditorBar.kt`, `ToolPanel.kt`,
 * `PagePanel.kt` and `MorePanel.kt` read only what is declared here, so they can be built in
 * parallel against this file without seeing `EditorActivity` at all.
 *
 * **Which panel is open** is the single [EditorPanel] the Activity holds as `uiOpenPanel`. Layers
 * and Template are not panels of their own: they are [MorePanelState] data shown by a sub-page the
 * More panel keeps as its *own* local `remember` state, since the spec keeps that swap ("Layers ›"
 * to a "‹" back row) inside one panel with no Activity round-trip. Likewise, the Page panel's
 * inline delete confirm ("delete #12? [yes] [no, keep]") is local state inside `PagePanel.kt` —
 * [PagePanelActions] only ever hears the final, confirmed [PagePanelActions.onDeleteCurrentPage].
 *
 * **Where a panel anchors** is not carried in state. Each bar glyph reports its own window-pixel
 * bounds at the moment it is tapped (`Modifier.onGloballyPositioned { it.boundsInWindow() }`, the
 * same coordinate space `EditorActivity.updateToolbarExclude` already converts from) and
 * [EditorBarActions]' `onToggleXPanel(anchor: Rect)` methods carry that [Rect] up. The alternative —
 * threading a remembered [Rect] through [EditorBarState] — would still need `EditorBar.kt` to report
 * bounds *somewhere*, just via an extra hop through state instead of the click handler that already
 * has them. The Activity remembers the anchor for whichever [EditorPanel] is currently open and
 * feeds it to that panel's [PanelAnchor].
 */
internal enum class EditorPanel { NONE, TOOL, PAGE, MORE, FIND }

/**
 * The five brackets the Tool panel's top row offers, and the bar's own "current tool" glyph. Core
 * [Tool] only names the three ink tools ([PEN], [PENCIL], [MARKER]) it can stamp on a [Stroke];
 * the eraser and lasso are `EditorActivity`'s own `uiEraser`/`uiLasso` flags layered on top (see
 * `applyCaptureMode`), not members of [Tool] — this enum is what lets the bar and panel name all
 * five as one choice.
 */
internal enum class EditorTool { PEN, PENCIL, MARKER, ERASER, LASSO }

/** [PEN]/[PENCIL]/[MARKER] map onto core [Tool] 1:1; the eraser and lasso have no [Tool] of their own. */
internal fun EditorTool.asCoreTool(): Tool? = when (this) {
    EditorTool.PEN -> Tool.PEN
    EditorTool.PENCIL -> Tool.PENCIL
    EditorTool.MARKER -> Tool.MARKER
    EditorTool.ERASER, EditorTool.LASSO -> null
}

// ---------------------------------------------------------------------------------------------
// Editor bar
// ---------------------------------------------------------------------------------------------

/**
 * What the bar looks like right now — one of three unrelated shapes, not a flat bag of optional
 * fields, because at most one is ever true and a `when` here is exhaustive where a pile of nullable
 * fields would just move the "which combination is valid" question into `EditorBar.kt`.
 */
internal sealed interface BarMode {

    /**
     * At rest: `[≡] [✎ pen] #12 of 40 ⋯⋯ [⌕] [⋯]`, with paste inserted just before ⌕ while
     * [canPaste]. Chosen whenever no lasso outcome is up, i.e. neither a stroke selection nor a
     * circled link or image — see [Selection].
     *
     * The jump-back link is *not* one of that row's glyphs: mockup screen 6 draws it as its own
     * descriptive line beneath the bar (`[← back to research #3]`, not a bare `[←]`), and the
     * mockup wins over this file's own design-doc ASCII sketch per the spec's tie-break rule. See
     * [jumpBackLabel].
     *
     * [findEnabled] is `false` for the whole of Phase 1: ⌕ is drawn muted and inert so the bar's
     * layout does not shift again when Phase 3 wires Quick Find in.
     */
    data class Normal(
        val activeTool: EditorTool,
        /** 0-based, same indexing as `EditorActivity.uiPageIndex`; the bar displays `index + 1`. */
        val pageIndex: Int,
        val pageCount: Int,
        /**
         * `null` hides the jump-back line entirely (`uiJumpDepth == 0`); otherwise the label of
         * the page jumped *from* (e.g. `"research #3"`), rendered as the single bracketed phrase
         * `[← back to research #3]` on its own line under the bar — mockup screen 6. Replaces an
         * earlier `canJumpBack: Boolean` once the mockup showed a descriptive line rather than a
         * bare `[←]` glyph.
         */
        val jumpBackLabel: String?,
        val canPaste: Boolean,
        val findEnabled: Boolean,
    ) : BarMode

    /**
     * A lasso outcome is up. This is not one of three exclusive variants: the current model lets a
     * stroke selection, a circled link, and a circled image all be true at once (a single lasso can
     * catch strokes and circle a link in the same gesture), each contributing its own verbs — so
     * this carries one independent, nullable/boolean slot per outcome rather than a closed enum.
     *
     * `×` always deselects everything at once regardless of which slots are filled (mirrors
     * `EditorActivity.clearSelection`, which already clears all three together).
     *
     * @param strokeCount `null` when no strokes are selected (a link or image may still be
     *   circled with nothing caught); otherwise the count the status label reads as
     *   "N strokes caught".
     * @param canLink whether the "link" verb shows; true only when [strokeCount] is non-null *and*
     *   the selection sits on the page's main layer (mirrors `uiSelectionOnMainLayer`) — always
     *   `false` when [strokeCount] is `null`.
     * @param circledLink shows "edit link"/"delete link" independently of [strokeCount].
     * @param circledImage shows "delete image" independently of [strokeCount].
     */
    data class Selection(
        val strokeCount: Int?,
        val canLink: Boolean,
        val circledLink: Boolean,
        val circledImage: Boolean,
    ) : BarMode

    /** Chrome hidden ("Just the page"): a single 48 dp `[≡]` in the top-left corner, nothing else. */
    data object Hidden : BarMode
}

/** The bar's current appearance. A data class of one field so the interesting shape lives in [BarMode]. */
internal data class EditorBarState(val mode: BarMode)

/**
 * What each bar glyph does. Every `onToggleXPanel` both opens its panel and closes it back to
 * [EditorPanel.NONE] on a second call — the Activity, which already tracks `uiOpenPanel`, decides
 * which; this interface only carries the anchor a fresh *open* would need (see the file doc's
 * "Where a panel anchors").
 */
internal interface EditorBarActions {
    /** `[≡]` in [BarMode.Normal]/[BarMode.Selection]: close the editor, back to the library. */
    fun onOpenLibrary()

    /** `[≡]` in [BarMode.Hidden]: bring the bar back. A different action from [onOpenLibrary] on the same glyph shape. */
    fun onShowChrome()

    /** `[✎ <tool>]`: open or close the Tool panel, anchored at [anchor] (the glyph's window bounds). */
    fun onToggleToolPanel(anchor: Rect)

    /** The `#N of M` counter: open or close the Page panel, anchored at [anchor]. */
    fun onTogglePagePanel(anchor: Rect)

    /** The jump-back line, shown only while `BarMode.Normal.jumpBackLabel` is non-null. */
    fun onJumpBack()

    /** The paste verb, shown only while `BarMode.Normal.canPaste`. */
    fun onPaste()

    /** `[⌕]`: open or close Find, anchored at [anchor]. Muted and never called while `findEnabled` is false. */
    fun onToggleFind(anchor: Rect)

    /** `[⋯]`: open or close the More panel, anchored at [anchor]. */
    fun onToggleMorePanel(anchor: Rect)

    /** `[×]` in [BarMode.Selection]: deselect everything (strokes, circled link, circled image) at once. */
    fun onDeselect()

    /** `[copy]`, shown while `Selection.strokeCount != null`. */
    fun onCopySelection()

    /** `[link]`, shown while `Selection.canLink`. */
    fun onLinkSelection()

    /** `[delete]`, shown while `Selection.strokeCount != null`. */
    fun onDeleteSelection()

    /** `[edit link]`, shown while `Selection.circledLink`. */
    fun onEditCircledLink()

    /** `[delete link]`, shown while `Selection.circledLink`. */
    fun onDeleteCircledLink()

    /** `[delete image]`, shown while `Selection.circledImage`. */
    fun onDeleteCircledImage()
}

// ---------------------------------------------------------------------------------------------
// Tool panel
// ---------------------------------------------------------------------------------------------

/**
 * The Tool panel's state. [width]/[shade]/[smoothingOn] are `null` exactly when that row does not
 * apply to [activeTool] — the eraser and lasso are not ink tools, so all three are omitted for
 * them, not just disabled; [ToolPanel.kt] renders a row iff its field is non-null. [width] and
 * [shade] name the live [StrokeWidth]/[InkShade] preset directly (both widened to `internal` in
 * `EditorActivity.kt` for exactly this) rather than a pre-rendered label, because clamping which
 * end can still step is a one-line `ordinal` check on either 3-value enum
 * (`width.ordinal > 0` / `width.ordinal < StrokeWidth.entries.lastIndex`) — cheap enough that
 * duplicating it as precomputed booleans here would only add a way for the two to disagree.
 */
internal data class ToolPanelState(
    val activeTool: EditorTool,
    val width: StrokeWidth?,
    val shade: InkShade?,
    /** `true` = [com.nomadnotes.core.ink.SmoothingLevel.AUTO], `false` = `OFF`; `null` omits the row. */
    val smoothingOn: Boolean?,
)

/**
 * Choosing a tool via [onSelectTool] both switches it and **closes the panel** (the spec: "you
 * opened it to switch tools, and now you are done"); the other four keep it open so more than one
 * can be adjusted in a visit. [onSelectTool] routes an [EditorTool] to `selectTool`/`selectEraser`/
 * `selectLasso` via [asCoreTool] — see that function's doc.
 */
internal interface ToolPanelActions {
    fun onSelectTool(tool: EditorTool)
    fun onDecreaseWidth()
    fun onIncreaseWidth()
    fun onDecreaseShade()
    fun onIncreaseShade()
    fun onToggleSmoothing()
}

// ---------------------------------------------------------------------------------------------
// Page panel
// ---------------------------------------------------------------------------------------------

/**
 * The Page panel's state: `[‹ prev] #12 of 40 [next ›]`, a [strip] of page indices to draw as
 * cells (from `com.nomadnotes.core.pageWindow(pageIndex, pageCount)`, so the strip is always
 * `2*radius+1` wide except near either end), and whether insert/delete are available.
 *
 * The inline delete confirmation ("delete #12? [yes] [no, keep]") is *not* here: it is local
 * `remember`ed state inside `PagePanel.kt`, which only calls [PagePanelActions.onDeleteCurrentPage]
 * once the user has confirmed — see the file doc.
 *
 * @param pageIndex 0-based, same indexing as `EditorActivity.uiPageIndex`; cells and the counter
 *   display `index + 1`.
 * @param thumbnailFor Phase 2 hook. `null` for the whole of Phase 1 (number cells only); once a
 *   thumbnail cache exists this becomes a lookup from a [strip] index to its rendered
 *   [ImageBitmap], and `PagePanel.kt` falls back to the `#N` cell for any index it returns `null`
 *   for (not yet cached) or while this field itself is `null` (Phase 1).
 */
internal data class PagePanelState(
    val pageIndex: Int,
    val pageCount: Int,
    val canGoPrev: Boolean,
    val canGoNext: Boolean,
    val canDelete: Boolean,
    val strip: IntRange,
    val thumbnailFor: ((pageIndex: Int) -> ImageBitmap?)? = null,
)

/**
 * [onSelectPage] opens that page **and closes the panel** (the spec says so explicitly for the
 * strip). [onPrevPage]/[onNextPage] do not — nothing in the spec closes the panel on a plain
 * page-to-page step, and closing it would defeat paging through several pages in one visit; left to
 * the Activity/`PagePanel.kt` wiring if that turns out wrong on the device pass.
 */
internal interface PagePanelActions {
    fun onPrevPage()
    fun onNextPage()
    fun onSelectPage(pageIndex: Int)
    fun onInsertPageAfterCurrent()
    /** Called once, after `PagePanel.kt`'s own inline confirm says yes — never a raw button tap. */
    fun onDeleteCurrentPage()
}

// ---------------------------------------------------------------------------------------------
// More panel
// ---------------------------------------------------------------------------------------------

/**
 * The More panel's state: the root list's [canUndo]/[canRedo], plus the Layers and Template data
 * their sub-pages need. Which sub-page shows (root, Layers, or Template, behind a `‹` back row) is
 * local state inside `MorePanel.kt`, not here — see the file doc — so this is always fully
 * populated regardless of which sub-page is currently visible; the Activity should keep
 * [templateFiles] fresh (`loadTemplateFiles`) whenever the More panel opens, since there is no
 * Activity callback for navigating *into* the Template row to trigger it on demand.
 *
 * [layers] and [activeLayerId] mirror `uiLayers`/`uiActiveLayer` verbatim ([LayerRow] widened to
 * `internal` for exactly this); a row's own [LayerRow.isMain] is what `MorePanel.kt` reads to
 * disable that row's delete, so no separate "can remove" field is carried here.
 */
internal data class MorePanelState(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val layers: List<LayerRow>,
    val activeLayerId: LayerId?,
    /** `uiLayers.size < PageEditSession.MAX_LAYERS`, precomputed so `MorePanel.kt` need not import it. */
    val canAddLayer: Boolean,
    val templateRef: String?,
    val templateFiles: List<String>,
)

/**
 * Root-level actions plus the Layers and Template sub-page actions (reached through
 * `MorePanel.kt`'s own local sub-page state, per the file doc — there is no `onOpenLayers`/
 * `onOpenTemplate` here because entering either sub-page needs nothing from the Activity beyond
 * the [MorePanelState] it already has).
 */
internal interface MorePanelActions {
    fun onInsertImage()
    fun onUndo()
    fun onRedo()
    fun onHideToolbar()

    fun onToggleLayerVisible(id: LayerId, visible: Boolean)
    fun onSelectActiveLayer(id: LayerId)
    fun onAddLayer()
    fun onRemoveLayer(id: LayerId)

    /** `null` selects [com.nomadnotes.app.render.TemplateRef.BLANK]; otherwise one of the built-ins or a user file ref. */
    fun onSelectTemplate(ref: String?)
}
