#!/usr/bin/env bash
# Kotlin Spring Boot Harness 실행 래퍼
#
# Usage: ./implementations/kotlin-springboot/harness.sh [projectRoot]

set -uo pipefail

ROOT="${1:-.}"
HARNESS_DIR="$(cd "$(dirname "$0")/harness" && pwd)"

bash "$HARNESS_DIR/harness.sh" "$ROOT"
