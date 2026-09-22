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

   **Built 2026-09-17/18 (outcome A).** Two-finger tap undoes, three-finger tap redoes, and holding
   two fingers arms the *next* pen stroke as a lasso. `MultiFingerGestures` (pure, unit-tested) holds
   the timing and palm-rejection policy against the measured thresholds — stagger ≤120 ms,
   separation 80-500 px, travel ≤20 px, pressure ≤0.47 normalized, and a 150 ms pen-quiet window
   before a candidate may begin. `FingerGestures` is the MotionEvent adapter; `OnyxPenBackend` now
   installs one touch listener for the whole attachment, feeding it and the lasso collector together.
   Undo and the lasso arm are confirmed on device; redo-on-landing, the 150 ms arm and the straddle
   fix below were installed 2026-09-18 and still await a device pass.

   Findings from the device passes (2026-09-17 and -18) that the design did not predict:

   - **The lasso cannot be a held modifier on this firmware.** Arming it pauses raw drawing, and that
     pause makes the firmware cancel the very finger stream the hold is made of (a synthesized
     `ACTION_CANCEL`, `deviceId=0`, `toolType=0`), after which no further events arrive for those
     contacts — there is no lift left to observe. This is the cause of the "5 finger streams ended in
     CANCEL" caveat above: we cause it ourselves. The hold therefore arms a **one-shot latch** covering
     exactly the next pen gesture, expiring after 5 s if no stroke comes.
   - **Three fingers get the same cancel, with no mode change involved.** On 13 of 13 attempts a
     synthesized `ACTION_CANCEL` arrived in the same millisecond the third pointer landed —
     something below the app (most likely the firmware's own three-finger handling; unconfirmed)
     claims the stream. The lift is unobservable, so redo fires when the third finger *lands*, under
     the same palm-safety rules (within 120 ms of the candidate starting, pressure-gated).
   - **`HOLD_ARM_MS` is 150 ms.** The author's real two-finger taps, measured 2026-09-18 from the
     second finger landing to the first lift, ran 70, 74 and 87 ms; the probe's 138-289 ms figure was
     measured differently (whole contact, kernel layer) and set the earlier, slower values. Raise it if
     taps start arming the lasso; it is one constant.
   - **Drawing straight after arming raced the mode switch.** A pen landing while raw drawing was
     being paused produced a stray raw-channel gesture that was routed as the lasso, spending the
     one-shot arm and inking the rest of the stroke. Raw-channel callbacks processed while raw drawing
     is paused are now dropped; the touch path owns that pen-down.

   `enableFingerTouch` is still never called — the probe showed it is not needed — so
   `OnyxRawDrawingController.setFingerTouchEnabled` remains available but unused.
   Finger delivery from the SurfaceView listener proved reliable in the editor, so the lossiness
   caveat above did not bite. **Still unmeasured:** whether a writing-posture palm stays under
   `MAX_FINGER_PRESSURE`; only a whole open palm was measured, and it is the one input that could
   misfire an undo or redo.
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

8. **Tune the smoothing and ink-curve constants on device.** `SmoothingLevel.LIGHT`/`STRONG` map to
   `Tuning(sigmaPx, epsilonPx, maxKnotSpanPx)` — (4px, 1.5px, 48px) / (10px, 3px, 48px). `AUTO` derives
   its own tuning per stroke on the same Gaussian pipeline: `sigmaPx = min(10px, 0.2 * shape)` and
   `epsilonPx = clamp(0.03 * shape, 0.45px, 3px)`, where `shape` is the stroke's own shorter
   bounding-box side (`shapeScalePx`) floored at `MIN_SHAPE_SCALE_PX` (8px). The 0.45px tremor floor
   and 8px shape floor are measured, from 115 real strokes off the author's Boox Go 10.3
   (`test.nnote`); the 0.2 and 0.03 fractions, `NibProfile.forTool`'s drive constants, and the PEN's
   round-capped taper (`END_WIDTH_FACTOR` 0.6) are still picked by eye rather than from real firmware
   point streams. The first device pass rejected needle-pointed ends and found the old sample-count
   denoise too weak to feel, which is why `smoothPath`'s arc-length Gaussian averaging replaced it —
   see item 12. Adjust against actual handwriting; relates to item 1, since the same digitizer noise
   is what makes region-wide taps misclassify. **Remaining:** re-run `SmoothingCalibrationReport` —
   which now sweeps `SIGMA_FRACTION_OF_SHAPE` and `MAX_EPSILON_FRACTION_OF_SHAPE_AUTO` through the
   real pipeline — against a page of the author's ordinary prose (the corpus so far is `test.nnote`,
   which is closer to test marks than handwriting), and re-apply the selection rule.
