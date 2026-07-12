# 디렉토리 구조 (Go)

원칙은 루트 [directory-structure.md](../../../../docs/architecture/directory-structure.md)를 따른다: 도메인 우선 4레이어 구조, 공용 인프라는 도메인 디렉토리 밖에 배치. root 문서는 NestJS 스타일의 `<domain>/domain|application|interface|infrastructure/` 중첩 구조를 예시로 들지만, Go는 `internal/` 아래에서 **레이어를 최상위, 도메인을 그 하위**에 두는 역방향 구조를 쓴다 — Go의 패키지 시스템(디렉토리 = 패키지)과 `internal/` 가시성 규칙 때문에 이 구조가 더 자연스럽다. 이 저장소의 `examples/`가 실제로 이 구조를 쓰고 있다.

---

## 실제 트리 (`examples/`)

```
cmd/
  server/
    main.go                                  ← 진입점, 의존성 조립

internal/
  domain/
    account/
      account.go                             ← Aggregate Root (New/Reconstitute + 도메인 메서드)
      account_status.go                      ← Status enum (Value Object 성격)
      money.go                               ← Money Value Object
      transaction.go                         ← Transaction Entity
      events.go                              ← DomainEvent 인터페이스 + 이벤트 구조체들
      errors.go                              ← sentinel error (var ErrXxx = errors.New(...))
      repository.go                          ← Repository interface + FindQuery
    card/                                    ← 두 번째 Bounded Context (cross-domain.md 참고)
      card.go                                ← Card Aggregate Root (IssueCard/Suspend/Cancel)
      card_status.go                         ← Status enum
      errors.go
      repository.go                          ← Repository/Query interface (CQRS 분리)

  application/
    command/
      create_account_handler.go
      deposit_handler.go
      withdraw_handler.go
      suspend_account_handler.go
      reactivate_account_handler.go
      close_account_handler.go
      event_relay.go                         ← OutboxRelay 포트 인터페이스 (command 패키지가 필요로 하는 최소 시그니처)
      account_adapter.go                     ← Card→Account 동기 조회 포트(ACL 인터페이스) + AccountView
      issue_card_handler.go                  ← 카드 발급 (AccountAdapter로 계좌 활성 여부 동기 확인)
      suspend_cards_by_account_handler.go    ← account.suspended.v1 반응 유스케이스 (멱등)
      cancel_cards_by_account_handler.go     ← account.closed.v1 반응 유스케이스 (멱등)
    query/
      get_account_handler.go
      get_transactions_handler.go
      get_card_handler.go
      result.go                              ← Account Result DTO들
      card_result.go                         ← Card Result DTO
    event/
      account_created_event_handler.go       ← Outbox가 드레인한 이벤트를 처리해 알림 발송 (domain-events.md 참고)
      money_deposited_event_handler.go
      money_withdrawn_event_handler.go
      account_suspended_event_handler.go     ← 알림 발송 + account.suspended.v1 Integration Event 적재
      account_reactivated_event_handler.go
      account_closed_event_handler.go        ← 알림 발송 + account.closed.v1 Integration Event 적재
      integration_publisher.go               ← IntegrationPublisher 포트 (event 패키지가 필요로 하는 최소 시그니처)
    integration-event/                       ← Account가 외부 BC에 공개하는 버전 명시 Integration Event 계약
      account_suspended_integration_event.go
      account_closed_integration_event.go

  infrastructure/
    persistence/
      account_repository.go                  ← account.Repository 구현체 (같은 트랜잭션에 Outbox 행도 적재)
      card_repository.go                     ← card.Repository 구현체
    acl/
      account_adapter.go                     ← command.AccountAdapter 구현체 (Card→Account ACL, cross-domain.md)
    notification/
      service.go                             ← 이벤트 핸들러가 호출하는 알림 발송 (SES + DB 기록)
      ses_client.go                          ← SES 클라이언트 생성
    outbox/                                  ← 도메인 무관 공유 인프라 (shared-modules.md 참고)
      writer.go                              ← Repository.Save 트랜잭션 안에서 Domain Event를 Outbox 행으로 적재
      publisher.go                           ← EventHandler가 Integration Event를 Outbox 행으로 적재
      relay.go                               ← Command Handler가 저장 직후 동기 호출해 드레인

  interface/
    http/
      router.go                              ← net/http 라우팅 + 의존성 조립 보조
      account_handler.go                      ← HTTP 핸들러
      card_handler.go                        ← Card HTTP 핸들러 (POST /cards, GET /cards/{cardId})
      dto.go                                  ← 요청/응답 DTO

migrations/
  0001_init.sql
  0002_add_email_and_sent_emails.sql
  0003_add_outbox.sql
  0004_add_card.sql

test/
  account_e2e_test.go
  notification_e2e_test.go
  card_e2e_test.go                           ← 동기 ACL + 비동기 Integration Event 반응 검증

localstack/
  init-ses.sh

docker-compose.yml
go.mod
```

