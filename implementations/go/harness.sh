#!/usr/bin/env bash
# Go Harness execution wrapper
#
# Usage: ./implementations/go/harness.sh [projectRoot]
#
# Prerequisite: Go 1.22+

set -uo pipefail

ROOT="${1:-.}"
if [ ! -d "$ROOT" ]; then
  echo "projectRoot not found: $ROOT" >&2
  exit 1
fi
ROOT="$(cd "$ROOT" && pwd)"
HARNESS_DIR="$(cd "$(dirname "$0")/harness" && pwd)"

cd "$HARNESS_DIR"
go run . "$ROOT"
