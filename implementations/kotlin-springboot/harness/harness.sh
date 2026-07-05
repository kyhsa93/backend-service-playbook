#!/usr/bin/env bash
# Kotlin Spring Boot Harness — Kotlin 프로젝트 구조·어노테이션 규칙 검사
# Usage: ./harness.sh <projectRoot>
# 설치 불필요. bash/zsh + grep/find 만 사용.

set -uo pipefail

ROOT="${1:-.}"
PASS=0
FAIL=0

pass()    { PASS=$((PASS+1)); printf "  PASS  %s\n" "$1"; }
fail()    { FAIL=$((FAIL+1)); printf "  FAIL  %s — %s\n" "$1" "$2"; }
section() { printf "\n[%s]\n" "$1"; }
skip()    { printf "  SKIP  %s\n" "$1"; }

KT_FILES=()
while IFS= read -r f; do
  KT_FILES+=("$f")
done < <(find "$ROOT" -name "*.kt" -not -path "*/test/*" -not -path "*/.git/*" -not -path "*/build/*" 2>/dev/null | sort)

# ── [1] 파일명 PascalCase 검사 ─────────────────────────────────
section "file-naming"

if [ ${#KT_FILES[@]} -eq 0 ]; then
  skip "Kotlin 파일 없음"
else
  for f in "${KT_FILES[@]}"; do
    name=$(basename "$f" .kt)
    rel="${f#"$ROOT/"}"
    if echo "$name" | grep -qE '^[A-Z][A-Za-z0-9]*$'; then
      pass "$rel"
    else
      fail "$rel" "파일명은 PascalCase.kt 여야 함"
    fi
  done
fi

# ── [2] @Repository — infrastructure/ 에만 허용 ───────────────
section "repository-annotation"

FOUND_REPO=0
for f in "${KT_FILES[@]}"; do
  if grep -q "@Repository" "$f" 2>/dev/null; then
    FOUND_REPO=1
    rel="${f#"$ROOT/"}"
    if echo "$f" | grep -q "/infrastructure/"; then
      pass "$rel (@Repository)"
    else
      fail "$rel" "@Repository는 infrastructure/ 패키지 안에 있어야 함"
    fi
  fi
done
[ "$FOUND_REPO" -eq 0 ] && skip "@Repository 없음"

# ── [3] @Service — application/ 에만 허용 ────────────────────
section "service-annotation"

FOUND_SVC=0
for f in "${KT_FILES[@]}"; do
  if grep -q "@Service" "$f" 2>/dev/null; then
    FOUND_SVC=1
    rel="${f#"$ROOT/"}"
    if echo "$f" | grep -q "/application/"; then
      pass "$rel (@Service)"
    else
      fail "$rel" "@Service는 application/ 패키지 안에 있어야 함"
    fi
  fi
done
[ "$FOUND_SVC" -eq 0 ] && skip "@Service 없음"

# ── [4] domain/ 순수성 — Spring 어노테이션 금지 ──────────────
section "domain-purity"

FOUND_DOMAIN=0
for f in "${KT_FILES[@]}"; do
  if ! echo "$f" | grep -q "/domain/"; then
    continue
  fi
  FOUND_DOMAIN=1
  rel="${f#"$ROOT/"}"
  if grep -qE "@Service|@Component|@Repository|@Controller|@RestController" "$f" 2>/dev/null; then
    fail "$rel" "domain/ 클래스에 Spring 어노테이션 사용 금지"
  else
    pass "$rel (domain 순수성)"
  fi
done
[ "$FOUND_DOMAIN" -eq 0 ] && skip "domain/ Kotlin 파일 없음"

# ── [5] @RestController — interfaces/ 에만 허용 ───────────────
section "controller-placement"

FOUND_CTRL=0
for f in "${KT_FILES[@]}"; do
  if grep -q "@RestController" "$f" 2>/dev/null; then
    FOUND_CTRL=1
    rel="${f#"$ROOT/"}"
    if echo "$f" | grep -q "/interfaces/"; then
      pass "$rel (@RestController)"
    else
      fail "$rel" "@RestController는 interfaces/ 패키지 안에 있어야 함"
    fi
  fi
done
[ "$FOUND_CTRL" -eq 0 ] && skip "@RestController 없음"

# ── [6] sealed class 에러 — domain/ 에 위치 ──────────────────
section "sealed-exception"

FOUND_SEALED=0
for f in "${KT_FILES[@]}"; do
  if grep -qE "sealed class.*Exception|sealed class.*Error" "$f" 2>/dev/null; then
    FOUND_SEALED=1
    rel="${f#"$ROOT/"}"
    if echo "$f" | grep -q "/domain/"; then
      pass "$rel (sealed exception)"
    else
      fail "$rel" "sealed 예외 계층은 domain/ 안에 있어야 함"
    fi
  fi
done
[ "$FOUND_SEALED" -eq 0 ] && skip "sealed 예외 없음"

# ── [7] 패키지 구조 검사 (4레이어 + CQRS) ────────────────────
section "package-structure"

DOMAIN_DIRS=()
while IFS= read -r d; do
  DOMAIN_DIRS+=("$d")
done < <(find "$ROOT" -type d -name "domain" -not -path "*/test/*" -not -path "*/.git/*" -not -path "*/build/*" 2>/dev/null | sort)

if [ ${#DOMAIN_DIRS[@]} -eq 0 ]; then
  skip "domain/ 디렉토리 없음"
else
  for domain_dir in "${DOMAIN_DIRS[@]}"; do
    parent=$(dirname "$domain_dir")
    rel_parent="${parent#"$ROOT/"}"
    for layer in application infrastructure interfaces; do
      if [ -d "$parent/$layer" ]; then
        pass "$rel_parent/$layer/"
      else
        fail "$rel_parent/$layer/" "디렉토리 없음"
      fi
    done
    for sub in command query; do
      if [ -d "$parent/application/$sub" ]; then
        pass "$rel_parent/application/$sub/"
      else
        fail "$rel_parent/application/$sub/" "CQRS 디렉토리 없음"
      fi
    done
  done
fi

# ── [8] shared-infra: outbox·task-queue ───────────────────────
section "shared-infra"

if find "$ROOT" -name "*Outbox*.kt" -not -path "*/outbox/*" -not -path "*/test/*" -not -path "*/build/*" 2>/dev/null | grep -q .; then
  if find "$ROOT" -type d -name "outbox" -not -path "*/test/*" -not -path "*/build/*" 2>/dev/null | grep -q .; then
    pass "outbox 패키지"
  else
    fail "outbox 패키지" "Outbox 파일이 있으나 outbox/ 패키지 없음"
  fi
else
  skip "outbox 패턴 없음"
fi

if find "$ROOT" -name "*TaskQueue*.kt" -not -path "*/test/*" -not -path "*/build/*" 2>/dev/null | grep -q .; then
  if find "$ROOT" -type d \( -name "task-queue" -o -name "taskqueue" \) -not -path "*/test/*" -not -path "*/build/*" 2>/dev/null | grep -q .; then
    pass "task-queue 패키지"
  else
    fail "task-queue 패키지" "TaskQueue 파일이 있으나 task-queue/ 패키지 없음"
  fi
else
  skip "task-queue 패턴 없음"
fi

# ── [9] event-placement ───────────────────────────────────────
section "event-placement"

FOUND_EVENT=0
for f in "${KT_FILES[@]}"; do
  name=$(basename "$f" .kt)
  rel="${f#"$ROOT/"}"
  case "$name" in
    *EventHandler)
      FOUND_EVENT=1
      if echo "$f" | grep -q "/application/event/"; then
        pass "$rel (EventHandler)"
      else
        fail "$rel" "EventHandler는 application/event/ 패키지 안에 있어야 함"
      fi
      ;;
    *IntegrationEvent)
      FOUND_EVENT=1
      if echo "$f" | grep -q "/application/integration-event/"; then
        pass "$rel (IntegrationEvent)"
      else
        fail "$rel" "IntegrationEvent는 application/integration-event/ 패키지 안에 있어야 함"
      fi
      ;;
  esac
done
[ "$FOUND_EVENT" -eq 0 ] && skip "이벤트 핸들러 없음"

# ── summary ───────────────────────────────────────────────────
printf "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
if [ "$FAIL" -eq 0 ]; then
  printf "%d passed  PASS\n" "$PASS"
else
  printf "%d passed, %d failed  FAIL\n" "$PASS" "$FAIL"
  exit 1
fi
