# Things 3 on e-ink — chrome design for NomadNotes

Status: proposed, 2026-09-18. Mockups: `2026-09-18-things-eink-ui-mockups.html` (same folder).
Source research: `YetAnotherPerfectNotes/docs/research/things3-mobile-ui.md` (cited as *T3 §n*).

## Why Things, and why its desktop rather than its phone

Things 3 is admired for being calm: few controls, type doing the work of hierarchy, and
nothing on screen that isn't needed right now. That is the brand NomadNotes already claims on its
landing page ("Just the page"). The in-app chrome hasn't caught up yet. The editor toolbar is a
wrapping row of about 25 controls, and the library is a plain list.

Things' **mobile** signature is motion: the Magic Plus button deforms under the finger, a
completed row holds for 1.45 s and then dissolves, and haptics confirm gestures (T3 §5, §6, §8).
Each of those is a continuous stream of frames. On e-ink every frame is a partial refresh with
ghosting, so all of it is either unaffordable or illegible.

Things' **desktop** is a *static-frame* design: a permanent sidebar, a quiet toolbar of monochrome
glyphs that are buttons and not objects (T3 §5, "What the desktop does instead"), hairline
structure, and navigation by typing a name. A screen that changes rarely and completely is exactly
what e-ink draws well. So the rule for this spec is: **take Things' structure from the desktop,
take its restraint from both, and take none of its motion.**

## Translation table

| Things 3 principle | T3 | NomadNotes on e-ink |
|---|---|---|
| Objects transform in place; screens are not replaced | §1, §6 | Pickers are panels anchored to the glyph that opened them, drawn over the page. The page never goes away. |
| The sidebar is a permanent frame | §4 | The library is two panes: notebooks on the left, the chosen notebook's pages on the right. |
| Desktop toolbar: a few monochrome glyphs, buttons not objects | §5 | The editor has one quiet bar of outline glyphs. Everything else lives in panels or under the fingers. |
| Creation carries placement | §1 | New things are born where you are: a new page goes after the current one, and the "+" tile goes at the end of the grid you are looking at. There is never a "save to default, move later" step. |
| Rigid, shallow hierarchy | §2 | Notebook → Page → Layer. No nesting and no folders. |
| Counts only where there is something to count | §4 | No zero badges. The Back glyph exists only after a link jump, and page counts show only above one page. |
| Quick Find is the navigation system; specialist lists hide behind it | §3, §4 | Find jumps to a notebook or page by name. Recent and backlinks are reachable through Find, not added as sidebar rows. |
| Completed rows dim; they don't strike through | §6 | Disabled or receded means 50 % grey text. Selected means inverted. Grey is never the only signal for something the user must act on. |
| "Each animation is purposeful" | §8 | On e-ink the purposeful amount is **zero**. Every transition is a state change plus one refresh. |
| Colour-coded lists | §4 | Glyph shape and type weight do the job colour did. Black and white only. |
| Haptics on ambiguous gestures | §8 | *(2026-09-22: superseded — see the dated note under "Voice: bash.org manners".)* Originally one static, opaque badge drawn once when a gesture latches (`BadgeRenderer`, now removed); the lasso hold instead inverts the bar's own tool bracket, and undo/redo need no separate feedback. |
| No Done button: dismiss by touching elsewhere | §6 | A panel closes when you tap outside it or when the pen touches the page. |

### Not adopted, and why

- **Magic Plus drag.** Placement by dragging needs continuous feedback while the drag is in
  flight, and the pen is busy writing anyway. Placement comes from context instead (see above).
- **Liquid deformation, the completion dissolve, cross-fades, reflow slides.** These are all
  motion. E-ink would show them as a smear followed by a ghost.
- **Haptics.** The Boox has no haptic engine. The static badge replaces them.
- **Colour.** The panel is monochrome, and the brand is too.
- **Swipe actions on rows.** A horizontal swipe on e-ink gives no feedback until it completes, so
  the user can't tell it registered. Row actions live behind "⋯".
- **Tags, dates, Someday, Logbook.** These belong to a task model. Notes have no finish line.

## E-ink rules (apply to every surface)

1. **No motion.** No ripple (`NoIndication` stays), no fade, no animated scroll, no
   `AnimatedVisibility`. A panel either exists or doesn't.
