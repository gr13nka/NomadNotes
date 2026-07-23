# NomadNotes Backlog

Recorded 2026-07-23 after the Phase 2 device pass on the Boox Go 10.3. Items are
future work — none block Phase 2, which is validated on device.

## Links: fixes from the device pass

1. **Whole-region button + no inking inside links.** On device, only taps near the
   corner glyph reliably navigate; taps elsewhere in the region often become ink
   dots, and pen traces accumulate over the link area. Do as Supernote: the entire
   link region is a button (tap anywhere → navigate) and handwriting inside a link
   region is rejected (no stroke recorded, no lasting trace). First investigate why
   region-wide taps misclassify (TapClassifier thresholds vs real firmware point
   streams; wet dot lands before classification).
2. **Broken-link dialog fails to appear for a deleted notebook.** Observed: tapping
   a link whose target notebook was deleted does nothing — no dialog, the link
   stays. Expected (spec): "Broken link" dialog offering to delete the link.
   Reproduce, find why the `findNotebookById == null` path doesn't surface the
   dialog, fix.
3. **Link jump latency (~2 s on device).** Profile `navigateToLink`: linear
   `findNotebookById` loads every notebook; full-page render on switch. Consider an
   id→name index / cached notebook list and a lighter present path for jumps.

## Links: features

4. **Finger-tap navigation.** Tap links with a finger while the pen keeps writing.
   Needs a device spike (does the firmware deliver finger MotionEvents while raw
   drawing is enabled?) and a palm-rejection story — a resting palm must not
   trigger jumps.
5. **Copy/paste preserves links.** Copying a selection that forms a link region
   should carry the link; paste creates a new link (new id, same target) over the
   pasted strokes.
6. **Richer target picker.** Page thumbnails instead of bare page numbers; show the
   notebook's table of contents in the picker once headings exist (item 7).

## Phase 3 (per nomad.pdf §4.7–4.8)

7. **Headings + keywords + navigation window** (TOC panel): create from lasso, list
   in a navigation panel, jump from it; the TOC also feeds the link target picker
   (item 6).

## Accepted deferrals (from Phase 2 reviews)

- Dialogs do not suppress the pen backend (inherited Phase-1 pattern).
- `TODO(erase-flash)` region-refresh mitigation in OnyxRawDrawingController.
- A self-target link jump pushes a back-stack origin (harmless).
