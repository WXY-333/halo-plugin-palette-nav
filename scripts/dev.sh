#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .tools/halo/halo.jar ]; then
  echo "Missing .tools/halo/halo.jar. Copy a Halo 2.27.x jar there first." >&2
  exit 1
fi

# Halo 2.27 is built for Java 21. Try HALO_JAVA_HOME first, then the caller's
# JAVA_HOME, then the local JDK used by this project's maintainer. A configured
# but too-old JAVA_HOME must not win just because it was exported first.
java_major() {
  "$1" -version 2>&1 | awk -F'"' '/version/ { print $2 }' | cut -d. -f1
}

JAVA_BIN=""
for candidate_home in "${HALO_JAVA_HOME:-}" "${JAVA_HOME:-}" "D:/Develop/jdk/jdk-21"; do
  [ -n "$candidate_home" ] || continue
  candidate="${candidate_home%/}/bin/java"
  if [ -x "$candidate.exe" ]; then
    candidate="$candidate.exe"
  fi
  [ -x "$candidate" ] || continue
  candidate_major="$(java_major "$candidate")"
  if [ "${candidate_major:-0}" -ge 21 ]; then
    JAVA_BIN="$candidate"
    JAVA_HOME="$candidate_home"
    break
  fi
done
if [ -z "$JAVA_BIN" ]; then
  echo "Halo 2.27 requires JDK 21+." >&2
  echo "Set HALO_JAVA_HOME or JAVA_HOME to a JDK 21 installation." >&2
  exit 1
fi

HALO_URL="${HALO_URL:-http://localhost:8095}"
HALO_ADMIN_USER="${HALO_ADMIN_USER:-admin}"
HALO_ADMIN_PASSWORD="${HALO_ADMIN_PASSWORD:-admin123}"
export JAVA_HOME

"$JAVA_BIN" -Dfile.encoding=UTF-8 \
  -jar .tools/halo/halo.jar \
  --spring.config.additional-location=optional:file:.tools/halo/ &
HALO_PID=$!
trap 'kill "$HALO_PID" 2>/dev/null || true' INT TERM EXIT

if ! command -v curl >/dev/null 2>&1; then
  echo "curl not found; Halo is running but the plugin will not be auto-enabled." >&2
  wait "$HALO_PID"
  exit $?
fi

# Wait for Halo before touching the plugin resource.
ready=""
for _ in $(seq 1 120); do
  if curl -sf -m 2 "$HALO_URL/actuator/health" >/dev/null 2>&1; then
    ready="yes"
    break
  fi
  if ! kill -0 "$HALO_PID" 2>/dev/null; then
    wait "$HALO_PID"
    exit $?
  fi
  sleep 1
done

if [ -z "$ready" ]; then
  echo "Halo did not become ready at $HALO_URL; the plugin was not auto-enabled." >&2
else
  # Halo decides whether to start a plugin from the Plugin CR's spec.enabled.
  # Development instances can come back from a restart with enabled=false, so
  # keep setting it until the reconciler reports the plugin as STARTED. The
  # repeated PATCH also covers the startup race where the reconciler processes
  # an older disabled revision right after we enable the plugin.
  plugin_url="$HALO_URL/apis/plugin.halo.run/v1alpha1/plugins/palette-nav"
  auth="$HALO_ADMIN_USER:$HALO_ADMIN_PASSWORD"
  started=""
  for _ in $(seq 1 30); do
    curl -sf -u "$auth" \
      -X PATCH \
      -H "Content-Type: application/json-patch+json" \
      --data '[{"op":"add","path":"/spec/enabled","value":true}]' \
      "$plugin_url" >/dev/null 2>&1 || true
    sleep 2
    if curl -sf -u "$auth" "$plugin_url" 2>/dev/null \
      | grep -q '"phase":"STARTED"'; then
      started="yes"
      break
    fi
  done

  if [ -n "$started" ]; then
    echo "Palette Nav plugin is running."
  else
    echo "Could not enable the palette-nav plugin. Check that" >&2
    echo "halo.security.basic-auth.disabled=false is set in .tools/halo/application.yaml" >&2
    echo "and that the local admin credentials are $HALO_ADMIN_USER/***." >&2
  fi
fi

wait "$HALO_PID"
