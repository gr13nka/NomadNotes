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

## Images and smoothing

8. **Tune the smoothing levels on device.** `SmoothingLevel.LIGHT`/`STRONG` map to RDP tolerances
   (1.2 px / 3.0 px) and a 2.5 px resample spacing, picked by eye rather than from real firmware
   point streams. Adjust against actual handwriting; relates to item 1, since the same digitizer
   noise is what makes region-wide taps misclassify.
9. **Confirm the settle blit never lands mid-stroke.** `SMOOTHING_SETTLE_MS` is 300 ms and a
   pen-down cancels it (`PenBackend.Listener.onGestureStarted`). If a fast writer ever sees a
   dropped or truncated stroke with smoothing on, that window is the first suspect.
10. **Orphaned image assets are never collected.** Deleting a page (or undoing an image insert past
    the undo cap) leaves its file in `<notebook>.nnote/images/`. Deleting the whole notebook still
    cleans up, since the directory goes with it. A sweep comparing files against the refs on every
    page would fix it.
11. **Dither quality on the panel.** Images are reduced to 16 greys with an 8×8 ordered dither at
    decode time (`ImageResolver`). If photographs look too coarse, the alternatives are a finer
    matrix, error diffusion (at the cost of a stable pattern across partial refreshes), or leaving
    more levels to the firmware.

## Accepted deferrals (from Phase 2 reviews)

- Dialogs do not suppress the pen backend (inherited Phase-1 pattern).
- `TODO(erase-flash)` region-refresh mitigation in OnyxRawDrawingController.
- A self-target link jump pushes a back-stack origin (harmless).
- `Layer.images` is a bare defaulted field, not an `extra` entry, following the Phase-2 links
  precedent. An older build would decode a page with images fine but drop them on its next
  autosave, since `NotesJson` does not re-emit unknown keys. Accepted: builds are not downgraded.
- An image is always beneath the ink of its own layer; putting one over ink means putting it on a
  higher layer. True interleaving would mean replacing `Layer.strokes` with an element list.
