#!/usr/bin/env bash
set -euo pipefail

OUT_PATH=${1:-version.json}
GRADLE_FILE="app/build.gradle.kts"

version_name=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -n 1)
version_code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE" | head -n 1)

if [[ -z "${version_name}" || -z "${version_code}" ]]; then
  echo "Failed to parse version from ${GRADLE_FILE}" >&2
  exit 1
fi

cat > "$OUT_PATH" <<JSON
{
  "latest_version_name": "${version_name}",
  "latest_version_code": ${version_code},
  "download_url": "https://groovitation.blaha.io/android/groovitation-${version_name}.apk"
}
JSON
