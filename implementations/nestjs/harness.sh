#!/usr/bin/env bash
# NestJS Harness — TypeScript evaluator 실행 래퍼
#
# Usage: ./implementations/nestjs/harness.sh [projectRoot]
#
# 사전 조건: node, npm (또는 npx)

set -uo pipefail

ROOT="${1:-.}"
if [ ! -d "$ROOT" ]; then
  echo "projectRoot not found: $ROOT" >&2
  exit 1
fi
ROOT="$(cd "$ROOT" && pwd)"
HARNESS_DIR="$(cd "$(dirname "$0")/harness" && pwd)"

if [ ! -d "$HARNESS_DIR/node_modules" ]; then
  printf "node_modules 없음 — npm install 실행 중...\n"
  (cd "$HARNESS_DIR" && npm install --silent)
fi

cd "$HARNESS_DIR"
npm run evaluate -- "$ROOT"
