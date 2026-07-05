#!/usr/bin/env bash
# Backend Service Playbook — Base Harness
# 설치 불필요. bash/zsh가 있으면 실행된다.
#
# Usage: ./harness.sh [projectRoot]
#
# 이 스크립트는 언어 무관한 구조·배치 규칙만 검사한다.
# 구현체별 언어 하네스를 함께 실행해야 완전한 검증이 된다.
#
# 구현체별 추가 harness:
#   NestJS          : cd implementations/nestjs/harness && npm run evaluate -- <root>
#   Go              : cd implementations/go/harness && go run . <root>
#   Spring Boot     : bash implementations/java-springboot/harness/harness.sh <root>
#   Kotlin S.Boot   : bash implementations/kotlin-springboot/harness/harness.sh <root>
#   FastAPI         : python3 implementations/fastapi/harness/harness.py <root>

set -uo pipefail

ROOT="${1:-.}"
SRC="$ROOT/src"
PASS=0
FAIL=0

# ── helpers ──────────────────────────────────────────────────
pass()    { PASS=$((PASS+1)); printf "  PASS  %s\n" "$1"; }
fail()    { FAIL=$((FAIL+1)); printf "  FAIL  %s — %s\n" "$1" "$2"; }
section() { printf "\n[%s]\n" "$1"; }
skip()    { printf "  SKIP  %s\n" "$1"; }

is_shared_dir() {
  case "$1" in
    common|database|outbox|task-queue|config) return 0;;
    *) return 1;;
  esac
}

# 도메인 디렉토리 수집
DOMAINS=()
if [ -d "$SRC" ]; then
  while IFS= read -r d; do
    name=$(basename "$d")
    is_shared_dir "$name" || DOMAINS+=("$name")
  done < <(find "$SRC" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort)
fi

# ── [1] structure ─────────────────────────────────────────────
section "structure"

if [ ! -d "$SRC" ]; then
  fail "src/" "src/ 디렉토리가 없습니다"
elif [ ${#DOMAINS[@]} -eq 0 ]; then
  fail "domains" "src/ 아래에 도메인 디렉토리가 없습니다"
else
  for domain in "${DOMAINS[@]}"; do
    for layer in domain application interface infrastructure; do
      if [ -d "$SRC/$domain/$layer" ]; then
        pass "$domain/$layer/"
      else
        fail "$domain/$layer/" "디렉토리 없음"
      fi
    done
  done
fi

# ── [2] cqrs-pattern ──────────────────────────────────────────
section "cqrs-pattern"

if [ ${#DOMAINS[@]} -eq 0 ]; then
  skip "도메인 없음"
else
  for domain in "${DOMAINS[@]}"; do
    for dir in "application/command" "application/query"; do
      if [ -d "$SRC/$domain/$dir" ]; then
        pass "$domain/$dir/"
      else
        fail "$domain/$dir/" "디렉토리 없음"
      fi
    done
  done
fi

# ── [3] file-placement ────────────────────────────────────────
section "file-placement"

if [ -d "$SRC" ]; then
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    name=$(basename "$f")
    stem="${name%.*}"
    rel="${f#"$ROOT/"}"

    case "$stem" in
      *-repository-impl)
        case "$f" in
          */domain/*)         fail "$rel" "impl은 domain/ 에 있으면 안 됨" ;;
          */infrastructure/*) pass "$rel" ;;
          *)                  fail "$rel" "infrastructure/ 에 있어야 함" ;;
        esac
        ;;
      *-repository)
        case "$f" in
          */domain/*) pass "$rel" ;;
          *)          fail "$rel" "domain/ 에 있어야 함" ;;
        esac
        ;;
      *-command-service)
        case "$f" in
          */application/command/*) pass "$rel" ;;
          *)                       fail "$rel" "application/command/ 에 있어야 함" ;;
        esac
        ;;
      *-query-service)
        case "$f" in
          */application/query/*) pass "$rel" ;;
          *)                     fail "$rel" "application/query/ 에 있어야 함" ;;
        esac
        ;;
      *-task-controller)
        case "$f" in
          */interface/*) pass "$rel" ;;
          *)             fail "$rel" "interface/ 에 있어야 함" ;;
        esac
        ;;
      *-scheduler)
        case "$f" in
          */infrastructure/*) pass "$rel" ;;
          *)                  fail "$rel" "infrastructure/ 에 있어야 함" ;;
        esac
        ;;
      *-impl)
        case "$f" in
          */domain/*) fail "$rel" "impl 파일은 domain/ 에 있으면 안 됨" ;;
        esac
        ;;
    esac
  done < <(find "$SRC" -type f 2>/dev/null | sort)
fi

# ── [4] shared-infra ──────────────────────────────────────────
section "shared-infra"

if [ -d "$SRC" ]; then
  if find "$SRC" -name "*outbox*" -not -path "$SRC/outbox/*" 2>/dev/null | grep -q .; then
    if [ -d "$SRC/outbox" ]; then
      pass "src/outbox/"
    else
      fail "src/outbox/" "outbox 파일이 있으나 src/outbox/ 없음"
    fi
  else
    skip "outbox 패턴 없음"
  fi

  if find "$SRC" -name "*task*" -not -path "$SRC/task-queue/*" 2>/dev/null | grep -q .; then
    if [ -d "$SRC/task-queue" ]; then
      pass "src/task-queue/"
    else
      fail "src/task-queue/" "task 파일이 있으나 src/task-queue/ 없음"
    fi
  else
    skip "task 패턴 없음"
  fi
fi

# ── [5] event-placement ───────────────────────────────────────
section "event-placement"

if [ -d "$SRC" ]; then
  FOUND_EVENT=0
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    name=$(basename "$f")
    stem="${name%.*}"
    rel="${f#"$ROOT/"}"

    case "$stem" in
      *-handler)
        FOUND_EVENT=1
        case "$f" in
          */application/event/*) pass "$rel" ;;
          *)                     fail "$rel" "application/event/ 에 있어야 함" ;;
        esac
        ;;
      *-integration-event)
        FOUND_EVENT=1
        case "$f" in
          */application/integration-event/*) pass "$rel" ;;
          *)                                 fail "$rel" "application/integration-event/ 에 있어야 함" ;;
        esac
        ;;
    esac
  done < <(find "$SRC" -type f 2>/dev/null | sort)

  [ "$FOUND_EVENT" -eq 0 ] && skip "이벤트 핸들러 없음"
fi

# ── summary ───────────────────────────────────────────────────
printf "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
if [ "$FAIL" -eq 0 ]; then
  printf "%d passed  PASS\n" "$PASS"
else
  printf "%d passed, %d failed  FAIL\n" "$PASS" "$FAIL"
  exit 1
fi