2. **One refresh per state change.** Opening or closing a panel, changing the bar's mode, and
   switching the selected notebook each cost exactly one chrome refresh
   (`withChromeRefresh`, `EditorActivity.kt:1516`). No intermediate frames.
3. **No scrims.** A flat 50 % grey scrim over the page ghosts and muddies the ink underneath.
   Elevation is a 1 dp border around a solid paper fill. Panels never cast shadows.
4. **Raw drawing stays honest.** Every piece of chrome visible over the canvas is in the exclude
   rects (`updateToolbarExclude`, `EditorActivity.kt:1628`). While a panel is open, raw drawing is
   off (`updateBackendEnabled`, `:1501`), and the first pen touch outside the panel closes it and
   is *not* inked.
5. **Opaque and non-antialiased where partial refresh draws it.** Text and glyphs over the canvas
   use flat black on flat white — the same reasoning `BadgeRenderer` once demonstrated for its badge
   before that badge was removed (2026-09-22); the bar and panels still follow it.

## Tokens (`ui/Eink.kt` target state)

| Token | Value | Notes |
|---|---|---|
| `ink` | `#000000` | Text, glyphs, borders, inverted fills |
| `paper` | `#FFFFFF` | Backgrounds, panel fills |
| `muted` | `#808080` | Captions, disabled text, receded items. Flat fill only, never a gradient. |
| Font | Geist (OFL), bundled in `res/font` | The typeface the landing page uses. One family only. |
| `title` | 28 sp, SemiBold | Notebook title in the library pane |
| `body` | 18 sp, Regular | Rows, panel items, the page counter |
| `caption` | 14 sp, Regular, `muted` | Counts and secondary lines |
| Spacing | 8 dp grid: 8 / 16 / 24 / 32 / 48 | Generous page margins, following Things' whitespace |
| Touch target | ≥ 48 dp | Glyphs are 24 dp inside a 48 dp hit area |
| Hairline | 1 dp `ink` | Structure: the bar's bottom edge, the sidebar's right edge, row separators where needed |
| Control border | 1.5 dp `ink` (`BorderWidth`) | Buttons, fields, panel edges. Unchanged from today. |
| Corners | Square | Unchanged. It matches the existing controls and the site. |

States:

- **Selected**: an inverted fill, white on black. Used for the active tool, the chosen notebook,
  and the current page thumbnail.
- **Focus**: a 2 dp outline (the text-field caret, a keyboard-focused row).
- **Disabled**: `muted` text, border unchanged.

These states replace the ad hoc 15/16/17/18/24 sp sizes and the per-call-site paddings.
`EinkButton`, `EinkToggle` and the other controls read the tokens instead of taking literals.

## Voice: bash.org manners

Calm structure alone read as generic. The chrome gets its character from **copy and crisp states,
not ornament**. Hand-drawn marks and tilted sheets were tried and rejected.

- **Bracket buttons.** Every text control is lowercase text in square brackets: `[rename]`,
  `[+ notebook]`, `[copy]`. The bar glyphs are bracketed too: `[≡] [✎ pen] [⌕] [⋯]`. Pressed
  or active means the whole bracketed label is inverted. Names, counts and status text are
  never bracketed.
- **Rating control for values**, like bash.org's `[+] [−]`: `width  [−] 3 [+]`,
  `shade  [−] dark [+]`.
- **`#` page IDs.** Pages are numbered like quotes: `#12 of 40`, thumbnails `#1 #2 …`, and links
  `[← back to research #3]`.
- **Dry, lowercase, short copy.** No exclamation marks, no emoji, no cuteness. Examples:
  - The gesture badges: `undo. nobody saw that.`, `redo. changed your mind again.`,
    `lasso ready. draw a loop.`
  - The empty library: `no notebooks yet. the pen is bored.`
  - Delete confirmation: `delete thesis? all 24 pages.  [yes, burn it]  [no]`
  - No Find results: `nothing. try fewer letters.`
  - Lasso bar: `14 strokes caught`
- **Current and selected states** stay geometric:
  - The selected notebook row is inverted.
  - The active tool's bracket label is inverted.
  - The current page gets a 3 dp border and an inverted `#` caption.
- **`[random page]`** (sidebar, under `recent`), after bash.org's "random": it opens a random
  old page from any notebook, for rediscovery.

