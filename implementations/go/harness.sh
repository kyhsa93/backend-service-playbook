#!/usr/bin/env bash
# Go Harness 실행 래퍼
#
# Usage: ./implementations/go/harness.sh [projectRoot]
#
# 사전 조건: Go 1.22+

set -uo pipefail

ROOT="${1:-.}"
HARNESS_DIR="$(cd "$(dirname "$0")/harness" && pwd)"

cd "$HARNESS_DIR"
go run . "$ROOT"
