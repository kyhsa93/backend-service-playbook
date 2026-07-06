# 디렉토리 구조

> 프레임워크 무관 원칙: [../../../../docs/architecture/directory-structure.md](../../../../docs/architecture/directory-structure.md)

이 저장소의 실제 구현(`examples/src/account/`)을 기준으로 한 FastAPI 패키지 구조다.

## 전체 구조

```
implementations/fastapi/examples/
  main.py                              ← FastAPI 앱 생성, lifespan, 라우터 등록, exception_handler
  requirements.txt
  pytest.ini
  docker-compose.yml                   ← 로컬 인프라 (Postgres + LocalStack)
  localstack/
    init-ses.sh                        ← LocalStack SES 초기화 스크립트
  src/
    database.py                        ← 엔진/세션 팩토리, get_session() 의존성

    account/                           ← Bounded Context (도메인) 단위 패키지
      domain/                          ← 프레임워크 무의존
        account.py                     ← Aggregate Root
        transaction.py                 ← Entity (frozen dataclass, 하위 개체)
        money.py                       ← Value Object (frozen dataclass)
        account_status.py              ← 도메인 enum
        events.py                      ← Domain Event (frozen dataclass 모음)
        errors.py                      ← 도메인 예외 계층 (AccountError 및 하위 클래스)
        repository.py                  ← AccountRepository ABC

      application/
        command/
          create_account_handler.py    ← CreateAccountCommand + CreateAccountHandler
          deposit_handler.py
          withdraw_handler.py
          suspend_account_handler.py
          reactivate_account_handler.py
          close_account_handler.py
        query/
          get_account_handler.py       ← GetAccountQuery + GetAccountHandler
          get_transactions_handler.py
          result.py                    ← GetAccountResult, GetTransactionsResult 등 응답 DTO
        service/
          notification_service.py      ← NotificationService ABC (Technical Service 인터페이스)

      infrastructure/
        persistence/
          account_repository.py        ← Base(DeclarativeBase), AccountModel, TransactionModel,
                                          SqlAlchemyAccountRepository(AccountRepository)
        notification/
          notification_service.py      ← SesNotificationService(NotificationService) — aioboto3 SES 구현
          sent_email_model.py           ← SentEmailModel (발송 이력 테이블)

      interface/
        rest/
          account_router.py             ← APIRouter, Depends 조립, Handler 호출
          schemas.py                    ← Pydantic 요청/응답 모델

  tests/
    test_account_e2e.py                ← E2E (testcontainers Postgres)
    test_notification_e2e.py           ← E2E (testcontainers Postgres + LocalStack SES)
```

---

## Technical Service 분리 예시 — `notification_service`

이 저장소에서 [domain-service.md](../../../../docs/architecture/domain-service.md)의 **Technical Service 패턴**(Application에 인터페이스, Infrastructure에 구현체)이 실제로 적용된 유일한 예다. 새로운 기술 인프라 관심사(파일 스토리지, Secrets Manager 등)를 추가할 때 이 구조를 그대로 따른다.

```python
# application/service/notification_service.py — 인터페이스 (ABC)
from abc import ABC, abstractmethod

from ...domain.account import AccountDomainEvent


class NotificationService(ABC):
    @abstractmethod
    async def notify(self, event: AccountDomainEvent) -> None: ...
```

```python
# infrastructure/notification/notification_service.py — 구현체 (SES + aioboto3)
class SesNotificationService(NotificationService):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session
        self._boto_session = aioboto3.Session()

    async def notify(self, event: AccountDomainEvent) -> None:
        ...
```

`application/command/deposit_handler.py`는 구체 클래스(`SesNotificationService`)가 아니라 인터페이스(`NotificationService`)를 생성자에서 받는다. `interface/rest/account_router.py`의 `_notification_service()` 팩토리가 `Depends`로 실제 구현체를 바인딩한다 — DI 컨테이너가 없는 FastAPI에서는 이 팩토리 함수 자체가 "바인딩 지점" 역할을 한다.

---

## 레이어별 원칙

### domain/

- **프레임워크 무의존**: `fastapi`, `sqlalchemy`, `aioboto3` 등 어떤 외부 라이브러리도 import하지 않는다. 표준 라이브러리(`dataclasses`, `abc`, `datetime`, `enum`)만 사용한다.
- **비즈니스 규칙 캡슐화**: `Account`의 `deposit()`/`withdraw()`/`close()` 등 메서드 내부에서만 상태를 변경하고 불변식을 검증한다.
- Repository는 **ABC만** 여기에 둔다 (`repository.py`). 구현체는 `infrastructure/`.

