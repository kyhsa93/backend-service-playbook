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
  # @Entity, @Embeddable, @Enumerated 는 JPA 허용 — Spring 어노테이션만 금지
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

# ── summary ───────────────────────────────────────────────────
printf "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
if [ "$FAIL" -eq 0 ]; then
  printf "%d passed  PASS\n" "$PASS"
else
  printf "%d passed, %d failed  FAIL\n" "$PASS" "$FAIL"
  exit 1
fi
