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
  interface, so nothing above the input layer knows which backend is in use.
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

## Docs

- Specs: `docs/superpowers/specs/`. Plans: `docs/superpowers/plans/`.
- Future work and device-pass findings: `docs/BACKLOG.md`.
