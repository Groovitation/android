#!/bin/bash
# Run Maestro tests for Groovitation Android app
#
# Usage:
#   ./run-maestro-tests.sh           # Run all tests
#   ./run-maestro-tests.sh 01        # Run test 01 only
#   ./run-maestro-tests.sh studio    # Open Maestro Studio

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAESTRO_DIR="$SCRIPT_DIR/.maestro"

# Check if Maestro is installed
if ! command -v maestro &> /dev/null; then
    echo "Maestro not found. Installing..."
    curl -Ls "https://get.maestro.mobile.dev" | bash
    export PATH="$PATH:$HOME/.maestro/bin"
fi

# Check if emulator/device is connected
if ! adb devices | grep -q "device$"; then
    echo "No Android device/emulator found. Please start one:"
    echo "  emulator -list-avds"
    echo "  emulator -avd <avd_name>"
    exit 1
fi

# Create screenshots directory
mkdir -p "$MAESTRO_DIR/screenshots"

case "${1:-all}" in
    studio)
        echo "Opening Maestro Studio..."
        maestro studio
        ;;
    all)
        echo "Running all Maestro tests..."
        maestro test "$MAESTRO_DIR/"
        ;;
    *)
        # Run specific test by number
        TEST_FILE=$(find "$MAESTRO_DIR" -name "${1}*.yaml" | head -1)
        if [ -n "$TEST_FILE" ]; then
            echo "Running test: $TEST_FILE"
            maestro test "$TEST_FILE"
        else
            echo "Test not found: $1"
            echo "Available tests:"
            ls -1 "$MAESTRO_DIR"/*.yaml
            exit 1
        fi
        ;;
esac

echo ""
echo "Screenshots saved to: $MAESTRO_DIR/screenshots/"
