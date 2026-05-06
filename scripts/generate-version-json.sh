#!/usr/bin/env bash
set -euo pipefail

OUT_PATH=${1:-version.json}
BRAND=${BRAND:-groovitation}
VERSION_FILE="app/brand-versions.properties"
BASE_URL=${BASE_URL:-https://groovitation.blaha.io}
APK_BASENAME=${APK_BASENAME:-${BRAND}}

version_name=$(sed -n "s/^${BRAND}\\.versionName=//p" "$VERSION_FILE" | head -n 1)
version_code=$(sed -n "s/^${BRAND}\\.versionCode=//p" "$VERSION_FILE" | head -n 1)

if [[ -z "${version_name}" || -z "${version_code}" ]]; then
  echo "Failed to parse ${BRAND} version from ${VERSION_FILE}" >&2
  exit 1
fi

cat > "$OUT_PATH" <<JSON
{
  "latest_version_name": "${version_name}",
  "latest_version_code": ${version_code},
  "download_url": "${BASE_URL}/android/${APK_BASENAME}-${version_name}.apk"
}
JSON

if [[ -n "${VERSION_ENV_PATH:-}" ]]; then
  cat > "${VERSION_ENV_PATH}" <<ENV
VERSION_NAME=${version_name}
VERSION_CODE=${version_code}
APK_NAME=${APK_BASENAME}-${version_name}.apk
ENV
fi
