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
# outbox 트리거는 "OutboxRelay를 실제로 참조하는 코드가 있는가"로 판단한다 — 이전에는
# "*Outbox*.kt 파일이 outbox/ 밖에 있는가"로 판단했는데, 실제 Outbox 파일들이 이미
# 전부 outbox/ 안에 있어서 이 조건이 항상 거짓이 되어 SKIP만 하고 outbox 패키지를
# 실질적으로 검증한 적이 없었다.
section "shared-infra"

USES_OUTBOX_RELAY=0
for f in "${KT_FILES[@]}"; do
  if grep -q "OutboxRelay" "$f" 2>/dev/null; then
    USES_OUTBOX_RELAY=1
    break
  fi
done

if [ "$USES_OUTBOX_RELAY" -eq 1 ]; then
  OUTBOX_DIRS=()
  while IFS= read -r d; do
    OUTBOX_DIRS+=("$d")
  done < <(find "$ROOT" -type d -name "outbox" -not -path "*/test/*" -not -path "*/build/*" 2>/dev/null)

  if [ ${#OUTBOX_DIRS[@]} -eq 0 ]; then
    fail "outbox 패키지" "OutboxRelay를 참조하지만 outbox/ 패키지가 없음"
  else
    HAS_WRITER=0
    HAS_RELAY=0
    for d in "${OUTBOX_DIRS[@]}"; do
      [ -f "$d/OutboxWriter.kt" ] && HAS_WRITER=1
      [ -f "$d/OutboxRelay.kt" ] && HAS_RELAY=1
    done
    if [ "$HAS_WRITER" -eq 1 ] && [ "$HAS_RELAY" -eq 1 ]; then
      pass "outbox 패키지 (OutboxWriter/OutboxRelay 구현 확인)"
    else
      fail "outbox 패키지" "outbox/ 패키지는 있으나 OutboxWriter.kt/OutboxRelay.kt를 찾을 수 없음"
    fi
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
    *)
      # 파일명 접미사(EventHandler/IntegrationEvent)와 무관하게, Spring의
      # ApplicationEventPublisher 기반 동기 인프로세스 이벤트 처리를 나타내는
      # @EventListener 애노테이션이 있으면 이벤트 핸들링 코드로 간주한다.
      # (가이드의 domainEvents/pullDomainEvents() 발행 메커니즘을 실제로 소비하는 방식)
      if grep -q "@EventListener" "$f" 2>/dev/null; then
        FOUND_EVENT=1
        if echo "$f" | grep -q "/application/event/"; then
          pass "$rel (@EventListener)"
        else
          fail "$rel" "@EventListener 사용 클래스는 application/event/ 패키지 안에 있어야 함"
        fi
      fi
      ;;
  esac
done
[ "$FOUND_EVENT" -eq 0 ] && skip "이벤트 핸들러 없음"

# ── [10] Command Service는 Outbox 경유만 허용 — ApplicationEventPublisher/
#         @EventListener/publishEvent() 직접 사용 금지 (domain-events.md) ────
section "no-event-publisher-in-command"

FOUND_CMD=0
for f in "${KT_FILES[@]}"; do
  if ! echo "$f" | grep -q "/application/command/"; then
    continue
  fi
  FOUND_CMD=1
  rel="${f#"$ROOT/"}"
  if grep -qE "ApplicationEventPublisher|@EventListener|\.publishEvent\(" "$f" 2>/dev/null; then
    fail "$rel" "Command Service는 ApplicationEventPublisher/@EventListener/publishEvent()를 쓰지 않아야 함 — Outbox 경유(domain-events.md)"
  else
    pass "$rel (Outbox 경유 확인)"
  fi
done
[ "$FOUND_CMD" -eq 0 ] && skip "Command Service 없음"

# ── [11] 트랜잭션 경계 — Command Service에는 없고 Repository.save()에 있어야 함 ──
section "transaction-boundary"

FOUND_TXN=0
for f in "${KT_FILES[@]}"; do
  if ! echo "$f" | grep -q "/application/command/"; then
    continue
  fi
  FOUND_TXN=1
  rel="${f#"$ROOT/"}"
  if grep -q "@Transactional" "$f" 2>/dev/null; then
    fail "$rel" "Command Service에 @Transactional이 있으면 안 됨 — 트랜잭션 경계는 Repository.save()로 이관됨(domain-events.md, persistence.md)"
  else
    pass "$rel (트랜잭션 경계 미보유 확인)"
  fi
done

for f in "${KT_FILES[@]}"; do
  name=$(basename "$f" .kt)
  case "$name" in
    *RepositoryImpl)
      if grep -q "Outbox" "$f" 2>/dev/null; then
        FOUND_TXN=1
        rel="${f#"$ROOT/"}"
        if grep -q "@Transactional" "$f" 2>/dev/null; then
          pass "$rel (Repository.save() 트랜잭션 경계 확인)"
        else
          fail "$rel" "Outbox를 저장하는 Repository 구현체에 @Transactional이 없음 — Aggregate 저장과 Outbox 적재가 원자적이지 않을 수 있음"
        fi
      fi
      ;;
  esac
