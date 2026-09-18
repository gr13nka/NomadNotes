package com.nomadnotes.app.editor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One finger's contact with the panel, sampled from the raw touch stream.
 *
 * [pressure] is normalized to 0..1 (the caller divides the device's raw units by its maximum);
 * [x] and [y] are panel pixels, in the same space the caller reports contacts in.
 */
data class FingerContact(val id: Int, val x: Float, val y: Float, val pressure: Float)

/**
 * Tells a two-finger tap (undo) and a three-finger tap (redo) apart from a hold-to-arm (a one-shot
 * lasso latch) in a stream of finger-contact snapshots, while a resting palm or an ordinary pan
 * disqualifies the gesture instead of misfiring one of the three. This is the palm-rejection and
 * timing policy in one place, so [com.nomadnotes.app.input.GestureCollector]'s pen grammar and the
 * editor's touch dispatch don't each reimplement it.
 *
 * The class is a pure state machine: it takes contact snapshots and pen edges in, and calls back
 * out. It owns no clock and no input source, so it is driven and unit-tested without Android at
 * all. [onContactsChanged] and [onDeadlineReached] are the whole scheduling contract — each
 * returns the absolute time the caller should next invoke [onDeadlineReached], or `null` when
 * nothing is pending, and the caller replaces whatever tick it had scheduled with that.
 *
 * All distance thresholds are in page/panel pixels and are therefore specific to the panel they
 * were measured on (Boox Go 10.3); a different panel's touch controller may need new constants.
 * The thresholds below were chosen from a device pass on 2026-09-17 — see `docs/BACKLOG.md` item 4
 * and `docs/superpowers/plans/2026-09-17-comfort-features.md` for the full figures.
 *
 * The hold reports arming as a one-shot event rather than a sustained on/off state, because a
 * sustained hold is unobservable on this firmware: arming the lasso switches
 * [com.nomadnotes.app.input.CaptureMode], and that switch pauses raw drawing, which this firmware
 * answers by cancelling the finger touch stream the hold is made of. The 2026-09-17 device log
 * (`docs/BACKLOG.md` item 4) shows the sequence:
 *
 * ```
 * HOLD=true
 * captureMode=LASSO penGestureInProgress=false
 * reconcile useRaw=false enabled=true mode=LASSO     <- raw drawing paused
 * finger CANCEL (contacts=1)                          <- firmware cancels the finger stream
 * HOLD=false                                          <- hold disarms itself
 * captureMode=INK ... reconcile useRaw=true mode=INK  <- back to ink, pen still inks
 * ```
 *
 * The cancel arrives synthesized (`deviceId=0`, `toolType=0`), and no further event is ever
 * delivered for those contacts afterwards — there is no lift left to observe. So [onHoldArmed]
 * fires once, at the hold deadline, and leaves it to the caller to decide how long the arm lasts
 * (see [com.nomadnotes.app.input.PenBackend.Listener.onLassoArmed]). Resist turning this back into
 * a `true`/`false` pair: the `false` edge would not correspond to any real event, only to a guess
 * at when the fingers left.
 *
 * A third contact lands into the same trap. The 2026-09-18 device log is identical on 13 of 13
 * attempts:
 *
 * ```
 * touch action=5 ptrs=2      <- second finger
 * touch action=5 ptrs=3      <- third finger lands
 * finger CANCEL (contacts=1) <- same millisecond: synthesized ACTION_CANCEL, deviceId=0, toolType=0
 * ```
 *
 * No further event is ever delivered for those contacts either, so [onThreeFingerTap] fires the
 * instant the third contact is accepted, on landing rather than on lift — there is no lift to wait
 * for. The two-finger tap keeps firing on lift, because two fingers lifting is exactly the
 * ordinary case this firmware does not cancel.
 *
 * Three states drive the machine: idle (nothing happening), pending (fingers down, tap-or-hold
 * undecided), and dead (this touch sequence is disqualified, or has already produced its one
 * outcome, and everything is ignored until every finger lifts). A candidate begins the moment a
 * second contact appears and is rejected to dead unless the arrival gap, the spacing, every
 * contact's pressure, and pen quiet all clear their thresholds at once. A lift below two contacts
 * resolves it as a two-finger tap; a legitimate third contact resolves it immediately as a
 * three-finger tap instead (see [onContactsChangedWhilePending]) — a candidate that survives to a
 * lift or the hold deadline has therefore always stayed at exactly two contacts.
 *
 * @param onTwoFingerTap called once when a two-finger tap is recognized (drives undo).
 * @param onThreeFingerTap called once a third contact lands and is accepted onto a pending
 *   two-finger candidate (drives redo) — on landing, not on lift; see the class KDoc for why.
 * @param onHoldArmed called once when a two-finger candidate has been held past [HOLD_ARM_MS], so
 *   the caller can arm a one-shot lasso latch for the pen stroke that follows. A candidate that
 *   grows a third contact never reaches this: it has already resolved via [onThreeFingerTap].
 */
class MultiFingerGestures(
    private val onTwoFingerTap: () -> Unit,
    private val onThreeFingerTap: () -> Unit,
    private val onHoldArmed: () -> Unit,
) {
    private enum class State { IDLE, PENDING, DEAD }

    private var state = State.IDLE
    private var candidateStartMs = 0L

    /** Where each candidate pointer first landed, for the pending-state travel check. */
    private val candidateOrigin = mutableMapOf<Int, Pair<Float, Float>>()

    /** First-seen time per currently-down pointer id, to measure the two-finger arrival gap. */
    private val contactFirstSeenMs = mutableMapOf<Int, Long>()

    private var penDown = false
    private var lastPenUpMs: Long? = null

    /**
     * Reports the complete current set of finger contacts, called whenever that set changes or a
     * contact moves (an empty list means every finger is off). Returns the next time the caller
     * must call [onDeadlineReached], or `null` if the recognizer isn't waiting on anything.
     */
    fun onContactsChanged(contacts: List<FingerContact>, atMs: Long): Long? {
        syncFirstSeen(contacts, atMs)
        return when (state) {
            State.IDLE -> if (contacts.size >= 2) startCandidate(contacts, atMs) else null
            State.PENDING -> onContactsChangedWhilePending(contacts)
            State.DEAD -> {
                if (contacts.isEmpty()) state = State.IDLE
                null
            }
        }
    }

    /**
     * Fires when the deadline previously returned by [onContactsChanged] or this method arrives.
     * Only a still-intact pending candidate acts on it (arms the hold and goes dead — see the class
     * KDoc for why arming is one-shot rather than a state the fingers can later end); any other
     * state is a stale tick racing a state change made by [onContactsChanged] in the meantime, and
     * is a no-op. A candidate can only still be pending here at two contacts — a third contact
     * resolves (and kills) the candidate immediately, before any deadline is ever reached.
     */
    fun onDeadlineReached(atMs: Long): Long? {
        if (state != State.PENDING) return null
        val deadline = candidateStartMs + HOLD_ARM_MS
        if (atMs < deadline) return deadline
        state = State.DEAD
        onHoldArmed()
        return null
    }

    /**
     * Marks the pen down, so a candidate cannot start while it draws. Leaves an in-progress
     * candidate alone — the pen writing is exactly what is expected once a hold has armed, not a
     * reason to disqualify anything.
     */
    fun onPenDown(atMs: Long) {
        penDown = true
    }

    /** Marks the pen up and starts the [PEN_QUIET_MS] window a new candidate must wait out. */
    fun onPenUp(atMs: Long) {
        penDown = false
        lastPenUpMs = atMs
    }

    /**
     * Drops any pending candidate back to idle, for a caller that tears touch capture down
     * mid-gesture (e.g. disabling the recognizer) with no further contact updates coming. Fires no
     * callback — arming is already a one-shot event by the time this could run, so there is nothing
     * left to retract, and a candidate still pending was never reported in the first place.
     */
    fun reset() {
        state = State.IDLE
        candidateStartMs = 0L
        candidateOrigin.clear()
        // Arrival times go too: fingers may still be on the panel, and keeping their old first-seen
        // stamps would let the next snapshot start a candidate with a stagger of zero — resurrecting
        // the very gesture the caller just tore down.
        contactFirstSeenMs.clear()
    }

    /**
     * A touch cancel arrived for the fingers this recognizer is tracking — most notably the
     * synthesized cancel the firmware sends the instant a hold arms and pauses raw drawing, or the
     * instant a third finger lands (see the class KDoc). Behaviourally identical to [reset]: both
     * just go back to idle. It has its own
     * name because the reason differs — this is a genuine loss of the touch stream, not the caller
     * tearing capture down — and a future reader deciding whether either call site needs to change
     * should be able to tell the two apart.
     */
    fun onCancelled() {
        reset()
    }

    private fun startCandidate(contacts: List<FingerContact>, atMs: Long): Long? {
        if (contacts.size != 2) {
            state = State.DEAD
            return null
        }
        val (first, second) = contacts
        val stagger = contactFirstSeenMs.getValue(second.id) - contactFirstSeenMs.getValue(first.id)
        val spacing = distance(first.x, first.y, second.x, second.y)
        val qualifies = abs(stagger) <= MAX_POINTER_STAGGER_MS &&
            spacing in MIN_FINGER_SPACING_PX..MAX_FINGER_SPACING_PX &&
            first.pressure <= MAX_FINGER_PRESSURE && second.pressure <= MAX_FINGER_PRESSURE &&
            isPenQuiet(atMs)
        if (!qualifies) {
            state = State.DEAD
            return null
        }
        state = State.PENDING
        candidateStartMs = atMs
        candidateOrigin.clear()
        candidateOrigin[first.id] = first.x to first.y
        candidateOrigin[second.id] = second.x to second.y
        return candidateStartMs + HOLD_ARM_MS
    }

    private fun onContactsChangedWhilePending(contacts: List<FingerContact>): Long? {
        if (contacts.size > 2) {
            // The only legal growth is two fingers to a third, once, and it resolves the candidate
            // on the spot — see onThreeFingerTap's doc for why landing, not lift, is the trigger.
            // Anything else here (a rejected third, or straight past three) is a palm blob, not a
            // deliberate gesture, and just kills the candidate the same way.
            if (contacts.size == 3 && acceptsThirdContact(contacts)) {
                state = State.DEAD
                onThreeFingerTap()
            } else {
                state = State.DEAD
            }
            return null
        }
        if (contacts.size < 2) {
            // A finger lifted before the hold deadline: the gesture is a two-finger tap. A
            // candidate that grew a third contact is never seen here — that shape already resolved
            // and died above.
            state = if (contacts.isEmpty()) State.IDLE else State.DEAD
            onTwoFingerTap()
            return null
        }
        val strayed = contacts.any { traveledTooFar(it) }
        val palmish = contacts.any { it.pressure > MAX_FINGER_PRESSURE }
        if (strayed || palmish) {
            state = State.DEAD
            return null
        }
        return candidateStartMs + HOLD_ARM_MS
    }

    /**
     * Decides whether a third contact joining a pending two-finger candidate is a deliberate
     * three-finger gesture rather than a palm settling a moment later. All three checks must hold at
     * once:
     *  - it arrived within [MAX_POINTER_STAGGER_MS] of the *candidate's start* (not of the other
     *    fingers) — this is the rule that keeps a palm landing a beat after the two fingers already
     *    down from being folded into the same gesture;
     *  - its pressure is within [MAX_FINGER_PRESSURE], the same fingertip-vs-palm test the first two
     *    contacts already passed;
     *  - it landed within [MAX_FINGER_SPACING_PX] of the nearest already-validated contact, so it
     *    reads as part of the same landing.
     *
     * Deliberately does *not* apply [MIN_FINGER_SPACING_PX]: that minimum exists to reject one
     * smeared contact reported as two separate pointers, a risk the two already-validated fingers
     * have ruled out by the time a third arrives, and three real fingers land measurably closer
     * together than two.
     */
    private fun acceptsThirdContact(contacts: List<FingerContact>): Boolean {
        val newContact = contacts.firstOrNull { it.id !in candidateOrigin } ?: return false
        val stagger = contactFirstSeenMs.getValue(newContact.id) - candidateStartMs
        if (abs(stagger) > MAX_POINTER_STAGGER_MS) return false
        if (newContact.pressure > MAX_FINGER_PRESSURE) return false
        val nearest = contacts.filter { it.id != newContact.id }
            .minOf { distance(it.x, it.y, newContact.x, newContact.y) }
        return nearest <= MAX_FINGER_SPACING_PX
    }

    private fun traveledTooFar(contact: FingerContact): Boolean {
        val (originX, originY) = candidateOrigin.getOrPut(contact.id) { contact.x to contact.y }
        return distance(originX, originY, contact.x, contact.y) > MAX_TAP_TRAVEL_PX
    }

    private fun isPenQuiet(atMs: Long): Boolean {
        if (penDown) return false
        val lastUp = lastPenUpMs ?: return true
        return atMs - lastUp >= PEN_QUIET_MS
    }

    /** Adds newly-seen pointer ids at [atMs] and drops ids no longer present. */
    private fun syncFirstSeen(contacts: List<FingerContact>, atMs: Long) {
        contactFirstSeenMs.keys.retainAll(contacts.map { it.id }.toSet())
        for (contact in contacts) contactFirstSeenMs.putIfAbsent(contact.id, atMs)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /**
         * Widest gap allowed between two contacts' arrival for them to count as one landing —
         * between the first two contacts starting a candidate, and between the candidate's start and
         * a third contact growing it (see [acceptsThirdContact]). Measured p50 0 ms, max 43 ms on the
         * Boox Go 10.3 (fingers normally land in one report frame) — docs/BACKLOG.md item 4.
         */
        const val MAX_POINTER_STAGGER_MS = 120L

        /**
         * Narrowest allowed separation between the two contacts starting a candidate, in panel
         * pixels. Real two-finger landings measured 181-275 px apart on the Boox Go 10.3 —
         * docs/BACKLOG.md item 4. Not applied to a third contact (see [acceptsThirdContact]).
         */
        const val MIN_FINGER_SPACING_PX = 80f

        /**
         * Widest allowed separation between two contacts, in panel pixels: between the two starting a
         * candidate, and between a third contact and its nearest neighbour (see
         * [acceptsThirdContact]). See [MIN_FINGER_SPACING_PX] for the measurement it pairs with.
         */
        const val MAX_FINGER_SPACING_PX = 500f

        /**
         * Farthest a candidate pointer may drift from where it first landed and still be a tap or
         * hold rather than a pan/scroll. Real taps and holds measured ~0 px of travel on the Boox
         * Go 10.3 — docs/BACKLOG.md item 4.
         */
        const val MAX_TAP_TRAVEL_PX = 20f

        /**
         * How long fingers must stay down before the gesture commits to a hold instead of a tap —
         * the single threshold separating the two for a two-finger candidate (a third contact
         * resolves separately and immediately; see [onThreeFingerTap]'s doc).
         *
         * Measured from the second finger's landing (where a candidate begins) to the first lift,
         * this user's real two-finger undo taps on the Boox Go 10.3 ran 70, 74, and 87 ms on
         * 2026-09-18 (docs/BACKLOG.md item 4). The 138-289 ms figure this constant was previously
         * tuned against came from the 2026-09-17 probe, which measured the whole contact at the
         * kernel layer rather than from candidate start, and is not the right basis for this
         * threshold.
         *
         * 150 ms leaves ~60 ms of margin over the slowest observed tap (87 ms). This is still the
         * single tap/hold threshold: a slower tap will arm the lasso instead of firing undo/redo,
         * a known, deliberately accepted tradeoff in exchange for being able to put fingers down
         * and start drawing a selection almost immediately. The delay cannot simply be removed,
         * because the lasso must arm *before* the pen lands — raw drawing has to already be off
         * for the stroke that follows to be captured as a selection rather than inked. If
         * accidental arming shows up in use, the fix is to raise this value, trading away some of
         * that immediacy.
         */
        const val HOLD_ARM_MS = 150L

        /**
         * Highest normalized pressure a genuine fingertip contact may report. The panel never
         * reports contact size (`getTouchMajor()`/`getSize()` read 0.0 always), so pressure is the
         * only palm discriminator available: fingertips measured 0.039-0.18 normalized, a resting
         * palm saturates at 1.0. 0.47 is approximately 120/255 in the device's raw units —
         * docs/BACKLOG.md item 4. Applied to every contact, including a candidate's third.
         */
        const val MAX_FINGER_PRESSURE = 0.47f

        /**
         * How long after a pen-up a candidate may start. Covers the fact that the pen-down
         * notification is marshalled from the Onyx input thread and can lose a race with the touch
         * stream, so a palm landing a moment after pen-down could otherwise read as a gesture —
         * docs/BACKLOG.md item 4.
         */
        const val PEN_QUIET_MS = 150L
    }
}
