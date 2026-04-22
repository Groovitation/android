#!/bin/bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$TMP_DIR/locks" "$TMP_DIR/waiters" "$TMP_DIR/legacy"

export ANDROID_EMULATOR_SLOT_LOCK_DIR="$TMP_DIR/locks"
export ANDROID_EMULATOR_SLOT_WAIT_DIR="$TMP_DIR/waiters"
export ANDROID_EMULATOR_LEGACY_LOCK_DIR="$TMP_DIR/legacy"
export ANDROID_EMULATOR_SLOT_IDS="5556"
export ANDROID_EMULATOR_SLOT_CLAIM_TIMEOUT=15
export ANDROID_EMULATOR_SLOT_CLAIM_RETRY_INTERVAL=1
export ANDROID_EMULATOR_SLOT_STALE_LOCK_AGE=30
unset ANDROID_EMULATOR_SLOT_API_V4_URL ANDROID_EMULATOR_SLOT_PROJECT_ID CI_API_V4_URL CI_PROJECT_ID CI_JOB_TOKEN

FIRST_WAITER_ID="waiter-101"
SECOND_WAITER_ID="waiter-202"

echo "seed-holder" > "$ANDROID_EMULATOR_SLOT_LOCK_DIR/slot-5556.lock"

bash "$ROOT_DIR/scripts/android-emulator-slots.sh" claim "$FIRST_WAITER_ID" > "$TMP_DIR/claim-101.out" 2> "$TMP_DIR/claim-101.err" &
PID_101=$!
sleep 1

bash "$ROOT_DIR/scripts/android-emulator-slots.sh" claim "$SECOND_WAITER_ID" > "$TMP_DIR/claim-202.out" 2> "$TMP_DIR/claim-202.err" &
PID_202=$!

sleep 2
rm -f "$ANDROID_EMULATOR_SLOT_LOCK_DIR/slot-5556.lock"

wait "$PID_101"
CLAIM_101=$(cat "$TMP_DIR/claim-101.out")
if [ "$CLAIM_101" != "5556" ]; then
    echo "expected first waiter to claim port 5556, got '$CLAIM_101'" >&2
    exit 1
fi

RECLAIM_101=$(bash "$ROOT_DIR/scripts/android-emulator-slots.sh" claim "$FIRST_WAITER_ID")
if [ "$RECLAIM_101" != "5556" ]; then
    echo "expected existing holder to re-read port 5556, got '$RECLAIM_101'" >&2
    exit 1
fi

if ! kill -0 "$PID_202" 2>/dev/null; then
    echo "second waiter claimed a slot before the first waiter released it" >&2
    cat "$TMP_DIR/claim-202.out" >&2 || true
    exit 1
fi

bash "$ROOT_DIR/scripts/android-emulator-slots.sh" release 5556 "$FIRST_WAITER_ID" >/dev/null

wait "$PID_202"
CLAIM_202=$(cat "$TMP_DIR/claim-202.out")
if [ "$CLAIM_202" != "5556" ]; then
    echo "expected second waiter to claim port 5556 after release, got '$CLAIM_202'" >&2
    exit 1
fi

if find "$ANDROID_EMULATOR_SLOT_WAIT_DIR" -maxdepth 1 -type f -name '*.wait' | grep -q .; then
    echo "waiter queue should be empty after both claimers finish" >&2
    find "$ANDROID_EMULATOR_SLOT_WAIT_DIR" -maxdepth 1 -type f -name '*.wait' -print >&2
    exit 1
fi
