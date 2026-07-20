# 핵심 설계 원칙 요약

이 저장소의 다른 21개 문서에서 이미 다루는 원칙 중, FastAPI 구현에서 가장 자주 되짚어야 할 것들을 조합 순서(구조 → 레이어 → 에러/DI → ID/이벤트/DTO/비동기)로 압축했다. 각 항목은 상세 문서로 연결된다 — 상충하는 규칙을 새로 만들지 않고 기존 문서의 요약본 역할만 한다.

1. **도메인 우선 디렉토리 구조** — `src/<domain>/` 하위에 `domain/application/interface/infrastructure` 4개 레이어를 배치한다. 기술 레이어가 아니라 Bounded Context가 패키지 분리 기준이다. ([directory-structure.md](directory-structure.md))

2. **Domain 레이어는 프레임워크 무의존** — `fastapi`, `sqlalchemy`, `aioboto3` 등 어떤 외부 라이브러리도 import하지 않는다. `dataclasses`/`abc`/`datetime`/`enum` 같은 표준 라이브러리만 사용한다. ([tactical-ddd.md](tactical-ddd.md), [layer-architecture.md](layer-architecture.md))

3. **변경 가능한 상태는 일반 클래스, 불변 값은 `frozen dataclass`** — Aggregate Root(`Account`)는 `__init__` + 도메인 메서드를 가진 일반 클래스, Entity(`Transaction`)·Value Object(`Money`)·Domain Event는 `@dataclass(frozen=True)`. ([tactical-ddd.md](tactical-ddd.md))

4. **비즈니스 규칙은 Aggregate 메서드에 캡슐화, Handler는 조율만** — `deposit()`/`withdraw()` 등 도메인 메서드 진입 시 불변식을 즉시 검증한다. Application Handler는 Repository 조회 → Aggregate 메서드 호출 → 저장만 수행한다. ([layer-architecture.md](layer-architecture.md))

5. **Repository는 Aggregate 단위, ABC는 `domain/`에 · 구현체는 `infrastructure/`에** — 하위 Entity(`Transaction`)는 별도 Repository를 갖지 않고 Aggregate Root의 Repository를 통해서만 조회/저장된다. ([repository-pattern.md](repository-pattern.md))

6. **DI는 `Depends` 팩토리 함수 — 전용 컨테이너 없음** — Repository/Technical Service/Adapter의 ABC ↔ 구현체 바인딩은 `interface/rest/*_router.py`의 팩토리 함수(`_repo`, `_notification_service`)가 담당한다. NestJS의 `{ provide, useClass }`에 대응하는 유일한 지점이다. ([module-pattern.md](module-pattern.md))

7. **Command/Query Handler는 `XxxHandler` + `async def execute()`** — Command와 Query를 `application/command/`, `application/query/`로 물리적으로 분리한다. CommandBus/QueryBus 도입은 선택 사항이며 이 저장소는 아직 도입하지 않았다. ([cqrs-pattern.md](cqrs-pattern.md))

8. **Technical Service는 인터페이스-구현체 분리** — 이메일 발송(`NotificationService`) 같은 기술 인프라 관심사는 `application/service/`에 ABC, `infrastructure/<concern>/`에 구현체를 둔다. 파일 스토리지, Secrets Manager를 추가할 때도 동일한 구조를 따른다. ([layer-architecture.md](layer-architecture.md), [file-storage.md](file-storage.md), [secret-manager.md](secret-manager.md))

9. **에러는 예외 클래스 계층 + HTTP 변환은 `main.py`에서만** — `domain/errors.py`는 plain `Exception`만 던지고 HTTP를 모른다. `@app.exception_handler`가 유일한 변환 지점이다. 각 예외는 `domain/error_codes.py`의 enum에서 고유 `code`를 가지며, `src/common/error_response.py`의 `build_error_response()`가 root가 요구하는 `statusCode`/`code`/`message`/`error` 4필드 응답을 조립한다. Pydantic 검증 실패(422)도 `code: VALIDATION_FAILED`로 동일한 형식을 따른다. ([error-handling.md](error-handling.md))

10. **ID는 Domain 팩토리 classmethod에서 생성, 하이픈 없는 32자리 hex** — `Account.create()`가 유일한 생성 경로이며, `common/generate_id.py`가 `uuid.uuid4().hex`(하이픈 없음)를 반환해 전 도메인에서 일관되게 쓰인다. ([aggregate-id.md](aggregate-id.md))

11. **Domain Event는 `pull_events()`로 수집, 발행은 Outbox → SQS 경유** — `repo.save()`가 Aggregate 상태와 Outbox 행을 같은 트랜잭션으로 커밋하고, Command Handler는 그 직후 곧바로 반환한다(동기 드레인 금지). 독립적으로 주기 실행되는 `OutboxPoller`가 Outbox → SQS로 발행하고, `OutboxConsumer`가 SQS를 수신해 `application/event/<event>_event_handler.py`를 호출한다 — 이 핸들러가 이벤트 타입별로 `NotificationService`를 호출한다. Aggregate 상태와 Outbox 행이 같은 트랜잭션으로 커밋되므로 dual-write가 발생하지 않는다. ([domain-events.md](domain-events.md))

12. **Interface DTO(Pydantic)는 얇은 변환만** — `schemas.py`의 `BaseModel`은 요청/응답 형태만 정의하고, `application/query/result.py`의 Result 객체를 감싸는 역할만 한다. 형식 검증(422, Pydantic)과 비즈니스 규칙 위반(400, Domain 예외)을 혼동하지 않는다. ([cross-cutting-concerns.md](cross-cutting-concerns.md), [directory-structure.md](directory-structure.md))

13. **비동기 I/O는 전 레이어에서 일관되게 `async`/`await`** — Repository, Technical Service, Handler, 라우트 함수까지 모두 `async def`다. 동기 함수를 섞으면 이벤트 루프를 블로킹해 다른 요청 처리를 지연시킨다 — 특히 `infrastructure/`에서 동기 SDK를 실수로 호출하지 않도록 주의한다(`aioboto3`처럼 비동기 클라이언트를 명시적으로 선택한 이유이기도 하다). ([layer-architecture.md](layer-architecture.md))

---

위 13개 항목 모두 `examples/`의 실제 코드가 문서의 원칙을 그대로 따른다. Rate Limiting은 `slowapi`로 구현되어 있다 — [rate-limiting.md](rate-limiting.md) 참고.

### 관련 문서

- [../../CLAUDE.md](../../CLAUDE.md) — 키워드 → 문서 인덱스
- `../../../../docs/implementations/fastapi.md` — 21개 root 주제에 대한 커버리지 감사(coverage audit)
