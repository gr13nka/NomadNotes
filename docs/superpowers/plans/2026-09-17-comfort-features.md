# Comfort features — state at 2026-09-17

Working document for the M1 comfort features (smoothing calibration, two-finger gestures).
Durable findings live in `docs/BACKLOG.md` items 4, 8, 9, 12, 13 — read those first. This file
records only what is in flight and what a fresh session would otherwise have to rediscover.

## Delivered this round (uncommitted, working tree)

Feature 1 complete and verified by `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
(23 `:core` tests green), plus the spike instrumentation. Nothing committed yet.

- `core/.../ink/Smoothing.kt` — `SmoothingLevel.AUTO`, per-stroke tuning
  (`min(tremor budget, fraction of shape scale)`), named constants carrying their evidence.
  The six algorithm functions are untouched.
- `core/.../ink/SmoothingTest.kt` — AUTO behaviour tests, invariants looped over all levels.
- `core/.../ink/SmoothingCalibrationReport.kt` — `Assume`-gated corpus tool.
- `core/build.gradle.kts` — passes `-Dcalibration.corpus` through to the test JVM. Gradle's `-D`
  lands on the daemon, not the forked test JVM, so without this the documented command silently
  runs nothing.
- App wiring: prefs default `AUTO`, `toggleSmoothing()` (AUTO/OFF), `SMOOTHING_SETTLE_MS` 700,
  preview pinned to fixed `LIGHT`, `smoothing_auto` string.
- Spike: `app/src/debug/` probe activity, `OnyxTouchProbeKnobs`, `setFingerTouchEnabled`,
  `tools/finger-probe-getevent.sh`.

## Features 2 and 3 are built (2026-09-17/18)

Outcome A. Fingers **are** delivered with raw drawing on, multi-touch works, pen and finger
coexist, and `enableFingerTouch` was never needed (all observations taken with it `OFF`).
Full numbers in BACKLOG item 4. Two consequences for the implementation:

1. **Contact size does not exist on this panel.** `getTouchMajor()`/`getSize()` are `0.0` always.
   Palm rejection uses **pressure**: fingertip 0.039-0.18 normalized, palm saturates at 1.0.
2. **Finger delivery to the SurfaceView is lossy** — 143 events reached `dispatchTouchEvent` but
   only 73 reached the SurfaceView, and 5 streams ended in `CANCEL`. If the recognizer proves
   lossy reading from the backend's listener, feed it from an Activity-level dispatch hook.

Measured thresholds (device, 2026-09-17): tap 138-289 ms, hold 1559-1607 ms, stagger 0-41 ms,
separation 181-251 px, travel ~0 px. The planned 600 ms tap/hold split sits in a wide clean gap.

*Superseded 2026-09-18:* the shipped split is 150 ms, from the author's real taps (70-87 ms) rather
than these probe figures; three-finger redo was added and fires on landing. See BACKLOG item 4.

Two-finger tap undoes, three-finger tap redoes, and holding two fingers arms the next pen stroke
as a lasso. The whole build is green (`:core:test` + `:app:testDebugUnitTest` + `:app:assembleDebug`):

- `core`-free `app/.../editor/MultiFingerGestures.kt` — the pure state machine (idle / pending /
  dead) holding every threshold and the pen veto. Unit-tested without Android.
- `app/.../input/FingerGestures.kt` — the MotionEvent adapter; owns only which pointers are fingers
  and when the recognizer's deadline falls due.
- `PenBackend.Listener` gained `onUndoGesture()`, `onRedoGesture()` and `onLassoArmed()` — intent,
  not pointer mechanics, so the single-pointer `StrokePoint` vocabulary is untouched.
- `OnyxPenBackend` installs **one** touch listener for the whole attachment and feeds both paths from
  it; `reconcile()` correspondingly shrank to flipping a flag. Capture mode is now latched at pen-down.
- `EditorActivity` — `undoByGesture()`/`redoByGesture()` (badge + one deferred clean refresh doing
  both jobs), `armLassoForNextStroke()` (a one-shot latch, 5 s expiry), and a new
  `applyCaptureMode()` that is the single writer of `backend.captureMode`. Badge drawn by a new
  `render/BadgeRenderer.kt` through `presentDecorated()`.

The device passes changed the design in three places — the lasso became a one-shot arm, redo fires
when the third finger lands, and raw-channel callbacks are dropped while raw drawing is paused. All
three trace to the firmware cancelling finger streams; BACKLOG item 4 has the evidence.

Fixes that fell out of building it:

- **`GestureCollector` followed pointer *index 0*, not the pointer that started the gesture.** It now
  captures a pointer by id and starts on `ACTION_POINTER_DOWN` too (stylus-only backends). Written in
  the belief that resting fingers would displace the stylus from index 0; the device log later showed
  pen and fingers arrive as separate input devices, so separate streams, and that case does not arise
  on the Boox. Kept as correct, not load-bearing.
- **The side-button erase channel had no pen-down edge.** `onBeginRawErasing` never called
  `onGestureStarted`, so the smoothing settle could blit mid-erase (a pre-existing bug) and a palm
  landing during an erase could arm a finger gesture. It now reports the edge like drawing does.
- **Capture mode was applied mid-stroke**, which dropped the stroke in flight — the latent bug the
  design predicted. Latching at pen-down fixes it and delivers the guarantee the modifier needs.

## Open threads

1. **Prose corpus not yet gathered.** `test.nnote` is unchanged since 2026-09-02, so the constants
   are still tuned against test marks. Set the toolbar to `Smooth: Off`, write a page of ordinary
   prose, `adb pull`, then re-run the report and re-apply its selection rule.
2. **"Ink looks pixelated" — unresolved.** Reported while the *probe* screen was foreground, which
   does not render real ink, so it may be nothing. If it reproduces in a real page it is a genuine
   regression: until now the default was `OFF`, so the settle repaint never ran and the panel always
   showed the firmware's own wet ink. It now replaces that with our rendered bitmap 700 ms after
   pen-up. `StrokeRenderer` does set `isAntiAlias = true`, so look further than that.
3. **BACKLOG item 9 unconfirmed.** The settle path is live in normal use for the first time. Needs a
   fast writer on device to confirm no stroke is ever dropped or truncated.
4. **Features 2 and 3: last round not yet seen on device.** Undo and the lasso arm are confirmed on
   the Boox, and finger delivery from the SurfaceView listener proved reliable. Awaiting a pass:
   three-finger redo on landing, the 150 ms arm (does a normal-speed undo tap ever arm the lasso
   instead?), and drawing immediately after arming. Still never measured: a **writing-posture palm**
   against `MAX_FINGER_PRESSURE` (0.47) — only a whole open palm was — and it is the one input that
   could misfire an undo or redo mid-sentence. Then remove the temporary `GestureDebug` logging.
   Original design: `~/.claude/plans/comfort-features-*.md`.

5. **The probe can be retired** once the above passes — BACKLOG item 14 lists the four pieces.

## Environment gotchas that cost time

- **`JAVA_HOME` is stale**: it points at `/Users/username/jdk17/...`, which no longer exists, so
  `./gradlew` fails outright. A valid JDK 17 is at
  `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`; prefix the invocation with it.
- **`getevent` on this firmware takes at most ONE device.** Passing two makes it print its usage and
  capture nothing, silently. The script now reads all devices and shouts if a step records nothing.
- **A second LAUNCHER activity hides the app's own icon** on the Boox launcher — one entry per
  package. The probe therefore has no icon; start it with
  `adb shell am start -n com.nomadnotes/.app.probe.FingerProbeActivity`.
- **Editor toolbar only exists inside a page**, not in the notebook list.
- Device state: stale `STRONG` smoothing preference was cleared so the new `AUTO` default applies.
