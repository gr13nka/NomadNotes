# Phase 2 — Links ("buttons") design

Date: 2026-07-23. Status: approved by Andrey (scope, tap mechanism, and full design).

## Goal

Supernote-style links (nomad.pdf §4.9): lasso a handwritten region on the main layer,
attach a jump target (a page of this or another notebook), tap the region with the pen
to jump, step back through a navigation stack. This is the app's killer feature; the
Phase-1 architecture prepared for it (`SelectionState` as the anchor, stable UUID ids,
`mainLayerId`).

## Scope decisions (user-approved)

- **Links only.** Headings (§4.7), keywords (§4.8), and the Navigation Window are
  Phase 3.
- **Pen tap activates a link.** A short pen tap inside a link region navigates instead
  of drawing; a tap outside link regions stays ink; a long stroke crossing a region
  stays ink. No finger-tap path (a palm resting on the panel is indistinguishable from
  a finger and would trigger false jumps) and no separate "hand" tool.
- Supernote parity constraints adopted: links live on the **main layer** only and are
  tappable only while that layer is **visible**; targets are notebook pages only.

## Data model (`:core`)

Links are a first-class field on `Page`, not an `extra` entry — `extra` maps stay
reserved for unknown/foreign data. The new field defaults to empty, so existing files
decode unchanged and `formatVersion` stays 1.

```kotlin
// Page gains:
val links: List<PageLink> = emptyList()

@Serializable
data class PageLink(
    val id: LinkId,                    // new @JvmInline value class in Ids.kt, UUID string
    val region: LinkRegion,            // left/top/right/bottom in page coordinates
    val targetNotebookId: NotebookId,  // survives notebook rename (directory name may change, id never does)
    val targetPageId: PageId,
)
```

- A link binds to a **region** (the selection bbox at creation time plus a small
  padding), not to strokes. The circled handwriting remains ordinary ink; the button is
  the rectangle above it. Moving strokes does not move links; links themselves cannot
  be moved in v1 (Supernote offers only edit/delete too).
- `PageEditSession` gains undo-capable commands following the existing
  inverse-carrying pattern: `addLink(link)`, `removeLink(id)`,
  `editLinkTarget(id, notebookId, pageId)`.
- Target resolution is by id: scan `listNotebooks()` for `targetNotebookId`, then load
  `targetPageId`. Robust against notebook renames; a missing notebook/page is a broken
  link (see Edge cases).

## UX flows

### Create

1. Lasso a region. If the selection is on the **main layer**, the selection bar shows
   a new **«Ссылка»** button (hidden for other layers).
2. The button opens a target-picker overlay (same pattern as existing panels, wrapped
   in `withChromeRefresh`): notebook list with the current notebook first → target page
   number within the chosen notebook.
3. Confirm → `addLink` with `region = selection bounds + padding`, clear the
   selection, full re-render.

### Tap → jump, back stack

- In `onStrokeFinished`, before `addStroke`: if the gesture is a **tap** (point spread
  ≤ ~6 px and duration ≤ ~300 ms from `timestampDelta`) and it lands inside a link
  region while the main layer is visible → discard the stroke and navigate. The wet-ink
  dot left on the EPD panel is erased by the target page's full render. Otherwise the
  gesture is ordinary ink. Thresholds are constants to tune on device.
- Cross-notebook jumps happen **inside the same `EditorActivity`**: flush autosave →
  load target notebook → install target page. No activity restart (heavy on e-ink; the
  page-switch path already exists).
- The editor keeps an in-memory jump stack of `{notebook, pageId}` origins. A **«←»**
  toolbar button appears while the stack is non-empty and pops one step per tap
  (Supernote's step-by-step return). The system back gesture still closes the editor.
  The stack is not persisted (process death clears it, as on Supernote). Stack depth is
  capped (drop oldest).
- A link may target its own page — navigation degenerates to a re-render, which also
  clears the tap dot.

### Edit / delete

Mirrors the manual ("circle a link with the lasso tool"): when a lasso polygon covers
a link's region (region center inside the polygon, or overlap ≥ 50 %), the selection
bar additionally shows **«Изменить ссылку»** (reopens the target picker) and
**«Удалить ссылку»** (removes the `PageLink`; strokes stay). Ordinary selection
actions remain available at the same time.

## Rendering

`PageRenderer`'s recomposite step draws each main-layer link's affordance on top of
the layers: a thin rounded-corner rectangle plus a small corner glyph («◸»), gray,
styled like `SelectionRenderer`. Because the affordance lives in the composite bitmap,
it survives every blit and raw-mode pause/resume cycle with no new Onyx rules.

## Edge cases

- **Broken target** (notebook or page deleted): «Цель не найдена» dialog with a
  «Удалить ссылку» action.
- **Hidden main layer**: link regions do not respond to taps; affordances are not
  drawn (parity with "visible main layer" in the manual).
- **Undo independence**: link commands and stroke commands are separate history
  entries; undoing a stroke erase never resurrects/removes a link and vice versa.
- **Link region on a page being deleted**: nothing special — links live inside the
  page file and die with it. Inbound links from other pages become broken targets,
  handled above.

## Out of scope (recorded for later phases)

Headings, keywords, Navigation Window/TOC; moving or resizing a link region; link
styles/titles (Supernote's "link style" picker); persisting the back stack;
"return to origin at once" button; finger-tap activation; blocking handwriting inside
link regions (long strokes stay ink by design).

## Testing

- `:core`: serialization round-trip with links (old JSON without `links` still
  decodes; `formatVersion` stays 1); add/remove/edit-link commands with undo/redo and
  history-cap interaction; tap-inside-region geometry.
- `:app` unit: tap-vs-stroke heuristic (spread/duration boundaries); target
  resolution by id with a renamed notebook directory; picker filtering (main layer
  only).
- Device pass (decisive, as always on this project): tap accuracy vs. dot-drawing,
  wet-dot cleanup on jump, chrome responsiveness of the picker dialog under
  `withChromeRefresh`, cross-notebook jump latency, back-stack walk.

## Binding Onyx constraints (from Phase-1 memory)

All chrome (picker dialog, new toolbar buttons) must go through the existing
`withChromeRefresh` pause bracket; no per-stroke blits while raw drawing is enabled;
composite blits only on structural events. The tap heuristic deliberately reuses the
existing raw-drawing INK path — no new input plumbing, no new firmware unknowns.
