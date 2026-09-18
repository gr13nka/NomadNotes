#!/usr/bin/env bash
#
# Kernel-level capture for docs/BACKLOG.md item 4 — does this firmware deliver finger touches while
# Onyx raw drawing is enabled? Runs entirely against `getevent`, so it needs no app build and can be
# used before FingerProbeActivity (app/src/debug) is even installed.
#
# Self-paced and labelled, deliberately not timed: a fixed capture window was tried first and
# failed, because it depends on the operator starting the gesture inside a window they cannot see.
# Instead each step prints its instruction, starts `getevent` in the background right away, and
# waits for Enter before moving to the next one — so the operator drives the pace, not the script.
#
# Requires: adb on PATH, nothing else. Run this with the NomadNotes editor already open on the Boox
# (raw drawing active) — the script prints the foreground activity so you can confirm that, but it
# does not launch the app for you.

set -euo pipefail

# Deliberately NO device argument: this firmware's getevent takes at most one device and answers a
# second one with its usage text, silently capturing nothing. Reading every device instead costs a
# little noise from the power and hall-sensor nodes, and each line carries its own /dev/input/eventN
# prefix, so the pen (Wacom) and touch (cyttsp5) streams stay tellable apart during analysis.

RUN_DIR="tools/finger-probe-runs/$(date +%Y%m%d-%H%M%S)"

STEP_LABELS=(
  "PALM_REST_WRITING_POSTURE"
  "PALM_FLAT"
  "TWO_FINGER_TAP_X5"
  "TWO_FINGER_HOLD_X3"
  "ONE_FINGER_TAP_X3"
  "PEN_WRITING_ONLY"
  "PEN_WRITING_PALM_RESTING"
  "FINGER_TAP_WHILE_PEN_DOWN"
)

STEP_PROMPTS=(
  "Rest your palm on the panel in normal writing posture (as if about to write) and hold it there."
  "Press your whole palm flat onto the panel."
  "Two-finger tap the panel 5 times (five brief, separate taps)."
  "Two-finger hold (press and hold with two fingers) 3 times."
  "One-finger tap the panel 3 times."
  "Write a few words with the pen only — no touch."
  "Write a few words with the pen while resting your palm on the panel normally."
  "Hold the pen down (or write), then tap the panel with a finger while the pen is still down."
)

command -v adb >/dev/null 2>&1 || { echo "adb not found on PATH" >&2; exit 1; }

if ! adb devices | grep -qw "device$"; then
  echo "No device found by 'adb devices'. Connect the Boox with USB debugging enabled." >&2
  exit 1
fi

foreground_activity() {
  adb shell dumpsys activity activities 2>/dev/null | grep -m1 "mResumedActivity" | sed 's/^[[:space:]]*//'
}

echo "Foreground activity: $(foreground_activity || echo "(couldn't read it — check adb permissions)")"
echo "Confirm the NomadNotes editor is open on the device (raw drawing active) before continuing."
read -r -p "Press Enter to start, or Ctrl-C to abort... " _

mkdir -p "$RUN_DIR"
echo "Capturing all input devices into $RUN_DIR/"

for i in "${!STEP_LABELS[@]}"; do
  label="${STEP_LABELS[$i]}"
  prompt="${STEP_PROMPTS[$i]}"
  out="$RUN_DIR/$label.txt"

  echo
  echo "=== $label ==="
  echo "$prompt"
  echo "Do it now; press Enter here when done."

  adb shell "getevent -lt" >"$out" 2>&1 &
  cap_pid=$!

  read -r _

  # The local adb client is what we can actually reach; killing it drops the shell session, which
  # is enough for adbd to tear down the remote getevent for a normal (non -t/-tt) `adb shell`
  # invocation. Belt-and-braces: also ask the device to reap any getevent it left behind, since a
  # stray reader left running (it never exclusively grabs the node) would just be wasted CPU, not a
  # correctness problem for later steps.
  kill "$cap_pid" >/dev/null 2>&1 || true
  wait "$cap_pid" 2>/dev/null || true
  adb shell "pkill -f 'getevent -lt'" >/dev/null 2>&1 || true

  # Fail loudly and immediately. A capture that silently recorded nothing wastes the whole session,
  # which is exactly what a rejected device argument did the first time this script was run.
  events=$(grep -c "EV_" "$out" 2>/dev/null || echo 0)
  if [ "$events" -eq 0 ]; then
    echo "  !! NO EVENTS CAPTURED for $label — something is wrong, not a finding."
    if grep -q "Usage: getevent" "$out" 2>/dev/null; then
      echo "  !! getevent rejected its arguments; this run is not usable."
    fi
  else
    echo "  ok: $events events"
  fi

  echo "Wrote $out"
done

echo
echo "Done. Captures are under $RUN_DIR/"
