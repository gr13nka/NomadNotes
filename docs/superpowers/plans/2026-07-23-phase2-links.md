# Phase 2 — Links ("buttons") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> Spec: `docs/superpowers/specs/2026-07-23-phase2-links-design.md` — read it first; it is the authority on behavior.
> Format note: core tasks carry full code (they lock in contracts); app tasks are precise briefs — the implementer reads the integration sites and follows existing patterns. This is deliberate (project rule: architecture by Fable, code by Opus subagents).

**Goal:** Supernote-style links: lasso a main-layer region → attach a target page (this or another notebook) → pen-tap jumps, «←» walks back; lasso-circling a link edits/deletes it.

**Architecture:** Links are first-class `Page.links: List<PageLink>` bound to a region (not strokes), edited through new inverse-carrying `PageEditSession` commands. Tap detection is a pure classifier on the existing INK `onStrokeFinished` path (no new input plumbing). Cross-notebook jumps stay inside `EditorActivity` (flush → load → install). Affordances render in the `PageRenderer` composite, so no new Onyx rules apply.

**Tech Stack:** Existing toolchain (Kotlin 2.0.21, kotlinx-serialization, Compose, vendored Onyx SDK). No new dependencies.

**Standing rules for every task:**
- Verify: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` green. ONE Gradle invocation at a time; `./gradlew --stop` after the final build of the task. Never start an emulator.
- Commit per task, message in English, **no AI/Claude attribution or co-author lines**.
- TDD: write the failing test first where a test target exists (`:core`, app unit).

---

### Task 1: `:core` model — LinkId, LinkRegion, PageLink, Page.links

**Files:**
- Modify: `core/src/main/kotlin/com/nomadnotes/core/Ids.kt` (add `LinkId`, mirroring `StrokeId` exactly — same annotations, same `random()` companion)
- Create: `core/src/main/kotlin/com/nomadnotes/core/model/PageLink.kt`
- Modify: `core/src/main/kotlin/com/nomadnotes/core/model/Page.kt` (add field)
- Test: `core/src/test/kotlin/com/nomadnotes/core/SerializationTest.kt` (extend)

- [ ] **Step 1: Failing tests** — in `SerializationTest`:
  - `page with links round-trips`: build a Page with one `PageLink(LinkId.random(), LinkRegion(10f, 20f, 110f, 60f), notebookId, pageId)`, encode → decode, assert equal.
  - `page json without links field decodes with empty links`: take a JSON string captured from encoding a link-free Page **with the `links` key removed manually** (regex or hand-built minimal JSON matching current schema), decode, assert `links.isEmpty()` and `formatVersion == 1`.
- [ ] **Step 2: Run `./gradlew :core:test` — expect compile failure (PageLink unresolved).**
- [ ] **Step 3: Implement.** `PageLink.kt`:

```kotlin
package com.nomadnotes.core.model

import com.nomadnotes.core.LinkId
import com.nomadnotes.core.NotebookId
import com.nomadnotes.core.PageId
import kotlinx.serialization.Serializable

/**
 * Axis-aligned rectangle in page coordinates. A link's tappable area — fixed at
 * creation time (selection bbox + padding); moving strokes does not move it.
 */
@Serializable
data class LinkRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * A Supernote-style link: a region of the page that, when tapped, jumps to a target
 * notebook page. Lives only on pages whose main layer carries the circled handwriting;
 * the strokes underneath remain ordinary ink. Targets are referenced by stable ids so
 * they survive notebook renames; resolution failure is a broken link handled by the UI.
 */
