package com.nomadnotes.pen.onyx

/**
 * PROBE ONLY — exists solely for the 2026-09 finger-touch probe (docs/BACKLOG.md item 4) and is
 * deleted together with it, never promoted into [OnyxRawDrawingController]'s permanent interface.
 *
 * Reaches four `TouchHelper` settings ([onlyEnableFingerTouch], [setTouchListenerEnabled],
 * [setPostInputEvent], [setRawInputReaderEnable]) that are speculative firmware levers, not a
 * settled part of the raw-drawing contract: the probe tries each in isolation to see which one, if
 * any, is what actually gates finger touch while raw drawing is on. `:app` has no direct line to
 * `TouchHelper` (`:pen-onyx` depends on the Onyx SDK with `implementation`, not `api`), so this
 * class is that one-off door onto it for the debug-only probe activity, kept out of
 * [OnyxRawDrawingController] so four unproven knobs do not become permanent surface on the strength
 * of one experiment. If a knob turns out to matter, its production home is a real, documented method
 * on [OnyxRawDrawingController] — not this class kept around.
 */
class OnyxTouchProbeKnobs(private val controller: OnyxRawDrawingController) {

    /** As opposed to [setFingerTouchEnabled][OnyxRawDrawingController.setFingerTouchEnabled], the
     *  SDK's naming suggests this suppresses pen input rather than adding finger input alongside
     *  it; probed to see whether the firmware treats "finger only" differently from "finger and
     *  pen together". */
    fun onlyEnableFingerTouch(enabled: Boolean) {
        controller.touchHelperForProbe().onlyEnableFingerTouch(enabled)
    }

    /** Gates the SDK's own internal touch listener — a different thing from routing events to the
     *  app, which is what [OnyxRawDrawingController.setFingerTouchEnabled] does. */
    fun setTouchListenerEnabled(enabled: Boolean) {
        controller.touchHelperForProbe().setTouchListenerEnabled(enabled)
    }

    /** Undocumented; probed on the theory that it controls whether TouchHelper posts input events
     *  onward once it has consumed them, which would explain events reaching the driver but never
     *  the view hierarchy. */
    fun setPostInputEvent(enabled: Boolean) {
        controller.touchHelperForProbe().setPostInputEvent(enabled)
    }

    /** Toggles the SDK's low-level raw input reader; probed on the theory that it is what claims
     *  the touch input device node exclusively while raw drawing is open, starving ordinary touch
     *  of finger events at the driver level. */
    fun setRawInputReaderEnable(enabled: Boolean) {
        controller.touchHelperForProbe().setRawInputReaderEnable(enabled)
    }
}
