#!/usr/bin/env bash
# Kotlin Spring Boot Harness — Kotlin 프로젝트 구조·어노테이션 규칙 검사
# Usage: ./harness.sh <projectRoot>
#
# 사전 조건: JDK 17+, kotlinc(Kotlin 컴파일러).
# kotlinc이 PATH에 없으면 KOTLINC 환경변수로 실행 파일 경로를 지정할 수 있다.
#
# src/ 아래 소스가 바뀌지 않았으면 이전에 컴파일한 build/harness.jar를 그대로 재사용한다
# (kotlinc 기동 자체가 느려서 매번 재컴파일하면 반복 실행이 번거롭다).

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
