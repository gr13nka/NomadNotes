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

## The spike is answered — build features 2 and 3 as designed

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
4. **Features 2 and 3 not started.** Designed in `~/.claude/plans/comfort-features-*.md`.

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
