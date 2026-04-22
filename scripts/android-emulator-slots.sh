#!/bin/bash
# Android emulator slot manager for Paul's shell-runner CI.
#
# Claims one of the small number of shared emulator ports by pipeline ID so the
# wait can happen outside the `emulator-smoke-test` wall clock. The reservation
# survives across jobs because slot ownership is represented by a simple file.

set -euo pipefail

if [ -n "${ANDROID_EMULATOR_SLOT_IDS:-}" ]; then
    read -r -a SLOT_IDS <<< "${ANDROID_EMULATOR_SLOT_IDS}"
else
    SLOT_IDS=(5556 5558)
fi

LOCK_DIR="${ANDROID_EMULATOR_SLOT_LOCK_DIR:-/tmp/android-emulator-slots}"
WAIT_DIR="${ANDROID_EMULATOR_SLOT_WAIT_DIR:-/tmp/android-emulator-slot-waiters}"
LEGACY_LOCK_DIR="${ANDROID_EMULATOR_LEGACY_LOCK_DIR:-/tmp}"
CLAIM_TIMEOUT="${ANDROID_EMULATOR_SLOT_CLAIM_TIMEOUT:-5400}"  # 90 min
CLAIM_RETRY_INTERVAL="${ANDROID_EMULATOR_SLOT_CLAIM_RETRY_INTERVAL:-15}"
STALE_LOCK_AGE="${ANDROID_EMULATOR_SLOT_STALE_LOCK_AGE:-7200}"  # 2h
API_BASE_URL="${ANDROID_EMULATOR_SLOT_API_V4_URL:-${CI_API_V4_URL:-}}"
PROJECT_ID="${ANDROID_EMULATOR_SLOT_PROJECT_ID:-${CI_PROJECT_ID:-4}}"

mkdir -p "$LOCK_DIR" "$WAIT_DIR"
chmod 1777 "$LOCK_DIR" "$WAIT_DIR" 2>/dev/null || true

pipeline_status() {
    local pipeline_id="$1"
    local response=""

    if [ -n "$API_BASE_URL" ] && [ -n "$PROJECT_ID" ] && [ -n "${CI_JOB_TOKEN:-}" ]; then
        response=$(curl -fsS \
            --header "JOB-TOKEN: ${CI_JOB_TOKEN}" \
            "${API_BASE_URL}/projects/${PROJECT_ID}/pipelines/${pipeline_id}" 2>/dev/null || true)
    elif [ -f "${HOME}/mobile/creds/gitlab-agent-token" ]; then
        local private_token
        private_token=$(cat "${HOME}/mobile/creds/gitlab-agent-token" 2>/dev/null || true)
        if [ -n "$private_token" ]; then
            response=$(curl -fsS \
                --header "PRIVATE-TOKEN: ${private_token}" \
                "https://git.blaha.io/api/v4/projects/${PROJECT_ID}/pipelines/${pipeline_id}" 2>/dev/null || true)
        fi
    fi

    if [ -n "$response" ]; then
        printf '%s' "$response" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || true
    fi
}

queue_timestamp() {
    date +%s%N 2>/dev/null || python3 - <<'PY'
import time
print(time.time_ns())
PY
}

register_waiter() {
    local holder="$1"
    local stamp=""
    local wait_file=""

    stamp=$(queue_timestamp)
    wait_file="${WAIT_DIR}/${stamp}-${holder}-$$.wait"
    printf '%s\n' "$holder" > "$wait_file"
    echo "$wait_file"
}

oldest_waiter_file() {
    find "$WAIT_DIR" -maxdepth 1 -type f -name '*.wait' -print 2>/dev/null | LC_ALL=C sort | head -n 1
}

waiter_is_head() {
    local wait_file="$1"
    local head_waiter=""

    head_waiter=$(oldest_waiter_file)
    [ -n "$head_waiter" ] && [ "$head_waiter" = "$wait_file" ]
}

find_existing_claim() {
    local holder="$1"
    local slot=""
    local lock_file=""

    for slot in "${SLOT_IDS[@]}"; do
        lock_file="${LOCK_DIR}/slot-${slot}.lock"
        if [ -f "$lock_file" ] && [ "$(cat "$lock_file" 2>/dev/null || true)" = "$holder" ]; then
            echo "$slot"
            return 0
        fi
    done

    return 1
}

