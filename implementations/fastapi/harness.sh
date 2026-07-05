#!/usr/bin/env bash
# FastAPI Harness 실행 래퍼
#
# Usage: ./implementations/fastapi/harness.sh [projectRoot]
#
# 사전 조건: Python 3.10+

set -uo pipefail

ROOT="${1:-.}"
HARNESS_DIR="$(cd "$(dirname "$0")/harness" && pwd)"

python3 "$HARNESS_DIR/harness.py" "$ROOT"
