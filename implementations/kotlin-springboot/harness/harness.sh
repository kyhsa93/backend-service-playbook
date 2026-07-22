#!/usr/bin/env bash
# Kotlin Spring Boot Harness — checks Kotlin project structure/annotation rules
# Usage: ./harness.sh <projectRoot>
#
# Prerequisites: JDK 17+, kotlinc (the Kotlin compiler).
# If kotlinc is not on PATH, specify the executable path via the KOTLINC environment variable.
#
# If the source under src/ hasn't changed, reuses the previously compiled build/harness.jar as-is
# (kotlinc startup itself is slow, so recompiling every time would make repeated runs tedious).

set -uo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${1:-.}"
KOTLINC_BIN="${KOTLINC:-kotlinc}"
JAR="$HARNESS_DIR/build/harness.jar"

needs_build=1
if [ -f "$JAR" ]; then
  stale="$(find "$HARNESS_DIR/src" -name '*.kt' -newer "$JAR" 2>/dev/null | head -1)"
  [ -z "$stale" ] && needs_build=0
fi

if [ "$needs_build" -eq 1 ]; then
  mkdir -p "$HARNESS_DIR/build"
  "$KOTLINC_BIN" "$HARNESS_DIR/src" -include-runtime -d "$JAR"
fi

java -jar "$JAR" "$ROOT"
