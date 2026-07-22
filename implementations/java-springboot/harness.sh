#!/usr/bin/env bash
# Spring Boot Harness execution wrapper
#
# Usage: ./implementations/springboot/harness.sh [projectRoot]

set -uo pipefail

ROOT="${1:-.}"
HARNESS_DIR="$(cd "$(dirname "$0")/harness" && pwd)"

bash "$HARNESS_DIR/harness.sh" "$ROOT"
