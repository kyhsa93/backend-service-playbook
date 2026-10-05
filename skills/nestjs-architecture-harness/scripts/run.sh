#!/usr/bin/env bash
# Runs the bundled NestJS architecture harness against a project with the adopt profile.
# Usage: bash scripts/run.sh [projectRoot] [--only=a,b] [--out=report.json] [--profile=benchmark]
#
# Needs node >= 20 and the project's own dependencies installed: the harness parses code with
# the project's typescript, so nothing is downloaded and no code leaves the machine.

set -euo pipefail

ROOT="."
if [ $# -gt 0 ] && [[ "$1" != --* ]]; then
  ROOT="$1"
  shift
fi
ROOT="$(cd "$ROOT" && pwd)"
DIR="$(cd "$(dirname "$0")" && pwd)"

if [ ! -f "$ROOT/node_modules/typescript/package.json" ]; then
  echo "typescript not found in $ROOT/node_modules — run npm install (or pnpm/yarn install) in the project first" >&2
  exit 2
fi

NODE_PATH="$ROOT/node_modules" exec node "$DIR/harness.cjs" "$ROOT" \
  --profile=adopt \
  --doc-base=https://github.com/kyhsa93/backend-service-playbook/blob/main/implementations/nestjs/ \
  "$@"
