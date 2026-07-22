#!/usr/bin/env bash
# Spring Boot Harness — checks Java project structure/annotation rules
# Usage: ./harness.sh <projectRoot>
#
# Prerequisite: JDK 17+ (javac/java). Uses JAVA_HOME/bin if not on PATH.
#
# If the source under src/ hasn't changed, it reuses the previously compiled build/classes as-is.

set -uo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${1:-.}"
JAVAC_BIN="${JAVAC:-javac}"
JAVA_BIN="${JAVA:-java}"
CLASSES="$HARNESS_DIR/build/classes"
STAMP="$CLASSES/.stamp"

needs_build=1
if [ -f "$STAMP" ]; then
  stale="$(find "$HARNESS_DIR/src" -name '*.java' -newer "$STAMP" 2>/dev/null | head -1)"
  [ -z "$stale" ] && needs_build=0
fi

if [ "$needs_build" -eq 1 ]; then
  mkdir -p "$CLASSES"
  "$JAVAC_BIN" -d "$CLASSES" $(find "$HARNESS_DIR/src" -name "*.java")
  touch "$STAMP"
fi

"$JAVA_BIN" -cp "$CLASSES" harness.Main "$ROOT"
