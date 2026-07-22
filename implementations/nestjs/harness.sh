#!/usr/bin/env bash
# NestJS Harness — a wrapper for running the TypeScript evaluators
#
# Usage: ./implementations/nestjs/harness.sh [projectRoot]
#
# Prerequisites: node, npm (or npx)

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
