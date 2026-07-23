# NomadNotes

A handwriting notes app for Onyx Boox e-ink tablets (validated on a Boox Go 10.3), aiming for
the lag-free, paper-like inking feel of a Supernote. The pen writes directly on the e-ink panel
via the Onyx SDK's raw-drawing path; finished strokes are persisted so ink survives refreshes.

The editor is a working notes app: multi-page notebooks with layers and stationery templates,
pen/pencil/marker tools, an eraser, and lasso select/move/copy/paste. Supernote-style **links**
(Phase 2) turn circled main-layer handwriting into a tappable page region — tap it with the pen to
jump to another notebook page, step back through jumps with the toolbar "←", and edit or delete a
link by circling it again. `SpikeActivity` remains in the tree as the raw-drawing reference that
first proved out lag-free capture on real hardware.

## Modules

- `:core` — the hardware-neutral note model and pure-JVM logic: `StrokePoint`, `Notebook`/`Page`/
  `Layer`, `PageLink`, the undo/redo `PageEditSession`, and the lasso/eraser geometry.
- `:pen-onyx` — the only module that touches the Onyx SDK (`OnyxRawDrawingController`).
- `:app` — the editor Activities, rendering, storage, and Compose chrome; picks the Onyx backend on
  Boox and a plain touch fallback everywhere else (emulator, ordinary tablet).

## Docs

- Feature specs and implementation plans: `docs/superpowers/specs/` and `docs/superpowers/plans/`.
- Future work and known device-pass findings: `docs/BACKLOG.md`.

## Build

```bash
./gradlew :app:assembleDebug
```

No network access to Onyx's servers is required: the Onyx SDK artifacts are vendored into the
repo under `third_party/boox-m2` and resolved from there. (`repo.boox.com` is unreachable from
some networks, and VPNs that reach it tend to break `dl.google.com`.) Re-vendor against
`repo.boox.com` only when bumping the Onyx SDK version.

Install and launch on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nomadnotes/com.nomadnotes.app.NotebookListActivity
```

## Device setup (Boox)

1. Enable USB debugging on the tablet: **Settings → Apps → USB Debug Mode** (Boox exposes it
   there rather than through the usual Android developer options), then authorize the host.

2. Turn off hidden-API enforcement so the Onyx SDK can reach its system handwriting classes.
   Without this the pen's raw-drawing region maps to empty and **no ink is ever captured**:

   ```bash
   adb shell settings put global hidden_api_policy 1
   adb shell settings put global hidden_api_policy_pre_p_apps 1
   adb shell settings put global hidden_api_policy_p_apps 1
   ```

   These are device-global settings and may be reset by an OTA update or factory reset; re-apply
   them if pen input stops being captured after such an event.

3. (Optional, for hands-off test sessions) keep the screen awake:

   ```bash
   adb shell svc power stayon true
   ```