lock_status_summary() {
    local summary=()
    local slot=""
    local lock_file=""
    local lock_holder=""
    local joined=""

    for slot in "${SLOT_IDS[@]}"; do
        lock_file="${LOCK_DIR}/slot-${slot}.lock"
        if [ -f "$lock_file" ]; then
            lock_holder=$(cat "$lock_file" 2>/dev/null || echo "?")
            summary+=("${slot}:${lock_holder}")
        else
            summary+=("${slot}:free")
        fi
    done

    printf -v joined '%s,' "${summary[@]}"
    echo "${joined%,}"
}

try_lock_slot() {
    local slot="$1"
    local holder="$2"
    local lock_file="${LOCK_DIR}/slot-${slot}.lock"
    local legacy_lock_file="${LEGACY_LOCK_DIR}/android-emulator-${slot}.lock"
    local legacy_lock_fd=""

    exec {legacy_lock_fd}> "$legacy_lock_file"
    if ! flock -n "$legacy_lock_fd" 2>/dev/null; then
        exec {legacy_lock_fd}>&-
        return 1
    fi

    flock -u "$legacy_lock_fd" 2>/dev/null || true
    exec {legacy_lock_fd}>&-

    if (set -o noclobber; echo "$holder" > "$lock_file") 2>/dev/null; then
        return 0
    fi

    return 1
}

try_claim_slot() {
    local holder="$1"
    local preferred_slot="${2:-}"
    local existing_slot=""
    local slot=""

    existing_slot=$(find_existing_claim "$holder" || true)
    if [ -n "$existing_slot" ]; then
        echo "$existing_slot"
        return 0
    fi

    if [ -n "$preferred_slot" ] && try_lock_slot "$preferred_slot" "$holder"; then
        echo "$preferred_slot"
        return 0
    fi

    for slot in "${SLOT_IDS[@]}"; do
        if [ -n "$preferred_slot" ] && [ "$slot" = "$preferred_slot" ]; then
            continue
        fi

        if try_lock_slot "$slot" "$holder"; then
            echo "$slot"
            return 0
        fi
    done

    return 1
}

cleanup_stale_locks() {
    local slot=""
    local lock_file=""
    local holder=""
    local active_status=""
    local lock_age=0

    for slot in "${SLOT_IDS[@]}"; do
        lock_file="${LOCK_DIR}/slot-${slot}.lock"
        if [ ! -f "$lock_file" ]; then
            continue
        fi

        holder=$(cat "$lock_file" 2>/dev/null || echo "")
        if [ -z "$holder" ]; then
            rm -f "$lock_file"
            continue
        fi

        active_status=""
        if [[ "$holder" =~ ^[0-9]+$ ]]; then
            active_status=$(pipeline_status "$holder")
        fi

        if [ -n "$active_status" ]; then
            case "$active_status" in
                running|pending|created|preparing|waiting_for_resource|manual)
                    continue
                    ;;
            esac

            echo "  Cleaning stale emulator slot port $slot (pipeline $holder status=$active_status)" >&2
            rm -f "$lock_file"
            continue
        fi

        lock_age=$(( $(date +%s) - $(stat -c %Y "$lock_file" 2>/dev/null || echo 0) ))
        if [ $lock_age -gt $STALE_LOCK_AGE ]; then
            echo "  Cleaning stale emulator slot port $slot (holder $holder age=${lock_age}s)" >&2
            rm -f "$lock_file"
        fi
    done
}