done
[ "$FOUND_TXN" -eq 0 ] && skip "Command Service/Outbox 연동 Repository 구현체 없음"

# ── [12] Outbox 드레인 순서 — save() 호출 뒤에 processPending() 호출 (domain-events.md) ──
section "outbox-drain-order"

FOUND_ORDER=0
for f in "${KT_FILES[@]}"; do
  if ! echo "$f" | grep -q "/application/command/"; then
    continue
  fi
  if ! grep -q "OutboxRelay" "$f" 2>/dev/null; then
    continue
  fi
  FOUND_ORDER=1
  rel="${f#"$ROOT/"}"
  save_line=$(grep -n "\.save(" "$f" | head -1 | cut -d: -f1)
  pp_line=$(grep -n "\.processPending(" "$f" | head -1 | cut -d: -f1)
  if [ -z "$save_line" ]; then
    fail "$rel" "OutboxRelay를 참조하지만 save(...) 호출을 찾을 수 없음"
  elif [ -z "$pp_line" ]; then
    fail "$rel" "OutboxRelay를 참조하지만 processPending() 호출이 없음 — 저장 직후 Outbox 드레인 누락(domain-events.md)"
  elif [ "$pp_line" -lt "$save_line" ]; then
    fail "$rel" "processPending() 호출이 save(...) 호출보다 먼저 등장함 — 커밋 이후 드레인 순서 위반"
  else
    pass "$rel (save → processPending 순서 확인)"
  fi
done
[ "$FOUND_ORDER" -eq 0 ] && skip "OutboxRelay를 사용하는 Command Service 없음"

# ── [13] NotificationE2ETest 존재 확인 ────────────────────────
# harness의 파일 스캔은 test/ 디렉토리 전체를 제외하므로(KT_FILES 정의 참고), 이 회귀
# 테스트가 통째로 삭제되어도 다른 어떤 규칙도 이를 알아채지 못한다. Outbox 경로 전체를
# 검증하는 유일한 e2e 테스트이므로 최소한 존재 여부만은 별도로 확인한다.
section "notification-e2e-test"

if find "$ROOT" -path "*/test/*" -name "NotificationE2ETest.kt" 2>/dev/null | grep -q .; then
  pass "src/test/.../notification/NotificationE2ETest.kt"
else
  fail "notification/NotificationE2ETest.kt" "Outbox 알림 발송 경로를 검증하는 e2e 테스트가 없음"
fi

# ── summary ───────────────────────────────────────────────────
printf "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
if [ "$FAIL" -eq 0 ]; then
  printf "%d passed  PASS\n" "$PASS"
else
  printf "%d passed, %d failed  FAIL\n" "$PASS" "$FAIL"
  exit 1
fi