### application/

- 유스케이스 **조율자**. `command/`와 `query/`의 Handler는 비즈니스 로직을 직접 수행하지 않고 Aggregate에 위임한다.
- `service/`: 기술 인프라 인터페이스(ABC) — 현재 `notification_service.py` 하나가 있다. 파일 스토리지, Secrets Manager 등을 추가하면 같은 디렉토리에 인터페이스를 늘린다.

### interface/rest/

- 외부 진입점. `account_router.py`가 HTTP 요청을 Command/Query로 변환해 Handler에 위임한다.
- `schemas.py`의 Pydantic 모델은 요청/응답 스키마 전용이며, `application/query/result.py`의 Result 객체를 감싸는 얇은 변환만 한다.

### infrastructure/

- 외부 시스템에 실제로 접근하는 유일한 레이어. `persistence/`는 SQLAlchemy, `notification/`은 aioboto3(SES)를 직접 사용한다.
- `domain/repository.py`, `application/service/notification_service.py`의 ABC 구현체가 모두 여기 있다.

---

## 파일명·모듈 네이밍

| 대상 | 규칙 | 예시 |
|------|------|------|
| 파일명·모듈명 | `snake_case.py` | `account_repository.py` |
| 패키지(디렉토리)명 | 소문자 | `domain`, `persistence`, `notification` |
| 클래스명 | `PascalCase` | `Account`, `AccountRepository`, `SesNotificationService` |
| 함수·변수 | `snake_case` | `create_account`, `pull_events` |
| 상수 | `UPPER_SNAKE_CASE` | `DEFAULT_SENDER_EMAIL` |
| 예외 클래스 | `PascalCase` + `Error` | `AccountNotFoundError` |
| ABC (인터페이스) | 구현 기술을 이름에 넣지 않음 | `AccountRepository`, `NotificationService` |
| ABC 구현체 | 구현 기술을 접두사로 | `SqlAlchemyAccountRepository`, `SesNotificationService` |

---

## 클래스 네이밍 규칙

| 종류 | 규칙 | 예시 |
|------|------|------|
| Aggregate Root | 도메인 명사 (PascalCase) | `Account` |
| Entity (하위 개체) | 도메인 명사 | `Transaction` |
| Value Object | 도메인 개념 | `Money` |
| Domain Event | 과거형 PascalCase | `AccountCreated`, `MoneyDeposited` |
| Repository 인터페이스 | `<Aggregate>Repository` | `AccountRepository` |
| Repository 구현체 | `SqlAlchemy<Aggregate>Repository` | `SqlAlchemyAccountRepository` |
| Command | `<Verb><Noun>Command` | `DepositCommand` |
| CommandHandler | `<Verb><Noun>Handler` | `DepositHandler` |
| Query | `<Verb><Noun>Query` | `GetAccountQuery` |
| Result | `<Verb><Noun>Result`/`<Noun>Result` | `GetAccountResult`, `MoneyResult` |
| Technical Service 인터페이스 | `<Concern>Service` | `NotificationService` |
| Technical Service 구현체 | `<Provider><Concern>Service` | `SesNotificationService` |

---

## 공용 인프라 배치 기준

현재 저장소에는 아직 `common/`, `config/` 등 공용 디렉토리가 없다 — 도메인이 `account` 하나뿐이기 때문이다. 두 번째 도메인을 추가하거나 [aggregate-id.md](aggregate-id.md)/[config.md](config.md)의 유틸을 도입할 때는 아래 위치를 따른다.

| 디렉토리 | 포함 내용 |
|---------|----------|
| `src/common/` | 순수 유틸 함수 — `generate_id.py`([aggregate-id.md](aggregate-id.md) 참조) 등 |
| `src/config/` | 관심사별 설정 클래스, fail-fast 검증 ([config.md](config.md) 참조) |
| `src/database.py` | DB 엔진/세션 팩토리 — 현재 위치 유지 (도메인이 하나뿐인 동안은 모듈 파일로 충분) |

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향과 역할 상세
- [repository-pattern.md](repository-pattern.md) — Repository 패턴 상세
- [domain-events.md](domain-events.md) — Domain Event, Outbox 구조
- [config.md](config.md) — 환경 설정 관리
