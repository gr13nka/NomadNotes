# Comfort features: smoothing calibration and two-finger gestures

Standalone work document. Self-contained — no need to read prior conversations.
Project guide: `CLAUDE.md`. Known device-pass findings: `docs/BACKLOG.md`.

## Context

NomadNotes is a handwriting notes app for Onyx Boox e-ink tablets, validated on a Boox Go 10.3. The
pen writes through the Onyx SDK's raw-drawing path, so ink appears with no perceptible lag; finished
strokes are persisted so they survive e-ink refreshes.

The app works, but it is not yet *comfortable* to write in daily. This document covers three
conveniences the author misses in real use. They are ranked by what actually blocks them, not by
apparent size — **feature 1 is calibration of code that already exists, while features 2 and 3 are
both gated behind the same unanswered hardware question.**

Read `docs/BACKLOG.md` items 4 and 8 before starting; both are directly relevant and record what the
last device pass found.

---

## Feature 1 — Make stroke smoothing good by default

**This is not new code. `core/src/main/kotlin/com/nomadnotes/core/ink/Smoothing.kt` already implements
it** — Ramer–Douglas–Peucker simplification followed by resampling along a centripetal Catmull–Rom
spline, with the stroke's own endpoints preserved verbatim. The algorithm is sound and well
documented. Do not rewrite it.

**What is actually wrong:** the tuning constants were picked by eye, not measured. From
`Smoothing.kt`:

```kotlin
SmoothingLevel.LIGHT  -> Tuning(epsilonPx = 1.2f, spacingPx = 2.5f)
SmoothingLevel.STRONG -> Tuning(epsilonPx = 3.0f, spacingPx = 2.5f)
```

The comment above them says as much: *"Device-tuned against the Boox Go 10.3 panel; expect these to
move after a device pass."* `docs/BACKLOG.md` item 8 records the same thing.

**The work:**

1. Capture real point streams from the Boox digitizer while writing ordinary prose at natural speed —
   not test squiggles. Small handwriting matters most: that is where over-smoothing destroys letter
   shapes, and where the current values are most likely wrong.
2. Retune `epsilonPx` and `spacingPx` against that data. The goal is that letters keep their form
   while sample-to-sample tremor disappears.
3. **Consider replacing the three-way manual setting (`OFF`/`LIGHT`/`STRONG`) with one automatic
   behaviour.** The author's phrasing is "auto-smoothing" — meaning it should simply be right, not a
   knob to fiddle with. If smoothing strength should vary, derive it from the stroke itself (pen
   speed, stroke extent) rather than asking the user. Keep `SmoothingLevel` as the vocabulary type if
   a manual override is still wanted, but the default path must need no configuration.

**Constraints that must not break.** These are contracts other code relies on, documented in
`Smoothing.kt`:

- `OFF` returns the input list unchanged, by identity.
- A gesture with fewer than `MIN_SMOOTHABLE_POINTS` distinct positions (a tap or dot) is returned
  unchanged — tap-sized marks must never be reshaped away.
- First and last points are preserved exactly, including pressure and `timestampDelta`. The stroke
  keeps its total duration, which `TapClassifier` reads from the last point. Breaking this breaks
  link tapping.

**Also verify (`docs/BACKLOG.md` item 9):** `SMOOTHING_SETTLE_MS` is 300 ms and a pen-down cancels the
settle blit via `PenBackend.Listener.onGestureStarted`. If a fast writer ever sees a dropped or
truncated stroke with smoothing on, that window is the first suspect.

---

## The blocker for features 2 and 3 — do a device spike first

**Both remaining features need finger touches to be delivered while the pen is active. Nobody has
confirmed the firmware does that.** `docs/BACKLOG.md` item 4 records this as an open question.

What the code currently assumes:

- `GestureCollector` takes a `stylusOnly` flag. Its own documentation says finger touch is ignored on
  Onyx, "where the finger is not a drawing tool."
- `OnyxPenBackend` documents that while raw drawing is enabled, the firmware suppresses normal window
  rendering. For lasso capture the backend turns raw drawing **fully off** and captures the stylus as
  ordinary `MotionEvent`s instead, precisely because the raw path stops delivering per-sample move
  callbacks.

**Spike, before writing any feature code.** On the physical Boox, with raw drawing enabled, log every
`MotionEvent` reaching the surface and answer:

1. Are finger `MotionEvent`s delivered at all while raw drawing is on?
2. If yes, are multi-touch pointers reported (can two simultaneous fingers be distinguished)?
3. Can a finger and the pen be active at the same time, or does one suppress the other?
4. What does a resting palm look like in that stream — how is it distinguishable from a deliberate
   two-finger touch (pointer count, contact size via `getTouchMajor`, timing)?

**Report the answers before implementing.** If fingers are not delivered while raw drawing is on,
both features need a different design — most likely toggling raw drawing off on a finger-down, the
way the lasso path already does — and that trade-off is a decision to surface, not to make silently.

---

## Feature 2 — Two-finger tap to undo

Two fingers tapped briefly on the page undoes the last edit. A well-established gesture on tablets,
and it removes the most frequent reason to reach for the toolbar while writing.

**Behaviour:**
- Two fingers down and up together, within a short time and with little movement, triggers one undo.
- Repeated taps step back through history, so a mis-stroke can be undone several times quickly.
- Consider three fingers for redo only if it comes cheaply; undo is the feature that matters.

**Wire it to the existing undo.** `PageEditSession` in `:core` already owns the undo/redo command
stack. This is an input gesture routed to an existing operation — no new editing logic.

**Hazards that will decide whether this feels good or awful:**
- **A resting palm must never trigger an undo.** This is the failure that would make people stop
  trusting the app entirely. Palm contact is large and sustained; a real two-finger tap is small,
  brief and has two distinct pointers. Use contact size and duration, not pointer count alone.
- A tap while the pen is down must not fire — the hand is busy writing.
- Give feedback that an undo happened. On e-ink a silent change is easy to miss; the repaint must
  make it obvious what disappeared.

---

## Feature 3 — Hold two fingers, circle with the pen, get a lasso

While two fingers rest on the page, anything drawn with the pen is captured as a lasso selection
instead of ink. Release the fingers and the pen inks normally again. This removes the tool-switching
round trip that currently breaks the flow of writing — today, selecting means going to the toolbar,
picking lasso, drawing, and switching back.

**Why this is the most valuable of the three:** it is the only one that changes how writing *feels*
rather than how it looks. It makes selection a modifier, like holding Shift, instead of a mode.

**Architecturally this fits what already exists.** `PenBackend` has `captureMode`
(`INK` / `ERASE` / `LASSO`), and `OnyxPenBackend` already handles `LASSO` by turning raw drawing off
and capturing the stylus through a `GestureCollector` as ordinary touch. The feature is: two fingers
down → set `captureMode = LASSO`; fingers up → restore the previous mode.

**What to get right:**
- **Mode changes must be visible.** The user has to know the pen will select rather than write,
  *before* drawing. On e-ink an unannounced mode change is disorienting.
- **The transition must not drop the stroke.** Switching raw drawing off mid-gesture is exactly the
  situation `OnyxPenBackend.reconcile` exists to manage; follow its existing pattern rather than
  inventing a second one.
- **Lifting the fingers mid-stroke** needs defined behaviour — finish the lasso, or abandon it.
  Decide deliberately and document the choice.
- The existing lasso result handling (selection, move, copy, paste) must be reused unchanged.

---

## Non-goals

- **Do not add a gesture settings screen.** These should work without configuration. A preferences
  panel is how this turns into a project instead of a convenience.
- **Do not add more gestures than these.** Pinch-zoom, swipe-to-page, three-finger anything — out of
  scope. Each gesture is a chance to fire by accident while a hand rests on the page.
- **Do not touch the link features.** Boox's own Notes app now ships page linking; this project is
  not competing on that axis any more. See `docs/BRIEF.md`.
- **Do not rewrite `Smoothing.kt`.** Calibrate it.

## Verification

Per `CLAUDE.md`:

- One Gradle invocation at a time, on a low-memory host. Run `./gradlew --stop` afterwards.
- **Never start an emulator.** Gesture and UI behaviour is verified on the physical Boox by a human.
- Automated tests cover the pure-JVM and Android unit layers only. Smoothing changes belong in
  `:core:test`; gesture-grammar changes belong in `:app:testDebugUnitTest`.

Build everything with:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

Gesture work cannot be validated by tests. Expect a device pass, and record what it finds in
`docs/BACKLOG.md` the way previous passes did.