9. **Confirm the ink settle never lands mid-stroke.** `INK_SETTLE_MS` is 200 ms and a pen-down
   cancels it (`PenBackend.Listener.onGestureStarted`). The settle always runs, at every level
   including OFF (it swaps the hardware's raw wet ink for our own tapered/curved ink, not a
   smoothing correction, so there is nothing level-specific to guard on). Measured gaps between
   strokes within one word run 100-300 ms, so 200 ms sits inside that band and can land mid-word,
   briefly suspending capture — chosen anyway, knowingly, for a faster wet-to-dry swap, since gaps
   between words (600 ms and up) clear it comfortably. If a fast writer's stroke is ever dropped or
   truncated on device, 700 ms is the measured value that sits past the intra-word band instead. A
   guard inside the settle Runnable would add nothing: both it and the `onGestureStarted` post are
   main-thread, so `removeCallbacks` ordering already decides the race. If the device pass shows the
   hardware fountain nib reading heavier than our PEN ink, see
   `OnyxRawDrawingController.setStrokeAppearance`'s note on scaling it.
   PEN's dry width no longer comes from that scaling note alone: `FountainInkSizer` (:pen-onyx)
   now replays a finished PEN stroke through Onyx's own `NeoFountainPen` (default `NeoPenConfig`,
   only `width`/`maxTouchPressure` set to match the hardware nib) and stores the widths it reports
   as each point's `StrokePoint.nibFactor`, so `inkOutline` repaints with the engine's own measured
   widths instead of our pressure/speed law. This is unvalidated against real wet ink — a device
   pass should compare the two and, if they still disagree, tune `NeoPenConfig`'s left-at-default
   knobs (dpi, smoothLevel, pressure/velocity sensitivity) rather than the width law.

10. **Orphaned image assets are never collected.** Deleting a page (or undoing an image insert past
    the undo cap) leaves its file in `<notebook>.nnote/images/`. Deleting the whole notebook still
    cleans up, since the directory goes with it. A sweep comparing files against the refs on every
    page would fix it.
11. **Dither quality on the panel.** Images are reduced to 16 greys with an 8×8 ordered dither at
    decode time (`ImageResolver`). If photographs look too coarse, the alternatives are a finer
    matrix, error diffusion (at the cost of a stable pattern across partial refreshes), or leaving
    more levels to the firmware.

12. **Resolved: curved-stroke denoising needed an averaging stage, not just simplification.** The
    original simplify-then-resample pipeline kept captured samples as knots and the spline passed
    exactly through them, so it was purely interpolating — it had no mechanism to cancel noise, and
    swept against a noiseless reference glyph its deviation never improved at any tolerance: a
    jittered straight line came out straight, but a jittered loop did not come out cleaner. Fixed by
    `smoothPath`'s arc-length Gaussian averaging ahead of `simplify` (see item 8), which moves points
    toward the curve's true line instead of only deciding which ones to keep; corners stay exact
    because `isCorner` anchors them out of the averaging window.
13. **Resolved: the pen-speed term in AUTO was inert and has been removed.** `tremorBudgetGain`
    modulated the tremor budget by median sample spacing; swept 1.0..3.0 over real ink, the selection
    rule chose 1.0 — no speed dependence at all. Deleted along with `medianSampleSpacingPx`, per the
    standing rule that a candidate failing to earn its keep in the calibration report should be
    dropped rather than kept out of caution. The merged `AUTO` formula (item 8) depends only on the
    stroke's own shape scale.

14. **Retire the finger probe.** `app/src/debug/` (`FingerProbeActivity`), `OnyxTouchProbeKnobs`,
    `OnyxRawDrawingController.touchHelperForProbe()` and `tools/finger-probe-getevent.sh` existed only
    to answer item 4, which is now answered and built against. Keep them until a device pass confirms
    the two-finger gestures behave, then delete all four together — `OnyxTouchProbeKnobs`' own KDoc
    says it must never outlive the experiment.

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
5. **Resolved: links mini map.** The `[⋈]` panel (design §4, mockups
   `2026-09-22-links-minimap-mockups.html`) is built: `EditorChromeContracts.kt`'s
   `LinksMapPanelState`/`LinksMapPanelActions`, `editor/LinksMapController.kt`,
   `ui/editor/LinksMapPanel.kt`, and `NotebookStorage.loadLinkIndex`. One hop, tap-to-select-then-act,
   `[open]`/`[centre]`/`[‹ back]`; no depth control (the design dropped it — the view is always
   exactly one hop). Not built: the `[⤢]` full-frame toggle (item 6 below).
6. **Links map: full-frame toggle.** `[⤢]` in the map header, to expand the anchored panel (item 5)
   to fill the whole page area below the bar for a larger view; `[⤡]` returns it. Design §4 marks
   this deferred.
7. **Resolved: sticker ink latency.** The sticker panel first captured the pen as ordinary touch and
   lagged on the Boox. It now uses Onyx raw drawing over the full surface with everything around the
   drawing box excluded (`PenBackend.setCaptureRegion`), and the link picker shows page previews
   (`render/PageThumbnails.kt`). Confirmed working on the Go 10.3 on 2026-09-22.

## Accepted deferrals (from Phase 2 reviews)

- Dialogs do not suppress the pen backend (inherited Phase-1 pattern).
- `TODO(erase-flash)` region-refresh mitigation in OnyxRawDrawingController.
- A self-target link jump pushes a back-stack origin (harmless).
- `Layer.images` is a bare defaulted field, not an `extra` entry, following the Phase-2 links
  precedent. An older build would decode a page with images fine but drop them on its next
  autosave, since `NotesJson` does not re-emit unknown keys. Accepted: builds are not downgraded.
- An image is always beneath the ink of its own layer; putting one over ink means putting it on a
  higher layer. True interleaving would mean replacing `Layer.strokes` with an element list.
