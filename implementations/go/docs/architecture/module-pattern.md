# 모듈 패턴 (Go) — DI 컨테이너 없이 패키지로 경계를 나눈다

Go 전용 문서 — root와 NestJS의 "모듈"(`@Module`, DI 컨테이너, `providers`/`exports`)에 대응하는 개념이 Go에는 없다. **Go에는 DI 컨테이너 자체가 없다.** 이 문서는 그 공백을 메우는 이 저장소의 실제 메커니즘 두 가지 — ① `main.go`의 수동 생성자 조립, ② Bounded Context 경계로서의 패키지 트리 — 를 다룬다. 디렉토리 레이아웃 자체(어느 파일이 어디에 있는지)는 [directory-structure.md](directory-structure.md)가 이미 다루므로 이 문서에서 반복하지 않는다 — 여기서는 그 구조를 "어떻게 조립하는가"에 집중한다.

---

## NestJS `@Module`이 하던 일, Go에서는 누가 하는가

| NestJS `@Module` | Go 대응 |
|---|---|
| `providers: [Service, { provide: Repo, useClass: RepoImpl }]` | `main.go`/`router.go`에서 `New...()` 생성자를 순서대로 호출 |
| `imports: [OtherModule]` | 다른 패키지를 `import`하고, 그 패키지가 exports하는(대문자로 시작하는) 타입/함수를 사용 |
| `exports: [Service]` | Go의 exported identifier(대문자 시작)는 패키지 밖에서 항상 접근 가능 — 별도 "exports 목록" 선언이 없다 |
| DI 컨테이너가 런타임에 그래프 해석 | 컴파일러가 `import` 그래프를 정적으로 검사 — 순환이 있으면 **컴파일 자체가 실패**한다 |
| `forwardRef()`로 순환 의존 우회 | 존재하지 않는다 — 아래 "순환 의존" 절 참고 |

핵심 차이: NestJS는 "모듈 선언"이라는 별도의 선언적 레이어가 있고 DI 컨테이너가 그것을 런타임에 해석한다. Go는 그 레이어가 없다 — **`import`문 + 생성자 호출이 곧 배선이다.**

---

## 메커니즘 1 — `main.go`의 수동 생성자 조립

```go
// cmd/server/main.go (실제 코드)
accountRepo := persistence.NewAccountRepository(db)
notifier := notification.NewService(notification.NewSESClient(), db)
mux := httphandler.NewRouter(accountRepo, notifier)
```

각 `New...()` 호출은 NestJS의 `providers` 배열 항목 하나에 대응하지만, 선언이 아니라 **실행되는 코드**다. 조립 순서는 의존 방향과 정확히 일치해야 한다 — `accountRepo`가 만들어지기 전에 `NewRouter(accountRepo, ...)`를 호출하는 코드는 애초에 컴파일되지 않는다(변수가 아직 선언되지 않았으므로). 이 저장소는 `main.go` → `router.go` → 개별 Handler 생성자로 조립 책임을 계층적으로 위임한다. 전체 흐름과 목표(설정 검증 + graceful shutdown 통합)는 [bootstrap.md](bootstrap.md) 참고.

**여러 도메인이 추가되면**, `main.go`는 도메인별로 Infrastructure를 만들고, 각 도메인의 `NewRouter`(또는 공유 `mux`에 라우트를 등록하는 함수)를 호출하는 식으로 커진다 — NestJS의 `AppModule.imports = [OrderModule, UserModule, PaymentModule]`이 하던 "루트 조합" 역할을, Go에서는 `main.go`가 각 도메인 조립 함수를 순서대로 호출하는 것으로 대신한다.

```go
// 여러 도메인이 있다고 가정한 main.go
accountRouter := accounthttp.NewRouter(accountRepo, notifier)
userRouter := userhttp.NewRouter(userRepo)

mux := http.NewServeMux()
mux.Handle("/accounts/", accountRouter)
mux.Handle("/users/", userRouter)
```

---

## 메커니즘 2 — 패키지 트리가 Bounded Context 경계를 대신한다

NestJS는 "1 Bounded Context = 1 `@Module`"이라는 명시적 단위가 있다. Go는 그 단위가 **패키지 트리**다: 하나의 도메인은 `internal/domain/<domain>/`, 그리고 그 도메인을 다루는 `internal/application/{command,query}/`·`internal/infrastructure/<concern>/`의 대응 파일들로 이루어진 집합이다.

```
internal/
  domain/
    account/              ← Account Bounded Context (도메인 규칙)
  application/
    command/
      create_account_handler.go   ← Account 관련 Command
      ...
    query/
      get_account_handler.go      ← Account 관련 Query
  infrastructure/
    persistence/
      account_repository.go       ← Account Repository 구현체
    notification/
      service.go                  ← Account 알림 구현체
```