@Serializable
data class PageLink(
    val id: LinkId,
    val region: LinkRegion,
    val targetNotebookId: NotebookId,
    val targetPageId: PageId,
)
```

  `Page` gains `val links: List<PageLink> = emptyList()` (place after `mainLayerId`, before `formatVersion`; KDoc: main-layer-bound buttons, default keeps old files decoding, formatVersion stays 1).
- [ ] **Step 4: `./gradlew :core:test` — PASS.**
- [ ] **Step 5: Commit** `Add PageLink model with region-bound links on Page`.

---

### Task 2: `:core` — PageEditSession link commands (undo-capable)

**Files:**
- Modify: `core/src/main/kotlin/com/nomadnotes/core/edit/EditCommand.kt`
- Modify: `core/src/main/kotlin/com/nomadnotes/core/edit/PageEditSession.kt`
- Test: `core/src/test/kotlin/com/nomadnotes/core/edit/PageEditSessionTest.kt` (extend)

- [ ] **Step 1: Failing tests:**
  - `addLink appears on page and undoes cleanly` (add → present; undo → absent; redo → present).
  - `removeLink removes and undo restores the identical link` (same id, region, target).
  - `removeLink of absent id returns false and leaves history untouched` (canUndo unchanged).
  - `setLinkTarget changes target and undo restores previous target`.
  - `setLinkTarget with identical target is a no-op` (returns false, no history entry).
  - `link commands interleave with stroke commands in one history` (addStroke → addLink → undo undoes the link, undo again undoes the stroke).
- [ ] **Step 2: `./gradlew :core:test` — compile failure.**
- [ ] **Step 3: Implement.** Three commands following the existing `EditCommand`/`Applied(page, inverse)` pattern exactly:

```kotlin
internal data class AddLink(val link: PageLink) : EditCommand {
    override fun applyTo(page: Page): Applied? {
        if (page.links.any { it.id == link.id }) return null
        return Applied(page.copy(links = page.links + link), RemoveLink(link.id))
    }
}

internal data class RemoveLink(val id: LinkId) : EditCommand {
    override fun applyTo(page: Page): Applied? {
        val removed = page.links.firstOrNull { it.id == id } ?: return null
        return Applied(page.copy(links = page.links - removed), AddLink(removed))
    }
}

internal data class SetLinkTarget(
    val id: LinkId,
    val targetNotebookId: NotebookId,
    val targetPageId: PageId,
) : EditCommand {
    override fun applyTo(page: Page): Applied? {
        val link = page.links.firstOrNull { it.id == id } ?: return null
        if (link.targetNotebookId == targetNotebookId && link.targetPageId == targetPageId) return null
        val updated = link.copy(targetNotebookId = targetNotebookId, targetPageId = targetPageId)
        return Applied(
            page.copy(links = page.links.map { if (it.id == id) updated else it }),
            SetLinkTarget(id, link.targetNotebookId, link.targetPageId),
        )
    }
}
```

  (Adapt to the file's actual `applyTo`/null-for-no-op convention — inspect `SetTemplateRef` first and mirror it; if no-ops are expressed differently, follow the file, not this sketch.) Session API, naming matched to `setLayerVisible`/`setTemplateRef` (spec's `editLinkTarget` == `setLinkTarget` here):

```kotlin
fun addLink(link: PageLink)
fun removeLink(id: LinkId): Boolean
fun setLinkTarget(id: LinkId, targetNotebookId: NotebookId, targetPageId: PageId): Boolean
```

- [ ] **Step 4: `./gradlew :core:test` — PASS.**
- [ ] **Step 5: Commit** `Add undo-capable link commands to PageEditSession`.

---

### Task 3: `:core` geometry — tap hit + lasso-covers-link

**Files:**
- Modify/Create in `core/src/main/kotlin/com/nomadnotes/core/geometry/` (put next to `lassoSelect`; reuse its ray-casting — extract a shared `internal fun pointInPolygon(polygon: List<Vec2>, x: Float, y: Float): Boolean` if not already separate, refactoring `lassoSelect` to call it)
- Test: geometry test file alongside existing lasso tests

- [ ] **Step 1: Failing tests:**
  - `lassoCoversRegion true when region center inside polygon` (square polygon around region).
  - `lassoCoversRegion false when polygon only clips a corner` (center outside).
  - `lassoCoversRegion false for degenerate polygon` (< 3 points).
  - `pointInPolygon` cases if newly extracted: inside, outside, on-vertex tolerance not required (document boundary behavior as-is from lassoSelect).
- [ ] **Step 2: Run — compile failure.**
- [ ] **Step 3: Implement:**

```kotlin
/**
 * A lasso "circles" a link when the region's center lies inside the polygon —
 * the v1 criterion from the design spec (deliberately simpler than area overlap).
 */