cleanup_stale_waiters() {
    local wait_file=""
    local holder=""
    local active_status=""
    local wait_age=0

    for wait_file in "${WAIT_DIR}"/*.wait; do
        [ -e "$wait_file" ] || continue

        holder=$(cat "$wait_file" 2>/dev/null || echo "")
        if [ -z "$holder" ]; then
            rm -f "$wait_file"
            continue
        fi

        active_status=""
        if [[ "$holder" =~ ^[0-9]+$ ]]; then
            active_status=$(pipeline_status "$holder")
        fi

        if [ -n "$active_status" ]; then
            case "$active_status" in
                running|pending|created|preparing|waiting_for_resource|manual)
                    continue
                    ;;
            esac

            echo "  Cleaning stale emulator waiter $(basename "$wait_file") (pipeline $holder status=$active_status)" >&2
            rm -f "$wait_file"
            continue
        fi

        wait_age=$(( $(date +%s) - $(stat -c %Y "$wait_file" 2>/dev/null || echo 0) ))
        if [ $wait_age -gt $STALE_LOCK_AGE ]; then
            echo "  Cleaning stale emulator waiter $(basename "$wait_file") (holder $holder age=${wait_age}s)" >&2
            rm -f "$wait_file"
        fi
    done
}

claim_slot() {
    local holder="${1:-$$}"
    local preferred_slot="${2:-}"
    local start=0
    local claimed_slot=""
    local wait_file=""
    local head_waiter=""
    local head_holder="none"
    local elapsed=0

    claimed_slot=$(find_existing_claim "$holder" || true)
    if [ -n "$claimed_slot" ]; then
        echo "$claimed_slot"
        return 0
    fi

    start=$(date +%s)
    wait_file=$(register_waiter "$holder")
    trap "rm -f '$wait_file'" EXIT INT TERM

    cleanup_stale_waiters
    cleanup_stale_locks

    while true; do
        cleanup_stale_waiters
        cleanup_stale_locks

        claimed_slot=$(find_existing_claim "$holder" || true)
        if [ -n "$claimed_slot" ]; then
            rm -f "$wait_file"
            trap - EXIT INT TERM
            echo "$claimed_slot"
            return 0
        fi

        if waiter_is_head "$wait_file"; then
            claimed_slot=$(try_claim_slot "$holder" "$preferred_slot" || true)
            if [ -n "$claimed_slot" ]; then
                rm -f "$wait_file"
                trap - EXIT INT TERM
                echo "$claimed_slot"
                return 0
            fi
        fi

        elapsed=$(($(date +%s) - start))
        if [ $elapsed -ge $CLAIM_TIMEOUT ]; then
            echo "ERROR: No available emulator slot after ${CLAIM_TIMEOUT}s (locks=$(lock_status_summary))" >&2
            rm -f "$wait_file"
            trap - EXIT INT TERM
            return 1
        fi

        head_waiter=$(oldest_waiter_file)
        head_holder="none"
        if [ -n "$head_waiter" ] && [ -f "$head_waiter" ]; then
            head_holder=$(cat "$head_waiter" 2>/dev/null || echo "unknown")
        fi

        echo "  Still waiting for emulator slot (${elapsed}s elapsed, queue_head=${head_holder}, locks=$(lock_status_summary))..." >&2
        sleep "$CLAIM_RETRY_INTERVAL"
    done
}

release_slot() {
    local slot="$1"
    local holder="${2:-$$}"
    local lock_file="${LOCK_DIR}/slot-${slot}.lock"
    local current_holder=""

    if [ ! -f "$lock_file" ]; then
        return 0
    fi

    current_holder=$(cat "$lock_file" 2>/dev/null || echo "")
    if [ "$current_holder" = "$holder" ]; then
        rm -f "$lock_file"
        echo "Released emulator slot port $slot"
        return 0
    fi

    echo "WARNING: Emulator slot port $slot held by $current_holder, not $holder" >&2
    return 1
}

status_slots() {
    local slot=""
    local lock_file=""
    local holder=""
    local head_waiter=""

    echo "Android emulator slot status:"
    for slot in "${SLOT_IDS[@]}"; do
        lock_file="${LOCK_DIR}/slot-${slot}.lock"
        if [ -f "$lock_file" ]; then
            holder=$(cat "$lock_file" 2>/dev/null || echo "?")
            echo "  Port $slot: LOCKED by $holder"
        else
            echo "  Port $slot: AVAILABLE"
        fi
    done

    head_waiter=$(oldest_waiter_file)
    if [ -n "$head_waiter" ]; then
        echo "  Queue head: $(basename "$head_waiter")"
    fi
}

case "${1:-}" in
    claim) claim_slot "${2:-$$}" "${3:-}" ;;
    release) release_slot "$2" "${3:-$$}" ;;
    status) status_slots ;;
    cleanup)
        cleanup_stale_waiters
        cleanup_stale_locks
        ;;
    *)
        echo "Usage: $0 {claim [holder] [preferred_slot]|release <slot> [holder]|status|cleanup}"
        exit 1
        ;;
esac
