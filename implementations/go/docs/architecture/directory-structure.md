# 디렉토리 구조 (Go)

원칙은 루트 [directory-structure.md](../../../../docs/architecture/directory-structure.md)를 따른다: 도메인 우선 4레이어 구조, 공용 인프라는 도메인 디렉토리 밖에 배치. root 문서는 NestJS 스타일의 `<domain>/domain|application|interface|infrastructure/` 중첩 구조를 예시로 들지만, Go는 `internal/` 아래에서 **레이어를 최상위, 도메인을 그 하위**에 두는 역방향 구조를 쓴다 — Go의 패키지 시스템(디렉토리 = 패키지)과 `internal/` 가시성 규칙 때문에 이 구조가 더 자연스럽다. 이 저장소의 `examples/`가 실제로 이 구조를 쓰고 있다.

---

## 실제 트리 (`examples/`)

```
cmd/
  server/
    main.go                                  ← 진입점, 의존성 조립

internal/
  common/                                    ← 도메인 무관 공유 순수 유틸 (shared-modules.md 참고)
    id.go                                    ← common.NewID() — aggregate-id.md

  config/                                    ← 관심사별 설정 로딩/검증 (config.md 참고)
    database.go
    jwt.go
    rate_limit.go
    secret_service.go

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
    credential/                              ← 인증/가입 Aggregate (authentication.md 참고)
      credential.go                          ← userId + bcrypt 해시
      errors.go
      repository.go

  application/
    command/
      create_account_handler.go
      deposit_handler.go
      withdraw_handler.go
      suspend_account_handler.go
      reactivate_account_handler.go
      close_account_handler.go
      account_adapter.go                     ← Card→Account 동기 조회 포트(ACL 인터페이스) + AccountView
      issue_card_handler.go                  ← 카드 발급 (AccountAdapter로 계좌 활성 여부 동기 확인)
      suspend_cards_by_account_handler.go    ← account.suspended.v1 반응 유스케이스 (멱등)
      cancel_cards_by_account_handler.go     ← account.closed.v1 반응 유스케이스 (멱등)
      sign_in_handler.go                     ← 저장된 해시와 비교 후 토큰 발급 (authentication.md 참고)
      sign_up_handler.go                     ← 중복 확인 → 해싱 → 저장
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
      credential_repository.go               ← credential.Repository 구현체
    acl/
      account_adapter.go                     ← command.AccountAdapter 구현체 (Card→Account ACL, cross-domain.md)
    auth/                                    ← 인증 Technical Service 구현체 (authentication.md 참고)
      bcrypt_password_hasher.go
      jwt_service.go
    secret/                                  ← Secrets Manager 접근 구현체 (secret-manager.md 참고)
      service.go
    notification/
      service.go                             ← 이벤트 핸들러가 호출하는 알림 발송 (SES + DB 기록)
      ses_client.go                          ← SES 클라이언트 생성
    outbox/                                  ← 도메인 무관 공유 인프라 (shared-modules.md 참고)
      writer.go                              ← Repository.Save 트랜잭션 안에서 Domain Event를 Outbox 행으로 적재
      publisher.go                           ← EventHandler가 Integration Event를 Outbox 행으로 적재
      sqs_client.go                          ← Poller/Consumer가 공유하는 SQS 클라이언트 생성
      poller.go                              ← Outbox 미처리 행을 주기적으로 읽어 SQS로 발행
      consumer.go                            ← SQS 수신 → event_type별 Handler 실행

  interface/
    http/
      router.go                              ← net/http 라우팅 + 의존성 조립 보조
      account_handler.go                      ← HTTP 핸들러
      card_handler.go                        ← Card HTTP 핸들러 (POST /cards, GET /cards/{cardId})
      auth_handler.go                        ← POST /auth/sign-in, POST /auth/sign-up
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
| `common/` | `internal/common/`(`id.go` — ID 생성 등 프레임워크 무관 순수 함수)([aggregate-id.md](aggregate-id.md) 참고) |
| `database/`(TransactionManager) | `internal/infrastructure/database/`(`WithTx`/`TxFromContext`/`QuerierFrom`/`Manager`) — 계좌 간 송금(Transfer)이 여러 Repository 저장을 하나의 트랜잭션으로 묶어야 하는 첫 실사용처였다([persistence.md](persistence.md) 참고) |
| `outbox/` | `internal/infrastructure/outbox/` — `Writer`/`Poller`/`Consumer` 구현됨([domain-events.md](domain-events.md) 참고) |
| `task-queue/` | `internal/infrastructure/task-queue/`(`Writer`/`Poller`/`Consumer`) — 정기 이자 지급/카드 명세서 발송 배치가 실사용처다([scheduling.md](scheduling.md) 참고) |
| `config/` | `internal/config/`(`database.go`/`jwt.go`/`rate_limit.go`/`secret_service.go`)([config.md](config.md) 참고) |

여러 도메인이 추가되면 `internal/domain/<domain>/`, `internal/infrastructure/persistence/<domain>_repository.go`처럼 도메인별로 파일이 늘어난다. 현재 `examples/`에는 Account와 Card 두 Bounded Context가 있고(파일명 접두사 `card_*`로 구분), `internal/application/command/`·`query/`는 아직 평평한 구조로 두 도메인의 핸들러를 함께 담는다 — 도메인이 더 많아져 파일이 번잡해지면 `command/<domain>/`처럼 하위 디렉토리로 나누는 것을 검토한다. Account↔Card 크로스 도메인 호출 배치는 [cross-domain.md](cross-domain.md)를 참고한다.

---

## 파일·패키지·타입 네이밍

| 대상 | 규칙 | 예시 |
|------|------|------|
| 파일명 | `snake_case.go` | `account_repository.go`, `get_transactions_handler.go` |
| 패키지명 | 소문자 단일 단어(언더스코어 없음) | `package account`, `package persistence` |
| 타입명 | `PascalCase` | `Account`, `AccountRepository` |
| 공개 함수/메서드 | `PascalCase` | `New`, `Deposit`, `FindAccounts` |
| 비공개 함수/메서드 | `camelCase` | `newTransaction`, `describe` |
| 에러 | `ErrXxx` | `ErrNotFound`, `ErrInsufficientBalance` |
| 인터페이스 | 명사(동사+er 지양, 역할 이름 우선) | `Repository`, `AccountAdapter`, `SESClient` |

패키지명은 디렉토리명과 일치시킨다(`internal/domain/account/` → `package account`). 여러 단어로 된 개념은 디렉토리를 중첩해 분리한다(`application/command/` → `package command`) — Go 컨벤션상 패키지명에 언더스코어나 캐멀케이스를 쓰지 않는다.

---

## 공용 인프라는 실제로 필요해질 때만 추가한다

root의 `common/`, `database/`, `outbox/`, `task-queue/`, `config/` 디렉토리는 각각 대응하는 패턴(ID 유틸, 트랜잭션 전파, Outbox, Task Queue, 설정 검증)이 실제로 필요해질 때 만드는 것이 Go 컨벤션에 맞다 — 미리 빈 추상화를 만들어두지 않는다(YAGNI). 다섯 모두 지금은 실제로 존재한다 — 각각 그 필요를 만든 실사용처가 생겼을 때 추가됐다: `outbox/`는 알림 발송이 유실되면 안 되는 부가효과 때문에, `task-queue/`는 정기 이자 지급/카드 명세서 발송 배치 때문에, `database/`는 계좌 간 송금이 두 Account 저장을 하나의 트랜잭션으로 묶어야 했기 때문이다. 이 순서 자체가 이 저장소의 YAGNI 원칙이 실제로 어떻게 지켜지는지 보여주는 기록이다 — 다음에 새로운 공용 인프라가 필요해지면 같은 방식(실사용처가 생긴 뒤에 추가)을 따른다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향과 역할 상세
- [repository-pattern.md](repository-pattern.md) — Repository 배치 규칙
- [tactical-ddd.md](tactical-ddd.md) — domain 패키지 내부 설계, 캡슐화 한계
- [config.md](config.md) — `internal/config/` 신설 시 패턴