> `internal/`은 Go 컴파일러가 강제하는 가시성 경계다 — `internal/`의 상위 디렉토리 바깥에서는 그 하위 패키지를 import할 수 없다. NestJS/Java의 `public`/`private` 접근 제어자와 달리 **패키지 단위**로만 캡슐화되므로, 도메인 내부 타입을 외부에 숨기고 싶다면 애초에 별도 패키지로 분리해야 한다([tactical-ddd.md](tactical-ddd.md)의 "캡슐화 한계" 참고).

---

## root 구조와의 대응

| root 개념 (NestJS 스타일) | Go 대응 |
|---|---|
| `<domain>/domain/` | `internal/domain/<domain>/` |
| `<domain>/application/command/` | `internal/application/command/` (여러 도메인이 생기면 `command/<domain>/`로 세분화 검토) |
| `<domain>/application/query/` | `internal/application/query/` |
| `<domain>/infrastructure/` | `internal/infrastructure/<concern>/` (persistence, notification 등 관심사별 하위 패키지) |
| `<domain>/interface/` | `internal/interface/http/` |
| `common/` | 필요 시 `internal/common/`(ID 생성 등 프레임워크 무관 순수 함수) — 현재 `examples/`에는 아직 없다([aggregate-id.md](aggregate-id.md) 참고) |
| `database/`(TransactionManager) | 없음 — 현재 `Save()` 내부 로컬 `db.BeginTx()`만 사용. root가 요구하는 컨텍스트 기반 전파는 미구현([persistence.md](persistence.md) 참고) |
| `outbox/` | `internal/infrastructure/outbox/` — `Writer`/`Relay` 구현됨([domain-events.md](domain-events.md) 참고) |
| `task-queue/` | 없음 — 스케줄링/Task Queue 예제 없음([scheduling.md](scheduling.md) 참고) |
| `config/` | 없음 — `main.go`가 `os.Getenv`를 검증 없이 직접 사용. 새로 만들 때는 `internal/config/`로 분리([config.md](config.md) 참고) |

여러 도메인이 추가되면 `internal/domain/<domain>/`, `internal/infrastructure/persistence/<domain>_repository.go`처럼 도메인별로 파일이 늘어난다. 현재 `examples/`에는 Account와 Card 두 Bounded Context가 있고(파일명 접두사 `card_*`로 구분), `internal/application/command/`·`query/`는 아직 평평한 구조로 두 도메인의 핸들러를 함께 담는다 — 도메인이 더 많아져 파일이 번잡해지면 `command/<domain>/`처럼 하위 디렉토리로 나누는 것을 검토한다. Account↔Card 크로스 도메인 호출 배치는 [cross-domain.md](cross-domain.md)를 참고한다.

---

## 파일·패키지·타입 네이밍

| 대상 | 규칙 | 예시 |
|------|------|------|
| 파일명 | `snake_case.go` | `account_repository.go`, `get_transactions_handler.go` |
| 패키지명 | 소문자 단일 단어(언더스코어 없음) | `package account`, `package persistence` |
| 타입명 | `PascalCase` | `Account`, `AccountRepository` |
| 공개 함수/메서드 | `PascalCase` | `New`, `Deposit`, `FindByID` |
| 비공개 함수/메서드 | `camelCase` | `newTransaction`, `describe` |
| 에러 | `ErrXxx` | `ErrNotFound`, `ErrInsufficientBalance` |
| 인터페이스 | 명사(동사+er 지양, 역할 이름 우선) | `Repository`, `OutboxRelay`, `SESClient` |

패키지명은 디렉토리명과 일치시킨다(`internal/domain/account/` → `package account`). 여러 단어로 된 개념은 디렉토리를 중첩해 분리한다(`application/command/` → `package command`) — Go 컨벤션상 패키지명에 언더스코어나 캐멀케이스를 쓰지 않는다.

---

## 공용 인프라를 아직 추가하지 않은 이유

root의 `common/`, `database/`, `outbox/`, `task-queue/`, `config/` 디렉토리는 각각 대응하는 패턴(ID 유틸, 트랜잭션 전파, Outbox, Task Queue, 설정 검증)이 실제로 필요해질 때 만드는 것이 Go 컨벤션에 맞다 — 미리 빈 추상화를 만들어두지 않는다. `outbox/`는 실제로 필요해져 이미 추가되었다(알림 발송이 유실되면 안 되는 부가효과였기 때문 — [domain-events.md](domain-events.md) 참고). 나머지(`common/`, `database/`, `task-queue/`, `config/`)는 아직 그 필요가 생기지 않아 없다. 각 패턴을 실제로 추가할 때 참고할 문서를 위 표에 명시했다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향과 역할 상세
- [repository-pattern.md](repository-pattern.md) — Repository 배치 규칙
- [tactical-ddd.md](tactical-ddd.md) — domain 패키지 내부 설계, 캡슐화 한계
- [config.md](config.md) — `internal/config/` 신설 시 패턴
