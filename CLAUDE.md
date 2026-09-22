# NomadNotes — project guide for Claude

A handwriting notes app for Onyx Boox e-ink tablets (validated on a Boox Go 10.3). The pen writes
on the e-ink panel through the Onyx SDK's raw-drawing path; finished strokes are persisted so ink
survives refreshes. See `README.md` for the user-facing overview and full device provisioning.

## Modules

- `:core` — pure-JVM Kotlin, no Android dependencies: the hardware-neutral note model
  (`StrokePoint`, `Notebook`, `Page`, `Layer`, `PageLink`), the undo/redo `PageEditSession`, and the
  lasso/eraser geometry. Unit-tested with JUnit (`:core:test`).
- `:app` — the Android app: editor Activities, rendering, storage, and Compose chrome. It picks the
  Onyx raw-drawing backend on Boox and a plain touch fallback elsewhere behind one `PenBackend`
  interface, so nothing above the input layer knows which backend is in use. Editor chrome (the bar
  and its anchored Tool/Page/More panels) lives in `ui/editor/`: each piece takes a `State` data
  class plus an `Actions` interface that `EditorActivity` implements, and `PanelAnchor` owns panel
  geometry. Last-opened pages persist in `<notebooks root>/.recent.json` (`core/recent/`).
- `:pen-onyx` — the **only** module that touches the Onyx SDK (`OnyxRawDrawingController`). Keep all
  Onyx-specific code here.

## Build & verify

One invocation builds core tests, app unit tests, and the debug APK:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

Build discipline — this is a low-memory host, so respect it:

- Run **one** Gradle invocation at a time; never build in parallel.
- Run `./gradlew --stop` after a build/verify to release the daemon.
- **Never** start an emulator. UI and gesture behaviour is verified on the physical Boox by a human;
  automated tests cover only the pure-JVM and Android unit layers.

## Dependencies

The Onyx SDK artifacts are vendored under `third_party/boox-m2` and resolved from there, so builds
are offline. **Never** rely on `repo.boox.com` — it is unreachable from many networks, and VPNs that
reach it tend to break `dl.google.com`. Re-vendor only when deliberately bumping the Onyx SDK.

## Commits

- Messages in English, imperative mood, describing the behaviour delivered.
- **No AI/Claude attribution and no `Co-Authored-By` lines.**
- Commit only the files a task changed.

## Device provisioning

The Boox needs USB debugging enabled and hidden-API enforcement turned off (`hidden_api_policy*`)
before raw drawing captures any ink. Full steps are in `README.md` → *Device setup (Boox)*.

Device traps that each cost a session:

- The Boox launcher's Auto Freeze can disable the app after `adb install`; run
  `adb shell pm enable com.nomadnotes` if it won't launch.
- Raw-drawing exclude rects are **surface-local**. Compose reports window coordinates, and the
  surface sits below the status bar, so convert (`EditorActivity.updateChromeExclude`) or ink lands
  on the chrome.
- The Onyx fountain engine wants pressure in 0..1. Raw 0..4095 pressure makes it emit millions of
  samples and OOM the app (`FountainInkSizer.normalizedPressure`). Raw-drawing callbacks arrive on
  the main thread on this firmware, so a slow stroke-end path is an ANR, not a lag.
- An ANR on the device leaves its report in `adb shell dumpsys dropbox --print data_app_anr`.

## Docs

- Specs: `docs/superpowers/specs/`. Plans: `docs/superpowers/plans/`.
- Future work and device-pass findings: `docs/BACKLOG.md`.
- Landing page: `site/` — static HTML/CSS/JS, no build step, outside Gradle. The hero plays device
  recordings from `site/assets/` (file names and recording spec in `site/assets/README.md`); keep it
  black and white, one typeface, minimal copy.

### Internal docs

- `docs/internals/onyx-sdk.md` — licensing and distribution terms of the vendored Onyx SDK artifacts.
- `docs/internals/geist-font.md` — provenance of the vendored Geist TTFs (`app/src/main/res/font/`) and where their OFL license text lives.
- Project framing for Alps: `docs/project_pitch.md`, `docs/problem_analysis.md`, `docs/project_brief.md`;
  interview log, filter report and market dossier in `docs/research/`.