이 저장소는 [directory-structure.md](directory-structure.md)에서 설명하듯 **레이어를 최상위, 도메인을 그 하위**에 두는 구조를 쓴다(NestJS의 반대). 도메인이 하나뿐인 지금은 이 구조가 평평하지만, 두 번째 도메인(예: User)이 추가되면 "이 파일이 어느 Bounded Context에 속하는가"는 **디렉토리 위치가 아니라 파일이 import하는 domain 패키지**로 판별한다 — `application/command/create_user_handler.go`가 `internal/domain/user`를 import하면 그것이 User BC 소속이라는 뜻이다. 여러 도메인이 쌓이면 `command/<domain>/`, `persistence/<domain>_repository.go`처럼 하위 디렉토리/파일명으로 세분화하는 것을 검토한다(directory-structure.md 참고).

**"모듈이 exports하는 Service"에 대응하는 개념**: NestJS는 `exports: [UserService]`로 명시해야 다른 모듈이 그 Service를 주입받을 수 있다. Go는 그런 선언이 없다 — `internal/domain/user` 패키지의 exported(대문자) 타입/함수는 같은 `internal/` 트리 안이라면 어디서든 import해서 쓸 수 있다. "내부 전용"으로 숨기고 싶은 타입은 소문자로 시작하는 미고유(unexported) 식별자로 만들거나, 아예 별도의 더 안쪽 패키지로 분리해야 한다([tactical-ddd.md](tactical-ddd.md)의 "캡슐화 한계" 참고) — Go는 인스턴스 단위 `private`이 없으므로 이것이 유일한 은닉 수단이다.

---

## 순환 의존 — Go는 우회로가 없다

NestJS는 두 모듈이 서로를 필요로 하면 `forwardRef(() => OtherModule)`로 순환을 "일단 컴파일되게" 만들 수 있다. **Go는 이 우회로가 없다** — 패키지 A가 패키지 B를 import하고 B가 A를 import하면, `go build`가 곧바로 다음과 같이 실패한다.

```
import cycle not allowed
	package github.com/example/account-service/internal/domain/account
		imports github.com/example/account-service/internal/domain/user
		imports github.com/example/account-service/internal/domain/account
```

이것은 제약이 아니라 **이점**이다: NestJS의 `forwardRef()`는 "설계에 문제가 있다"는 신호를 감춰버리지만, Go는 그 신호를 컴파일 에러로 즉시 드러낸다. 순환이 발생하면 다음 중 하나로 해결한다.

1. **공유되는 개념을 세 번째 패키지로 추출**한다 — A와 B가 모두 참조하는 타입/인터페이스를 `internal/domain/shared`(또는 두 도메인의 공통 상위 개념) 같은 별도 패키지로 옮기고, A와 B는 그 패키지만 import한다.
2. **Adapter로 방향을 강제로 단방향화**한다 — 두 도메인이 서로를 호출해야 하는 것처럼 보인다면, 실제로는 한쪽이 다른 쪽의 Adapter를 소유하는 방향으로 설계를 재정리할 수 있는 경우가 많다([cross-domain.md](cross-domain.md) 참고). "A가 B를 호출하고 B도 A를 호출한다"는 대개 두 BC의 경계를 잘못 그었다는 신호다.
3. **비동기 전환을 검토**한다 — 양방향 동기 호출이 정말 필요하다면, root [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)의 기준대로 한쪽 이상을 Integration Event(비동기)로 바꿔 순환 자체를 없앤다.

어느 경우든 Go에서는 "일단 우회하고 나중에 고친다"가 불가능하다 — 컴파일러가 순환을 막는 순간이 곧 설계를 재검토해야 하는 순간이다.

---

## 원칙

- **DI 컨테이너를 흉내 내지 않는다** — 리플렉션 기반 컨테이너나 서비스 로케이터를 직접 구현하려 하지 말고, 생성자 체이닝을 그대로 받아들인다.
- **패키지 = 캡슐화 경계**: 도메인 내부 타입을 숨기고 싶으면 unexported 식별자나 별도 패키지로 분리한다. "모듈 선언으로 숨긴다"는 방법은 Go에 없다.
- **순환 의존은 즉시 재설계 신호로 받아들인다** — 컴파일 에러를 우회하려 하지 않는다.
- **도메인이 늘어나면 패키지를 세분화**한다 — `command/<domain>/`, `persistence/<domain>_repository.go`처럼, 미리 만들지 않고 필요해질 때 나눈다.

---

### 관련 문서

- [directory-structure.md](directory-structure.md) — 실제 패키지 트리 레이아웃과 네이밍 규칙
- [bootstrap.md](bootstrap.md) — `main.go`의 전체 조립 순서
- [layer-architecture.md](layer-architecture.md) — 레이어 간 의존 방향
- [cross-domain.md](cross-domain.md) — 도메인 간 호출을 Adapter로 감싸 순환을 피하는 구체 예시
- [tactical-ddd.md](tactical-ddd.md) — 패키지 단위 캡슐화의 한계
