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
   **Spike answered on device, 2026-09-17 — the firmware does deliver finger input while raw
   drawing is enabled, and `TouchHelper.enableFingerTouch` was never needed** (every observation
   below was taken with it `OFF`):
   - *Delivered?* Yes. 240 finger MotionEvents with `rawDrawing=ON`; **73 of them reached the
     SurfaceView's own listener**, which is where production would read them.
   - *Multi-touch?* Yes. `ACTION_POINTER_DOWN`/`UP` fire, pointerCount reaches 2, and the
     multi-pointer events reach the SurfaceView.
   - *Pen and finger together?* Yes. At the kernel layer 8 finger contacts began **inside** a
     pen-down span while the pen was drawing; at app level stylus and finger events interleave in
     one session.
   - *Resting palm?* A palm alone produces **no events at all** — the touch controller rejects it
     below the app (two separate captures, zero events). A palm resting **while the pen writes**
     does get through, as a 10-pointer blob at kernel pressure 255 against a fingertip's 25-37.
   - *Contact size is unavailable.* `ABS_MT_TOUCH_MAJOR` is never emitted, and `getTouchMajor()`
     and `getSize()` are `0.0` in every MotionEvent. Palm rejection must use **pressure**:
     fingertips read 0.039-0.18 normalized (p50 0.094), a palm saturates at 1.0.
   - *Caveat.* Roughly half the finger events reach `dispatchTouchEvent` but not the SurfaceView
     (143 vs 73), and 5 finger streams ended in `CANCEL`. If the gesture recognizer proves lossy
     reading from the backend's listener, feed it from an Activity-level dispatch hook instead.
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

8. **Finish the smoothing calibration against real prose.** Largely done, 2026-09. Smoothing now
   defaults to `SmoothingLevel.AUTO`, which derives its tolerance per stroke: the smaller of a tremor
   budget and a fraction of the stroke's own shape scale (`min(bboxW, bboxH)`), so a tolerance that
   merely tidies a signature can no longer swallow a small letter. Measured over 115 real strokes:
   tremor runs p50 0.10 px / p95 0.44 px, while the old `LIGHT`/`STRONG` tolerances were 1.2 px and
   3.0 px — 3-7x the noise they were meant to remove, which is why they ate letter shape rather than
   jitter. At `STRONG` a sub-40 px stroke was reduced to 2.7 knots, i.e. very nearly a straight line.
   The old 2.5 px resample spacing was also coarser than the digitizer's own 1.57 px median sampling,
   so it decimated small hands instead of refitting them; it is now 1.2 px. `LIGHT`/`STRONG` remain in
   the enum as named fixed tunings but have left the toolbar, which is now a plain AUTO/OFF toggle.
   **Remaining:** re-run `SmoothingCalibrationReport` against a page of the author's ordinary prose
   (the corpus so far is `test.nnote`, which is closer to test marks than handwriting) and re-apply
   the selection rule.

9. **Confirm the settle blit never lands mid-stroke.** Still open, and now live for the first time:
   the default was `OFF` until 2026-09, so this path had never run in ordinary use. `SMOOTHING_SETTLE_MS`
   was raised 300 ms -> 700 ms, since gaps between strokes within a word are ~100-300 ms while gaps
   between words are ~600 ms and up — 300 ms sat inside the intra-word band. A guard inside the settle
   Runnable would add nothing: both it and the `onGestureStarted` post are main-thread, so
   `removeCallbacks` ordering already decides the race. Needs a fast writer on device to confirm no
   stroke is ever dropped or truncated.

10. **Orphaned image assets are never collected.** Deleting a page (or undoing an image insert past
    the undo cap) leaves its file in `<notebook>.nnote/images/`. Deleting the whole notebook still
    cleans up, since the directory goes with it. A sweep comparing files against the refs on every
    page would fix it.
11. **Dither quality on the panel.** Images are reduced to 16 greys with an 8×8 ordered dither at
    decode time (`ImageResolver`). If photographs look too coarse, the alternatives are a finer
    matrix, error diffusion (at the cost of a stable pattern across partial refreshes), or leaving
    more levels to the firmware.

12. **Smoothing cannot denoise a curved stroke, by construction.** Simplification keeps original
    samples as knots and the Catmull-Rom spline passes exactly through them, so this is an
    interpolating scheme, not an averaging one — it has no mechanism to cancel noise. Swept against a
    noiseless reference glyph, deviation never improved at any tolerance: it denoises only where the
    tolerance exceeds the noise *and* the true shape carries no detail at that scale, which is why a
    jittered straight line comes out straight while a jittered loop does not come out cleaner. What
    AUTO does deliver is an evenly spaced, curvature-continuous path that stays on the ink the pen
    laid down. If device use shows tremor is still visible on curves, the fix is an approximating
    fit (least-squares / moving average before simplification), not a larger tolerance — a larger
    tolerance only costs letter form.
13. **The pen-speed term in AUTO is inert and should probably go.** `tremorBudgetGain` modulates the
    tremor budget by median sample spacing. Swept 1.0..3.0 over real ink, the selection rule chose
    1.0 — no speed dependence at all. It is kept only so the prose corpus (item 8) can re-decide; if
    that run selects 1.0 again, delete it and `medianSampleSpacingPx` rather than keep machinery that
    does nothing.

## Things-style chrome — implementation

Spec: `docs/superpowers/specs/2026-09-18-things-eink-ui-design.md` (mockups alongside). Build
only after the mockups are approved.

1. **Tokens.** Geist in `res/font`, a three-step `Typography`, and spacing and state tokens in
   `ui/Eink.kt`. Controls read the tokens instead of per-call-site sizes.
2. **Editor bar.** Replace the `EditorToolbar` `FlowRow` with the five-glyph bar and its
   selection mode. Turn `EditorOverlays` into anchored Tool, Page and More panels. Reuse
   `updateToolbarExclude`, `updateBackendEnabled` and `withChromeRefresh`. Add a "Hide toolbar"
   state.
3. **Two-pane library.** Rework `NotebookListActivity` into a sidebar and a page grid, with
   inline rename and create. Needs a start-page extra for `EditorActivity` and a page-thumbnail
   renderer that caches on save.
4. **Quick Find.** A name-only index (notebooks, Recent pages), plus a backlinks query over
   `PageLink`. The heading search waits for Phase 3.

## Accepted deferrals (from Phase 2 reviews)

- Dialogs do not suppress the pen backend (inherited Phase-1 pattern).
- `TODO(erase-flash)` region-refresh mitigation in OnyxRawDrawingController.
- A self-target link jump pushes a back-stack origin (harmless).
- `Layer.images` is a bare defaulted field, not an `extra` entry, following the Phase-2 links
  precedent. An older build would decode a page with images fine but drop them on its next
  autosave, since `NotesJson` does not re-emit unknown keys. Accepted: builds are not downgraded.
- An image is always beneath the ink of its own layer; putting one over ink means putting it on a
  higher layer. True interleaving would mean replacing `Layer.strokes` with an element list.