fun lassoCoversRegion(polygon: List<Vec2>, region: LinkRegion): Boolean {
    if (polygon.size < 3) return false
    return pointInPolygon(polygon, region.centerX, region.centerY)
}
```

- [ ] **Step 4: `./gradlew :core:test` — PASS. Confirm existing lasso tests still green (refactor safety).**
- [ ] **Step 5: Commit** `Add lassoCoversRegion geometry for circling links`.

---

### Task 4: `:app` — link affordance rendering

**Files:**
- Create: `app/src/main/kotlin/com/nomadnotes/app/render/LinkRenderer.kt`
- Modify: `app/src/main/kotlin/com/nomadnotes/app/render/PageRenderer.kt` (recomposite step)

**Brief:** `LinkRenderer` mirrors `SelectionRenderer`'s structure/style: for each `PageLink`, draw on the composite canvas a thin (≈2 px) gray rounded-corner rectangle over `region` plus a small corner glyph in the top-right (a ≈12 px triangle path — draw it with `Path`, do not render text). `PageRenderer`'s recomposite draws affordances **after** all layers, **only when the main layer is visible** (`page.layers.first { it.id == page.mainLayerId }.visible`). No signature changes — `PageRenderer` already receives the `Page`. Affordances therefore appear in every blit (drag base bitmaps, persist-blits, erase renders) automatically; verify `renderFull(excludeStrokeIds=…)` path also composites them (it uses the same recomposite).

- [ ] **Step 1:** Implement `LinkRenderer` + the recomposite call. (Rendering is device-verified; no unit test — matches project precedent for `SelectionRenderer`.)
- [ ] **Step 2:** `./gradlew :app:assembleDebug` — green.
- [ ] **Step 3: Commit** `Render link affordances in the page composite`.

---

### Task 5: `:app` — create link from lasso (picker overlay)

**Files:**
- Modify: `app/src/main/kotlin/com/nomadnotes/app/EditorActivity.kt` (selection bar, overlay, state)
- Modify: `app/src/main/kotlin/com/nomadnotes/app/storage/NotebookStorage.kt` (+ `findNotebookById`)
- Test: storage unit test (extend existing NotebookStorage tests)

**Brief:**
1. `NotebookStorage.findNotebookById(id: NotebookId): Notebook?` = `listNotebooks().firstOrNull { it.id == id }`. TDD it: create two notebooks, find by id; rename one's directory on disk (simulating user rename — note name-from-directory is authoritative on load), assert the **id still resolves** and points at the renamed notebook.
2. Selection bar (`EditorActivity` FlowRow, selection-active branch): add **«Ссылка»** button, visible only when `selection.layerId == page.mainLayerId`. Tap (via `withChromeRefresh`, like Copy/Delete) opens the picker with `mode = Create(selection.bounds)`.
3. Picker overlay — a Compose overlay following the existing panel pattern (static layout, no scrolling, scrim, `NoIndication`): step 1 lists notebook names from `storage.listNotebooks()` on IO (current notebook first, labeled «этот блокнот»); step 2 shows page-number buttons `1..pageIds.size` in a `FlowRow`. Confirm → `session.addLink(PageLink(LinkId.random(), region = selection bbox padded by 8f page-px, targetNotebookId, targetPageId))` → `clearSelection()` → full re-render + present (clean pattern already used after erase). Cancel → just close.
4. State: one `uiLinkPicker: LinkPickerState?` (`sealed`/data class holding mode + chosen notebook), cleared on `onPause` like other transient chrome.

- [ ] **Step 1:** TDD `findNotebookById` (storage test first, red → green).
- [ ] **Step 2:** Implement selection-bar button + picker + addLink wiring.
- [ ] **Step 3:** `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` — green.
- [ ] **Step 4: Commit** `Create links from lasso selection via target picker`.

---

### Task 6: `:app` — pen-tap navigation + back stack

**Files:**
- Create: `app/src/main/kotlin/com/nomadnotes/app/editor/TapClassifier.kt`
- Modify: `app/src/main/kotlin/com/nomadnotes/app/EditorActivity.kt`
- Test: `app/src/test/.../TapClassifierTest.kt`

**Brief:**
1. `TapClassifier` — pure, JVM-testable:

```kotlin
/** A gesture is a tap when its points stay within MAX_SPREAD_PX of the first point
 *  and the whole gesture fits in MAX_DURATION_MS. Thresholds are device-tuned. */