**2026-09-22: jokey copy and the gesture badges dropped.** At the user's request, copy that read as a
joke rather than a label is gone — e.g. `%1$d strokes caught` → `%1$d strokes selected`, `link
caught`/`image caught` → `link selected`/`image selected`, and the delete confirmation's yes/no →
`[delete]`/`[cancel]`. The gesture badges (`undo. nobody saw that.`, `lasso ready. draw a loop.`, and
the rest) are gone along with `BadgeRenderer` itself: undo/redo need no feedback beyond the page
change, and the lasso hold now shows by inverting the bar's own tool bracket to `[✎ lasso]` instead of
drawing a badge over the page. Brackets, `#` page IDs, and the `[+]`/`[−]` rating controls are
unchanged — only wording that was trying to be funny was plained out.

Mockups: `2026-09-18-things-eink-ui-mockups.html` shows every state with this voice. Where the
ASCII sketches below differ in wording, the mockups and this section win.

## 1. Editor chrome

### The bar

One bar across the top edge. It is 56 dp tall with a 1 dp hairline beneath it, glyphs only, and
no labels except the page counter:

```
[≡]  [✎]  12 / 40  [←]              [⌕]  [⋯]
```

| Glyph | Meaning | Tap |
|---|---|---|
| ≡ | Library | Close the editor and return to the library with this notebook selected |
| ✎ (the active tool's own glyph) | Current tool | Opens the **Tool panel** |
| `12 / 40` | Page position | Opens the **Page panel** |
| ← | Back from a link jump | Shown **only** while `uiJumpDepth > 0`. Jumps back. |
| ⌕ | Find | Opens **Quick Find** |
| ⋯ | More | Opens the **More panel** |

That is five controls at rest, six after a link jump. The bar replaces the `EditorToolbar`
`FlowRow` (`EditorActivity.kt:1916`). Undo and redo leave the bar because two- and three-finger
taps own them (M1 comfort features). Lasso stays reachable as a tool, but a two-finger hold is
the fast path.

### Tool panel (anchored under ✎)

```
┌───────────────────────────────────┐
│  [Pen] [Pencil] [Marker] [Eraser] [Lasso] │   ← glyph row, active one inverted
│  ─────────────────────────────────│
│  Width     ○ · ●  ○ ●  ○ ⬤        │   ← 3 dots, the actual stroke widths
│  Shade     ■  ▩  □                │   ← black / grey / light
│  Smoothing Auto · Off             │
└───────────────────────────────────┘
```

- Choosing a tool **closes the panel**. You opened it to switch tools, and now you are done.
  Changing the width, shade or smoothing keeps the panel open so you can adjust more than one.
- Rows that don't apply to the selected tool (for example Shade for the Eraser) are omitted, not
  greyed out.

### Page panel (anchored under the counter)

```
┌──────────────────────────────────────────┐
│  ‹ Prev        12 / 40         Next ›    │
│  ────────────────────────────────────────│
│  [ 10 ] [ 11 ] [▮12▮] [ 13 ] [ 14 ]       │   ← thumbnails, current inverted-framed
│  ────────────────────────────────────────│
│  + Page after this        Delete page    │
└──────────────────────────────────────────┘
```

- The thumbnail strip shows the current page ±2. Tapping a thumbnail opens that page and closes
  the panel.
- **+ Page after this** always inserts at the current position. This is the "creation carries
  placement" rule; there is no "add at end" choice.
- **Delete page** asks for confirmation inline: the button turns into "Delete page 12? · Delete ·
  Keep". There is no modal dialog.

### More panel (anchored under ⋯)

A plain list in `body` type: **Layers ›**, **Template ›**, **Insert image**, a hairline, then
**Undo**, **Redo**, and **Hide toolbar**. Layers and Template replace the More list *in the same
panel*, with a "‹" back row at the top. They do not open a second panel on the right. The
existing `LayersPanel` and `TemplatePanel` content is reused, restyled to the tokens.

### Lasso selection: the bar changes mode

While a selection exists, the bar's contents are replaced in place:

```
[×]  Selection                     Copy   Link   Delete
```

× deselects. This mirrors Things' toolbar during multi-select: the actions for the thing you are
holding, in the place where actions always are. There is no second floating bar near the
selection, because it would land on ink and need its own refresh. The link-edit and image
selections use the same mode with their own verbs (Edit link · Delete link; Delete image). Paste
appears as a verb in normal mode only while the clipboard holds strokes, placed just before ⌕.

### Hidden chrome

**Hide toolbar** (in More) collapses the bar to a single 48 dp **≡** glyph in the top-left
corner. That glyph is the only exclude rect. Tapping it brings the bar back. This is the "Just the
page" state, the equivalent of Things' Slim Mode. The choice persists per device, not per
notebook.

### Panel mechanics

- Only one panel is open at a time. Opening one closes any other.
- A panel is anchored under its glyph's left edge, clamped to the screen with a 16 dp margin, and
  is at most 360 dp wide.
- It closes on a tap outside the panel, a pen touch on the page (not inked), a second tap on its
  glyph, or completing the action it was opened for.
- While a panel is open, raw drawing is off. The panel is drawn over the page with no scrim.

## 2. Library

Two panes on the portrait Go 10.3 (roughly 840 dp wide):

```
┌──────────────┬──────────────────────────────────────┐
│ ⌕ Find       │                                      │
│              │  Thesis                          ⋯   │
│ Recent       │  24 pages                            │
│ ──────────── │                                      │
│ Inbox        │  ┌───┐ ┌───┐ ┌───┐ ┌───┐             │
│▮Thesis     ▮│  │   │ │   │ │   │ │   │             │
│ Sketches  3  │  └───┘ └───┘ └───┘ └───┘             │
│ Meetings  12 │    1     2     3     4               │
│              │  ┌───┐ ┌───┐ ...          ┌ ─ ┐     │
│              │  │   │ │   │              │ + │     │
│              │  └───┘ └───┘              └ ─ ┘     │
│ + Notebook   │                                      │
└──────────────┴──────────────────────────────────────┘
```

**Sidebar** (280 dp, with a 1 dp hairline on its right edge):

- **Find** field at the top, as on Things for iPad. Tapping it opens Quick Find in place.
- **Recent**: the last pages you had open, across all notebooks. It is the one fixed entry.
- A hairline, then the notebooks in the order they were last opened. The selected notebook is
  inverted. A page count in `caption` appears only above one page.
- **+ Notebook** is pinned to the foot. It creates the notebook, selects it, and opens its name
  field inline in the sidebar row. There is no dialog.

**Content pane:**

- The notebook's name in `title`, with a page count in `caption` beneath it. **⋯** at the right
  opens an anchored popover with Rename and Delete. Rename edits the title in place. Delete uses
  the same inline confirmation as Delete page.
- A grid of page thumbnails at the page's own aspect ratio, 4 across, with page numbers in
  `caption` beneath. Tapping a thumbnail opens the editor **at that page**.
- A dashed **+** tile ends the grid. It appends a page and opens it.
- **Empty notebook**: only the + tile. **No notebooks**: the sidebar shows only + Notebook, and
  the content pane shows one line, "No notebooks yet." There is no illustration.

This replaces `NotebookListScreen` (`NotebookListActivity.kt:172`) and its bottom-sheet
`ItemMenu` (:281) and `NameDialog` (:257).

**Implementation needs**, not solved here:

- The editor needs a start-page extra.
- Page thumbnails need a renderer. It would rasterise a page's layers at thumbnail scale and
  cache the result on save, so the library never renders ink live.

## 3. Quick Find

Search as navigation. Typing a place's name takes you there (T3 §4, "Type Travel").

- **Entry points**: the sidebar field (library), or ⌕ in the editor bar. In the editor it is a
  panel docked to the left, the full height of the screen below the bar, 360 dp wide.
- **Stage one: names only.** It matches notebook names, and "Notebook · p. N" for pages in
  Recent. Matching is case-insensitive prefix-per-word (`th ch` → "Thesis · p. 12" … and any
  notebook whose words start th… ch…). The results update on every keystroke. There is no
  search button.
- **Choosing a result** opens that place. In the editor it switches notebook or page, and the
  panel closes.
- **Hidden views**, reachable only through Find (T3 §3's hidden lists):
  - **Recent**: typing `rec` offers it.
  - **Links here**: every page with a `PageLink` pointing at the current page (backlinks). In the
    editor it is offered as the first row when the query is empty.
- **Stage two (reserved)**: once Phase 3 headings and keywords exist, a "Search headings" row at
  the foot widens the query. That is Things' "Continue Search": a separate, slower pass you opt
  into.
- **Out of scope**: handwriting recognition. Find never pretends to search ink.

## 4. Links mini map

Mockups: `2026-09-22-links-minimap-mockups.html` (same folder). **Built** — the anchored panel
below; the full-frame toggle is not (see that bullet).

Pages as named chips, connected by their `PageLink`s. It's a map to get oriented in, one hop at a
time, not a reading view.

Pages have no titles, so a chip's name is not free text like the mockup's placeholder `Trip
budget` — it is the handwritten **sticker** on the `PageLink` between the chip and its neighbour,
scaled down to a small bitmap; a link with no sticker falls back to `#N`. The centre chip borrows
the first sticker among its own outgoing links, for the same reason.

**Sticker flow** (feeds the chips above). Creating a link no longer stops at picking the target
page: a small 2:1 panel opens next, pen-only, to write or draw the sticker — `[Done]` attaches it,
`[Skip]` attaches none. The circled-link selection bar gains a matching `[sticker]` verb to redraw
an existing link's sticker later. The sticker is drawn on the link's own card on the source page
too, doubling as its caption there.

- **Entry point.** A `[⋈]` bar glyph, left of `[⌕]`. It opens a panel anchored under it, following
  the Panel mechanics above (16 dp edge clamp, no scrim, raw drawing off while open, closes on an
  outside tap or a pen touch). Open by default about 70 % of the screen wide and roughly square, in
  the top-right under the glyph.
- **Radial layout, one hop deep.** Only the current page and its direct neighbours are drawn —
  nothing further out. The current page sits at the centre; its neighbours are placed evenly on a
  circle around it, starting at 12 o'clock and going clockwise, ordered by page number with
  cross-notebook neighbours last. The layout is recomputed from scratch for whichever page is
  root, not force-directed and not animated — e-ink has no budget for a physics simulation, and
  recentring is a single redraw.
- **Named chips, not dots.** Every node is a label chip, not a bare mark, so the map reads without
  guessing: the current page is a solid black chip with white text when it has no sticker, `#12`,
  or its sticker bitmap (3 dp border) when it does; neighbours are white chips with a 1.5 px ink
  border, their sticker bitmap or `#7` when they have none, with a second muted line giving their
  own link count (`3 links`) so it's clear centring on them leads somewhere. A cross-notebook
  neighbour gets a dashed border and folds its notebook into that line: `research #2 · 2 links`.
  Every chip's hit area is a comfortable ≥48 dp tall.
- **Undirected edges.** Edges are plain 1.5 px ink lines connecting chip centres, drawn under the
  chips — no arrowheads, since a link has no reading direction here. A cross-notebook edge is
  dashed.
- **Tap-to-select-then-act.** A tap selects a chip — a thicker 3 px border (or, on the solid
  current-page chip, an inverted white ring) — and fills a footer row with `#N Title`, `[Open]`,
  `[Centre]`. With only one hop shown there's nothing else to fade. A stray tap does no harm;
  tapping empty map space clears the selection.
- **Centre re-anchors, it doesn't navigate.** `[Centre]` makes the selected node the new "current"
  page and recomputes the radial layout around it instantly — no depth control needed, since the
  view is always exactly one hop deep. The bar's page counter does not change. The footer grows a
  `[‹ back]` once the map has been re-centred, to undo it.
- **Full-frame toggle — deferred.** `[⤢]` in the header, to expand the panel to fill the whole page
  area below the bar, is not built in v1 (`docs/BACKLOG.md`); the map is always the anchored panel.
- **Open** commits the jump: it closes the panel and updates the bar's page counter to the selected
  page, the same as any other navigation. `[⋈]` reopens the map, now centred on the page you're on.
- **Empty state.** The current page alone, captioned `no links yet`.

## Implementation touchpoints (for the follow-up round)

- `app/src/main/java/com/nomadnotes/app/ui/Eink.kt`: tokens, `Typography`, and the Geist
  `FontFamily`. Controls read tokens instead of literals.
- `app/src/main/java/com/nomadnotes/app/NotebookListActivity.kt`: the two-pane library.
- `app/src/main/java/com/nomadnotes/app/EditorActivity.kt`: `EditorToolbar` becomes the bar
  plus its mode for selections, and `EditorOverlays` becomes the anchored panels. The exclude and
  enable plumbing is reused as is.
- A new thumbnail renderer and cache, and a start-page extra for `EditorActivity`.
- A new Quick Find index: notebook names, the Recent list, and a backlink query over `PageLink`.
