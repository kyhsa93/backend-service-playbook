#!/usr/bin/env bash
# Spring Boot Harness — Java 프로젝트 구조·어노테이션 규칙 검사
# Usage: ./harness.sh <projectRoot>
#
# 사전 조건: JDK 17+ (javac/java). PATH에 없으면 JAVA_HOME/bin을 사용한다.
#
# src/ 아래 소스가 바뀌지 않았으면 이전에 컴파일한 build/classes를 그대로 재사용한다.

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
