# 핵심 설계 원칙 요약

이 저장소의 다른 21개 문서에서 이미 다루는 원칙 중, FastAPI 구현에서 가장 자주 되짚어야 할 것들을 조합 순서(구조 → 레이어 → 에러/DI → 알려진 격차)로 압축했다. 각 항목은 상세 문서로 연결된다 — 상충하는 규칙을 새로 만들지 않고 기존 문서의 요약본 역할만 한다.

1. **도메인 우선 디렉토리 구조** — `src/<domain>/` 하위에 `domain/application/interface/infrastructure` 4개 레이어를 배치한다. 기술 레이어가 아니라 Bounded Context가 패키지 분리 기준이다. ([directory-structure.md](directory-structure.md))

2. **Domain 레이어는 프레임워크 무의존** — `fastapi`, `sqlalchemy`, `aioboto3` 등 어떤 외부 라이브러리도 import하지 않는다. `dataclasses`/`abc`/`datetime`/`enum` 같은 표준 라이브러리만 사용한다. ([tactical-ddd.md](tactical-ddd.md), [layer-architecture.md](layer-architecture.md))

3. **변경 가능한 상태는 일반 클래스, 불변 값은 `frozen dataclass`** — Aggregate Root(`Account`)는 `__init__` + 도메인 메서드를 가진 일반 클래스, Entity(`Transaction`)·Value Object(`Money`)·Domain Event는 `@dataclass(frozen=True)`. ([tactical-ddd.md](tactical-ddd.md))

4. **비즈니스 규칙은 Aggregate 메서드에 캡슐화, Handler는 조율만** — `deposit()`/`withdraw()` 등 도메인 메서드 진입 시 불변식을 즉시 검증한다. Application Handler는 Repository 조회 → Aggregate 메서드 호출 → 저장만 수행한다. ([layer-architecture.md](layer-architecture.md))

5. **Repository는 Aggregate 단위, ABC는 `domain/`에 · 구현체는 `infrastructure/`에** — 하위 Entity(`Transaction`)는 별도 Repository를 갖지 않고 Aggregate Root의 Repository를 통해서만 조회/저장된다. ([repository-pattern.md](repository-pattern.md))

6. **DI는 `Depends` 팩토리 함수 — 전용 컨테이너 없음** — Repository/Technical Service/Adapter의 ABC ↔ 구현체 바인딩은 `interface/rest/*_router.py`의 팩토리 함수(`_repo`, `_notification_service`)가 담당한다. NestJS의 `{ provide, useClass }`에 대응하는 유일한 지점이다. ([module-pattern.md](module-pattern.md))

7. **Command/Query Handler는 `XxxHandler` + `async def execute()`** — Command와 Query를 `application/command/`, `application/query/`로 물리적으로 분리한다. CommandBus/QueryBus 도입은 선택 사항이며 이 저장소는 아직 도입하지 않았다. ([cqrs-pattern.md](cqrs-pattern.md))

8. **Technical Service는 인터페이스-구현체 분리** — 이메일 발송(`NotificationService`) 같은 기술 인프라 관심사는 `application/service/`에 ABC, `infrastructure/<concern>/`에 구현체를 둔다. 파일 스토리지, Secrets Manager를 추가할 때도 동일한 구조를 따른다. ([layer-architecture.md](layer-architecture.md), [file-storage.md](file-storage.md), [secret-manager.md](secret-manager.md))

9. **에러는 예외 클래스 계층 + HTTP 변환은 `main.py`에서만** — `domain/errors.py`는 plain `Exception`만 던지고 HTTP를 모른다. `@app.exception_handler`가 유일한 변환 지점이다. **알려진 격차**: 현재 응답이 `{"message": ...}`뿐이라 root가 요구하는 `statusCode`/`code`/`message`/`error` 4필드와 에러 코드 enum이 없다. ([error-handling.md](error-handling.md))

10. **ID는 Domain 팩토리 classmethod에서 생성, 하이픈 없는 32자리 hex** — `Account.create()`가 유일한 생성 경로다. **알려진 격차**: 현재 `str(uuid.uuid4())`를 사용해 하이픈이 포함된다 — `uuid.uuid4().hex`로 교체해야 한다. ([aggregate-id.md](aggregate-id.md))

11. **Domain Event는 `pull_events()`로 수집, 발행은 Outbox 경유** — 이벤트는 Aggregate 내부에 쌓였다가 Handler가 저장 직후 꺼내 처리한다. **알려진 격차 (가장 중요)**: Outbox 테이블 없이 `notification_service.notify()`를 동기 직접 호출한다 — 저장 직후 프로세스가 죽으면 알림이 유실될 수 있다. ([domain-events.md](domain-events.md))

12. **Interface DTO(Pydantic)는 얇은 변환만** — `schemas.py`의 `BaseModel`은 요청/응답 형태만 정의하고, `application/query/result.py`의 Result 객체를 감싸는 역할만 한다. 형식 검증(422, Pydantic)과 비즈니스 규칙 위반(400, Domain 예외)을 혼동하지 않는다. ([cross-cutting-concerns.md](cross-cutting-concerns.md), [directory-structure.md](directory-structure.md))

13. **비동기 I/O는 전 레이어에서 일관되게 `async`/`await`** — Repository, Technical Service, Handler, 라우트 함수까지 모두 `async def`다. 동기 함수를 섞으면 이벤트 루프를 블로킹해 다른 요청 처리를 지연시킨다 — 특히 `infrastructure/`에서 동기 SDK를 실수로 호출하지 않도록 주의한다(`aioboto3`처럼 비동기 클라이언트를 명시적으로 선택한 이유이기도 하다). ([layer-architecture.md](layer-architecture.md))

---

위 항목 중 9, 10, 11번은 **문서가 이미 원칙을 정확히 제시하지만 `examples/`의 실제 코드는 아직 그 원칙을 완전히 따르지 않는** 항목이다. 같은 성격의 격차가 이 13개 목록 밖에도 있다 — 인증 부재([authentication.md](authentication.md)), 미들웨어 부재([cross-cutting-concerns.md](cross-cutting-concerns.md)), Rate Limiting 미구현([rate-limiting.md](rate-limiting.md)). 새 도메인을 추가하거나 기존 코드를 수정할 때 이 격차부터 우선 해소한다.

### 관련 문서

- [../../CLAUDE.md](../../CLAUDE.md) — 키워드 → 문서 인덱스
- `../../../../docs/implementations/fastapi.md` — 21개 root 주제에 대한 커버리지 감사(coverage audit)
