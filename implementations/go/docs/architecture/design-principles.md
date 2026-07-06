# 핵심 설계 원칙 요약 (Go)

이 저장소의 다른 20개 문서가 다루는 내용을 TL;DR로 압축한 목록이다. 각 항목은 새로 만든 규칙이 아니라 해당 문서가 이미 설명하는 내용의 색인이다 — 상세 근거·코드·알려진 격차는 괄호 안 링크를 따라간다.

1. **레이어 방향 고정**: `Interface → Application → Domain`, `Infrastructure`는 `Domain`이 선언한 인터페이스를 구현해 의존성을 역전시킨다. `import` 그래프가 곧 의존 방향이다([layer-architecture.md](layer-architecture.md)).
2. **Domain 패키지는 프레임워크 무의존**: `internal/domain/account/`는 표준 라이브러리 기본 패키지 + 최소 의존성(`google/uuid`)만 import한다. `log`, `net/http`, `context`조차 쓰지 않는다([layer-architecture.md](layer-architecture.md), [cross-cutting-concerns.md](cross-cutting-concerns.md)).
3. **불변식은 Aggregate Root 메서드 안에서만 검증**한다. Application Handler는 조회 → 도메인 메서드 호출 → 저장의 조율만 담당한다([tactical-ddd.md](tactical-ddd.md)).
4. **Repository는 domain 패키지에 interface, infrastructure 패키지에 구현체**를 둔다. 구현체에 `var _ Repository = (*Impl)(nil)`로 컴파일 타임 만족 검증을 붙인다([repository-pattern.md](repository-pattern.md)).
5. **에러는 sentinel error**(`var ErrXxx = errors.New(...)`)로 타입화한다. 계층을 거치며 `fmt.Errorf("...: %w", err)`로 래핑하고, HTTP 상태 코드 변환은 Interface 레이어에서 `errors.Is`로만 수행한다([error-handling.md](error-handling.md)).
6. **CQRS는 `구조체 + Handle(ctx, cmd/query) (result, error)`**로 표현한다. Command Bus/Query Bus 없이 `router.go`에서 생성자로 직접 조립한다([cqrs-pattern.md](cqrs-pattern.md)).
7. **Aggregate ID는 UUID v4에서 하이픈을 제거한 32자리 hex**다(`common.NewID()`). 현재 `examples/`는 `uuid.NewString()`을 하이픈 포함 그대로 쓰는 알려진 격차가 있다([aggregate-id.md](aggregate-id.md)).
8. **DI 컨테이너는 없다** — 모든 의존성은 생성자 함수(`New...`)로 조립하고 `main.go`/`router.go`가 순서대로 연결한다. 새 의존성은 생성자 인자를 추가해 표현한다([module-pattern.md](module-pattern.md), [bootstrap.md](bootstrap.md)).
9. **`context.Context`는 모든 레이어 경계를 관통**한다 — 취소, 데드라인, (구현 시) Correlation ID와 트랜잭션이 인자로 명시적으로 전파된다. Node의 `AsyncLocalStorage` 같은 숨은 저장소가 없다([cross-cutting-concerns.md](cross-cutting-concerns.md), [persistence.md](persistence.md)).
10. **횡단 관심사는 미들웨어 체인**(`func(http.Handler) http.Handler`의 합성)으로 처리한다. 인증/로깅/Correlation ID를 Handler 밖에서 각각 하나의 관심사만 담당하도록 분리한다([cross-cutting-concerns.md](cross-cutting-concerns.md)).
11. **다른 Bounded Context 호출은 Adapter로 감싼다**: 호출하는 쪽 패키지에 인터페이스, 호출하는 쪽 infrastructure에 구현체를 두고, 다른 도메인의 Repository/Service를 직접 주입하지 않는다([cross-domain.md](cross-domain.md)).
12. **네이밍은 고정 규칙을 따른다**: 파일 `snake_case.go`, 패키지명은 소문자 단일 단어, 타입은 `PascalCase`, 인터페이스는 동사+er보다 역할 명사(`Repository`, `Notifier`)를 우선한다([directory-structure.md](directory-structure.md)).
13. **캡슐화는 패키지 단위로만 가능**하다 — Go에는 인스턴스 단위 `private`이 없으므로, 외부에 숨기고 싶은 타입은 애초에 별도 패키지로 분리해야 한다([tactical-ddd.md](tactical-ddd.md)).
14. **테스트는 3단계로 분리**한다: Domain(table-driven, 순수 함수 검증) / Application(수동 stub mock) / E2E(`testcontainers-go`, 실제 DB·SES 연동). 현재 `examples/`는 E2E만 실재한다([testing.md](testing.md)).

---

### 이 목록의 위치

이 문서는 21개(+이 문서를 포함한 6개 bonus) 문서 각각의 "TL;DR 인덱스"다 — 새로운 규칙을 만들지 않고, 다른 문서가 이미 코드로 근거를 든 내용을 한 페이지로 훑어볼 수 있게 요약한다. 특정 항목의 실제 코드·알려진 격차가 궁금하면 반드시 괄호 안 링크로 이동해 원문서를 읽는다.