object TapClassifier {
    const val MAX_SPREAD_PX = 6f
    const val MAX_DURATION_MS = 300L
    fun isTap(points: List<StrokePoint>): Boolean
}
```

   **Before implementing duration: read `StrokePoint.timestampDelta`'s KDoc** and compute elapsed time per its actual contract (delta-from-stroke-start → use `points.last()`; per-point delta → sum). Tests: single point = tap; tight slow cluster (duration over limit) = not tap; fast wide drag = not tap; boundary values.
2. `EditorActivity.penListener.onStrokeFinished`: before building the stroke — if `captureMode == INK && TapClassifier.isTap(points)`, hit-test `points.first()` against `session.page.links` (main layer visible required). Hit → **do not addStroke**, call `navigateToLink(link)`. Miss → existing path unchanged.
3. `navigateToLink(link)`: on IO resolve `storage.findNotebookById(link.targetNotebookId)`; missing notebook **or** `targetPageId !in nb.pageIds` → broken-target dialog («Цель не найдена», actions: «Удалить ссылку» → `session.removeLink` + re-render, «Отмена»). Resolved → push `JumpOrigin(currentNotebookName, currentPageId)` onto a `ArrayDeque` capped at 20 (drop oldest), then jump: same notebook → existing `goToPage(index)`; different → `switchNotebook(name, pageId)` — a new method refactored out of `openNotebookFromIntent`'s load path (flush autosave exactly like `goToPage` does, load notebook, install target page). All wrapped in `withChromeRefresh`; the jump's full render clears the wet tap dot (spec).
4. Back: toolbar **«←»** button visible while the stack is non-empty (`uiJumpDepth > 0` state); tap pops one origin and jumps to it via the same path (pop does **not** push). System back gesture untouched.
5. Same-page target: allowed; `goToPage(currentIndex)` re-render path is sufficient.

- [ ] **Step 1:** TDD `TapClassifier` (red → green).
- [ ] **Step 2:** Implement navigation, switchNotebook refactor, back stack, broken-target dialog.
- [ ] **Step 3:** `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` — green.
- [ ] **Step 4: Commit** `Navigate links by pen tap with step-back stack`.

---

### Task 7: `:app` — edit/delete a link by circling it

**Files:**
- Modify: `app/src/main/kotlin/com/nomadnotes/app/EditorActivity.kt`

**Brief:** In `handleLassoGesture`'s new-selection branch (after `applyLasso`): also test the polygon against `session.page.links` with `lassoCoversRegion` (main layer visible required); first hit → set `uiCircledLink: LinkId?`. This must work **even when the polygon captures zero strokes** (today an empty selection just clears — extend so a link-only circle still surfaces the link actions). Selection bar: when `uiCircledLink != null`, additionally show **«Изменить ссылку»** (opens the Task-5 picker in `mode = EditTarget(linkId)`, confirm → `session.setLinkTarget`) and **«Удалить ссылку»** (`session.removeLink` → re-render; strokes stay). `clearSelection()` and tool switches also clear `uiCircledLink`. Ordinary selection actions stay available alongside.

- [ ] **Step 1:** Implement detection + bar actions + picker edit mode.
- [ ] **Step 2:** `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` — green.
- [ ] **Step 3: Commit** `Edit and delete links by circling them with the lasso`.

---

### Task 8: batch review + device pass

- [ ] **Step 1:** Opus reviewer over the whole phase-2 diff (spec compliance vs `2026-07-23-phase2-links-design.md` + code quality); fix findings; commit fixes.
- [ ] **Step 2:** Build + install on the connected Boox (user connects device). `./gradlew --stop` after.
- [ ] **Step 3: User device checklist:**
  - Create: lasso on main layer → «Ссылка» appears (and does NOT on another layer) → picker → link affordance drawn.
  - Tap: pen tap inside region jumps (same notebook, other notebook, same page); tap outside stays ink; long stroke through region stays ink; wet dot cleaned by jump render.
  - Back: «←» walks the chain step by step; disappears when exhausted.
  - Edit/delete: circle a link (with and without strokes inside) → actions appear and work; deleting keeps handwriting.
  - Broken target: delete a target notebook → tap → «Цель не найдена» → delete link works.
  - Hidden main layer: no affordances, no tap response.
  - Regression: ink latency, lasso move/copy/paste, eraser, layers panel, page nav, autosave.
  - Tune `TapClassifier` thresholds if taps misclassify (constants are the only knob).
- [ ] **Step 4:** Fix-batch from device findings (if any) → re-review → final commit + push.
