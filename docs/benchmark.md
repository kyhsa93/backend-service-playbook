# AI 에이전트 아키텍처 준수도 벤치마크

이 저장소는 harness(`docs/harness.md`)라는 객관적 채점기를 이미 갖고 있다 — "이 코드가 문서화된
아키텍처 규칙을 지키는가"를 사람의 리뷰 없이 기계적으로 점수화한다. 이 문서는 그 채점기를
**AI 코딩 에이전트의 스펙 준수 능력을 재는 벤치마크**로 재사용하는 방법을 정의한다.

## 왜 이게 벤치마크가 되는가

일반적인 코딩 벤치마크는 "테스트를 통과하는가"를 묻는다. 이 벤치마크는 다른 질문을 묻는다 —
**"문서화된 아키텍처 규칙을 스스로 찾아 읽고, 처음 보는 요구사항에 적용해 구조적으로 올바른
코드를 만들어낼 수 있는가."** 비즈니스 로직이 맞았는지가 아니라, 레이어 배치·의존 방향·
CQRS 분리·Domain Event/Outbox 패턴 같은 *구조*를 지켰는지를 harness가 그대로 점수화한다.
사람이 "이 PR 구조 괜찮네요"라고 승인하는 걸 대신할 수 있는 유일한 방법이 자동 채점이라는
점에서, harness가 이미 사람 대신 하고 있는 일을 에이전트에게도 똑같이 적용하는 것뿐이다.

## 태스크 형식

에이전트에게 주는 프롬프트는 다음 세 가지만 담는다 — **구현 방법을 알려주지 않는다.**

1. **아직 이 저장소에 없는 도메인**의 비즈니스 규칙(이전에 검증/스캐폴딩에 쓴 이름은 재사용하지
   않는다 — `docs/reference.md`의 Order, 스캐폴딩 검증에 쓴 Coupon/LoyaltyCategory, 이
   문서의 예시인 Subscription은 이미 "본 적 있는" 이름이 됐으므로 다음 벤치마크에는 새 이름을
   쓴다).
2. "이 저장소의 기존 컨벤션을 따르라"는 지시와, 어디서부터 읽어야 하는지에 대한 최소한의
   진입점(`implementations/<lang>/CLAUDE.md`) — 그 이상의 문서 경로는 알려주지 않는다.
   에이전트가 스스로 문서 인덱스를 따라가 관련 문서를 찾아 읽는 것 자체가 측정 대상이다.
3. 완료 기준: `harness.sh`를 스스로 돌려서 통과할 때까지 반복하라는 지시.

## 채점

```bash
bash implementations/<lang>/harness.sh <에이전트가 작업한 프로젝트 루트>
```

- **점수**: nestjs는 0~100 정규화 점수(`A (100/100, raw 630/630)` 형식)를 바로 내놓는다.
  go/java-springboot/kotlin-springboot/fastapi는 raw pass/fail 개수만 출력하므로,
  `passed / (passed + failed)`로 직접 정규화해 언어 간 비교 가능한 비율로 환산한다.
- **채점자는 에이전트 자신이 아니라 벤치마크 실행자여야 한다.** 에이전트의 "harness
  100/100 확인했습니다" 자기 보고를 그대로 신뢰하지 말고, 에이전트의 워크트리에 대해
  harness를 독립적으로 다시 돌려 확인한다 — 실행 환경 차이(예: `node_modules` 누락으로
  인한 빌드 실패가 진짜 구조 위반처럼 보이는 경우)를 사람이 걸러내야 하는 경우가 실제로
  있었다.
- **구조 점수와 별개로 비즈니스 로직도 눈으로 한 번 확인한다.** harness는 "패턴을 지켰는가"만
  보고 "그 패턴 안의 로직이 스펙과 맞는가"는 보지 않는다 — 100점이 나와도 상태 전이 조건이
  스펙과 다르게 구현됐을 수 있다.

## 실행 사례

nestjs에서 실제로 한 차례 실행했다. 태스크: "Subscription 도메인 추가 — `ownerId`/`planName`을
갖고, 생성 시 PENDING, `activate()`는 단순 상태 전이, `cancel(reason)`은 다른 부분이 반응할
수 있어야 하는 만큼 의미가 크다(이게 무엇을 뜻하는지는 이 저장소가 이 종류의 일을 어떻게
다루는지 보고 판단하라는 힌트만 줌), 이미 취소된 건 다시 취소 불가." 에이전트에게는 이 규칙과
`implementations/nestjs/CLAUDE.md`부터 시작하라는 것만 줬다 — `docs/reference.md`나
`scripts/create-domain.js`는 언급하지 않았다.

**결과**: 에이전트가 스스로 `CLAUDE.md`의 문서 인덱스를 따라가 `scripts/create-domain.js`
(스캐폴딩 생성기, Phase 2 산출물)를 발견해 스켈레톤을 생성한 뒤, "다른 부분이 반응해야 한다"는
힌트를 정확히 "도메인 이벤트 + Outbox 패턴"으로 해석해 `cancel()`이 `SubscriptionCancelled`
이벤트를 발행하도록, `activate()`는 이벤트 없는 단순 전이로 구현했다. 벤치마크 실행자가
독립적으로 재실행한 harness 결과는 **A (100/100, raw 630/630)** — 에이전트의 자기 보고와
일치했다. 도메인 코드를 직접 읽어 확인한 결과 비즈니스 로직도 스펙과 정확히 일치했다
(이미 취소된 구독의 재취소 시 에러, activate에는 이벤트 없음). Account/Card 기존 코드는
건드리지 않았다.

이 실행은 두 가지를 보여준다 — (1) harness가 실제로 낯선 요구사항에 대해서도 신뢰할 수 있는
채점자로 동작한다는 것, (2) 이 저장소의 문서 구조(CLAUDE.md 인덱스 → 관련 architecture 문서 →
필요하면 스캐폴딩 도구)가 사람뿐 아니라 에이전트에게도 스스로 따라갈 수 있을 만큼 잘
정리돼 있다는 것.

## 실행 사례 — 언어 간 비교

같은 태스크(Voucher 도메인: `ownerId`/`faceValue`, 발급 시 `ACTIVE`, `redeem()`은 단순 상태
전이, `expire()`는 "다른 부분이 반응할 수 있어야 하는 만큼 의미가 크다"는 힌트만 줌, 이미
redeem/expire된 건 재시도 불가)를 5개 언어에 동시에 실행했다. 프롬프트는 모두 동일하게
`implementations/<lang>/CLAUDE.md`만 진입점으로 주고, 그 외 문서 경로나 스캐폴딩 도구는
언급하지 않았다.

| 언어 | 자체 보고 | 독립 재검증 |
|---|---|---|
| nestjs | A (100/100, raw 815/815) | 815/815 — 일치 |
| fastapi | 854 passed, 0 failed | 854/854 — 일치 |
| go | 652 passed, 0 failed | 652/652 — 일치 |
| kotlin-springboot | 1172 passed, 0 failed | 1172/1172 — 일치 |
| java-springboot | 1404 passed, 0 failed | 최초 1433/1로 불일치 → 원인 규명 후 1404/0 일치 |

**5개 언어 모두 harness 만점을 받았고, 독립적으로 동일한 아키텍처 판단에 도달했다** — `redeem()`엔
Domain Event를 붙이지 않고 `expire()`에만 붙이는 선택을 5개 에이전트가 각자 근거를 대며 스스로
내렸다(예: "Card의 suspend/cancel처럼 아무도 반응하지 않는 전이는 이벤트 없음, Account의
suspend/close처럼 뭔가 반응해야 하는 전이는 이벤트 있음" 패턴을 그대로 적용). 문서 하나(root
`domain-service.md`/각 언어 `domain-events.md`)가 언어 경계를 넘어 같은 판단을 유도했다는 뜻이다.

**"에이전트 자기 보고를 그대로 믿지 않는다"는 원칙이 실제로 한 번 걸렸다** — java-springboot에서
독립 재검증 결과가 자기 보고와 달랐다. 원인은 코드가 아니라 harness 자체의 결함이었다: Gradle이
만든 `build/` 디렉토리 안에 Spotless의 중간 캐시(`build/spotless/spotlessJava/...`, 소스 트리를
부분적으로만 미러링)가 남아 있었고, harness가 이걸 실제 소스처럼 스캔해 "레이어 디렉토리 없음"
오탐을 냈다. `build/`를 지우고 재검증하면 자기 보고와 정확히 일치했다.

**벤치마크가 이 저장소 자체의 도구 회귀 3건을 찾아냈다**:
- go/kotlin-springboot의 스캐폴딩 생성기(`scripts/create-domain*`)가 그 시점 최신이던
  `repository-naming` harness 규칙(`find<Noun>s`/`save<Noun>` 통일, `findByX`/`findAll`/
  바레 `save` 금지)을 위반하는 코드를 만들어내고 있었다 — 규칙이 생긴 뒤 생성기가 갱신되지
  않았던 것. 두 언어 모두 벤치마크 실행 중 스스로 발견해 수정했고, 이후 생성기 자체도 고쳐서
  회귀를 막았다.
- java-springboot harness의 파일 수집기(`JavaFiles.java`)가 `test`/`.git`만 제외하고
  `build`는 제외하지 않아, 위에서 설명한 오탐을 냈다 — kotlin-springboot의 동등한
  수집기(`KtFiles.kt`)는 이미 `build`를 제외하고 있어 언어 간 구현이 불일치했던 것으로,
  `build` 제외를 추가해 통일했다.
- fastapi harness의 `directory-structure` 규칙이 `src/` 바로 아래를 `os.listdir`로 직접
  훑으면서 `SKIP_DIRS`(다른 규칙들이 공유하는 제외 목록, `__pycache__` 포함)를 거치지 않아,
  pytest가 만든 `src/__pycache__/`를 레이어가 없는 도메인 폴더로 오인해 오탐을 냈다 — 이
  규칙도 `SKIP_DIRS`를 쓰도록 고쳤다.

## 실행 사례 — 난이도 2단계 (같은 BC 안 Aggregate 2개 + Domain Service)

레벨 1(Voucher)이 전 언어 만점으로 변별력이 없었던 점을 보완하기 위해, 결정 포인트를 하나 더
요구하는 태스크를 5개 언어에 동시 실행했다. 태스크: **Booking**(`ownerId`/`seatCount`, 생성 시
`PENDING`, `confirm()`은 단순 전이로 `CONFIRMED`)과 **Cancellation**(`bookingId`로 원 예약을
참조, 취소하려는 `seatCount`)을 추가하되, 취소 요청은 (1) 원 Booking이 `CONFIRMED`여야 하고
(2) 요청 seatCount가 원 seatCount를 넘지 않아야만 접수되고, 둘 중 하나라도 어기면 **요청 자체가
생성되지 않는다**. 이 규칙은 한 Aggregate만 봐서는 판단할 수 없어(Booking은 취소 시도를 모르고,
Cancellation은 원 예약의 상태/수량을 모른다) Domain Service가 필요하다 — 이 저장소의 유일한
선례는 Payment/Refund의 `RefundEligibilityService`다. 프롬프트에는 이 선례를 언급하지 않았다.

| 언어 | 자체 보고 | 독립 재검증 |
|---|---|---|
| nestjs | 최초 A(96/100) → 스스로 수정 → A(100/100, raw 815/815) | 815/815 — 일치 |
| go | 671 passed, 0 failed | 671/671 — 일치 |
| fastapi | 872 passed, 0 failed | 872/872 — 일치 |
| java-springboot | 1494 passed, 0 failed | 1494/1494 — 일치 |
| kotlin-springboot | 1246 passed, 0 failed | 1246/1246 — 일치 |

**5개 언어가 독립적으로 완전히 동일한 설계 판단에 도달했다.** 전부 `RefundEligibilityService`를
선례로 찾아내 상태 없는(stateless) Domain Service로 판단 로직을 분리했고, **Refund 패턴과의
차이까지 스스로 감지**했다 — Refund는 거부돼도 `REJECTED` 상태로 저장되지만, 이번 태스크는
"요청 자체가 생성되지 않는다"고 명시했으므로, 5개 언어 모두 Domain Service가 값(결정 객체)을
반환하는 대신 예외를 즉시 던지도록 바꿨고, Application 레이어가 실패 시 저장 호출 자체에 도달하지
않도록 구현했다. 언어마다 독립적으로 실행했는데도 이 미묘한 스펙 차이를 똑같이 잡아냈다는 것은
`domain-service.md` 문서의 지침이 상당히 정밀하게 전달된다는 뜻이다.

**레벨 1과의 차이**: 레벨 1은 5개 언어 전부 처음부터 만점이었지만, 레벨 2에서는 nestjs가 처음에
96점(enum 대신 raw 문자열로 예외를 던진 실제 코드 결함)을 받았다가 스스로 고쳤다 — 난이도를
올리자 처음으로 "처음부터 완벽하지 않았던" 사례가 나왔다. 태스크 난이도를 하나 올린 것만으로
변별력이 생긴 셈이다.

## 실행 사례 — 난이도 3단계 (다른 BC를 동기 조회하는 Adapter 필요)

태스크: **Membership**(`accountId`/`ownerId`/`tier`)을 생성하려면, 참조하는 **Account**(이미
존재하는 다른 BC)가 정지되거나 해지된 상태가 아니어야 한다 — 그런 계좌면 생성 자체가 거부된다.
`cancel()`은 단순 전이. 프롬프트에는 "Adapter"나 "ACL" 같은 용어를 전혀 언급하지 않고, "다른
이미 존재하는 부분의 상태를 확인해야 한다"는 사실만 문장으로 제시했다.

| 언어 | 자체 보고 | 독립 재검증 |
|---|---|---|
| nestjs | A(100/100, raw 815/815) | 815/815 — 일치 |
| go | 637 passed, 0 failed | 637/637 — 일치 |
| fastapi | 832 passed, 0 failed | 832/832 — 일치 |
| java-springboot | 1407 passed, 0 failed | 최초 1377로 불일치(0 failed는 동일) → harness 결함 규명 후 build/ 무관하게 1377로 통일 |
| kotlin-springboot | 1147 passed, 0 failed | 1147/1147 — 일치, BUILD SUCCESSFUL(173 tests, 0 failed/0 error) 별도 확인 |

**5개 언어 모두 동기 Adapter/ACL 패턴을 정확히 골랐다** — Card/Payment가 이미 Account를 조회하는
선례(`AccountAdapter`/`AccountAdapterImpl`류)를 찾아 그대로 미러링했고, 전부 "Account의 상태
enum을 그대로 노출하지 않고 boolean 등으로 번역해서 넘긴다"는 ACL 원칙까지 지켰다. java 에이전트는
한 걸음 더 나가 Card가 이미 `AccountAdapterImpl`이라는 클래스명을 쓰고 있어 같은 이름을 재사용하면
Spring Bean 이름 충돌이 난다는 것까지 기존 코드 주석에서 미리 읽어내 `MembershipAccountAdapterImpl`로
피해갔다.

**독립 검증이 또 한 번 harness 자체의 버그를 잡아냈다** — java-springboot에서 자기 보고(1407)와
독립 재검증(1377)이 또 어긋났다. 이번엔 레벨 1에서 고쳤던 `build/` 스캔 문제가 **다른 세 개
규칙**(`PackageStructure.java`, `NoOrmAutoSyncInProdConfig.java`, `SharedInfra.java`)에도
독립적으로 존재했던 것이 원인이었다 — 각자 자기만의 디렉토리 제외 목록을 따로 갖고 있어서,
`JavaFiles.java` 하나만 고친 것으로는 부족했다. 컴파일된 `.class` 패키지 디렉토리와 복사된
`application.yml`을 실제 소스처럼 중복 스캔하고 있었다(오탐은 없었지만 카운트가 부풀려졌고,
이론상 stale build 산출물이 있으면 거짓 통과/거짓 실패로 이어질 수 있는 구조였다). 3개 규칙 모두
`build`를 제외하도록 통일해 고쳤다.

## 실행 사례 — 난이도 4단계 (다른 BC의 이벤트에 비동기로 반응)

태스크: **StandingOrder**(`accountId`/`ownerId`/`amount`)를 생성하면 `ACTIVE`. 참조하는
**Account**가 나중에 정지되면 StandingOrder도 자동으로 `PAUSED`가 되어야 하고, Account가
해지되면 자동으로 `CANCELLED`가 되어야 한다 — 이 반응은 Account 상태가 바뀌는 시점에 일어나야
하며 StandingOrder에 대한 직접 API 호출로 트리거되지 않는다. 레벨 3(Membership)의 "생성 시점에
한 번 동기 조회"와 의도적으로 대비되도록 설계했다 — 이번엔 동기 Adapter가 아니라 비동기
Integration Event 구독이 정답이다. 검증 방식도 강화했다: 각 에이전트에게 실제 계좌 정지/해지
API를 호출해 반응이 진짜로 일어나는지 e2e로 확인하라고 명시했다.

| 언어 | 자체 보고 | 독립 재검증 |
|---|---|---|
| nestjs | A(100/100, raw 835/835) | 815/815(기본) — 일치 |
| go | 639 passed, 0 failed | 639/639 — 일치 |
| fastapi | 829 passed, 0 failed | 829/829 — 일치 |
| java-springboot | 1407 passed, 0 failed, 1 skip | 1407/1407 — 일치 |
| kotlin-springboot | 1149 passed, 0 failed | 1149/1149 — 일치 |

**5개 언어 전부 레벨 3과 정확히 구분되는 판단을 내렸다** — 동기 Adapter가 아니라 Account가 이미
발행하는 `account.suspended.v1`/`account.closed.v1` Integration Event를 구독하는 비동기
경로를 택했고, 전부 실제 HTTP API로 계좌를 정지/해지시킨 뒤 StandingOrder 상태가 바뀔 때까지
폴링하는 e2e 테스트로 증명했다(단순히 컴파일되는 코드가 아니라 실제로 동작하는 것을 확인).

**이 라운드의 가장 중요한 발견 — 실제 아키텍처 결함을 2개 언어에서 독립적으로 찾아냈다.**
Card BC가 이미 `account.suspended.v1`/`account.closed.v1`을 구독하고 있었는데, StandingOrder가
같은 이벤트에 두 번째로 구독하려 하면서 **"하나의 이벤트를 여러 핸들러가 구독할 수 있다(1:N)"는
root `domain-events.md`의 원칙이 java-springboot와 fastapi에서는 실제로 지원된 적이 없었다**는
사실이 드러났다:
- **java-springboot**: `OutboxConsumer`가 `Collectors.toMap(eventType, identity())`로 핸들러
  맵을 만들고 있었다 — 같은 eventType으로 두 번째 핸들러 Bean이 등록되는 순간
  `IllegalStateException: Duplicate key`로 부팅 자체가 실패했을 구조. `Collectors.groupingBy`로
  바꿔 `Map<String, List<OutboxEventHandler>>`가 되도록 고쳤다.
- **fastapi**: `build_event_handlers()`가 `dict[str, EventHandlerFn]`(값이 단일 Callable)이었다.
  `dict[str, list[EventHandlerFn]]`로 바꾸고 `OutboxConsumer`가 리스트를 순회하며 전부 호출하도록
  고쳤다 — 스캐폴딩 생성기(`create_domain.py`)와 `domain-events.md` 문서까지 함께 갱신했다.
- **go/nestjs는 애초에 문제가 없었다** — go는 `main.go`가 손으로 조립하는 `map[string]outbox.Handler`
  라 같은 키에 여러 핸들러를 순차 호출하도록 그냥 코드를 추가하면 됐고, nestjs의
  `EventHandlerRegistry`는 이미 `Map<string, EventHandlerFn[]>` 형태였다.
- **kotlin-springboot는 다른 방식으로 대응했다** — `EventHandlerRegistry`의 기존 람다 안에 두
  번째 호출을 추가하는 방식으로 동작은 하지만, java/fastapi처럼 구조 자체(리스트 기반)를 고친
  게 아니라 이번 두 구독자만 하드코딩한 것이라, 다음에 같은 이벤트에 세 번째 구독자가 추가되면
  또 손으로 편집해야 한다 — java/fastapi의 해법보다 확장성이 낮다.

이건 harness가 만점을 준 상태에서도 실제로 존재하던 결함이었다 — 왜냐하면 지금까지 이 저장소
어떤 도메인도 "같은 이벤트를 두 BC가 동시에 구독"하는 시나리오를 실제로 만든 적이 없었기
때문이다(Card 하나만 구독자였다). 벤치마크 태스크가 우연이 아니라 **의도적으로 설계한 난이도
축**(1:N 팬아웃)이 실제 미검증 코드 경로를 건드리면서 찾아낸 진짜 버그다.

## 실행 사례 — 난이도 5단계 (같은 BC 안 배치 + Domain Service 조합)

태스크: **정기 이체 규칙**(`sourceAccountId`/`targetAccountId`/`monthlyAmount`)을 등록하면
매달 1일 ACTIVE 규칙 전부를 사용자 API 호출 없이 자동으로 이체한다. 잔액부족/계좌정지면
그 회차만 건너뛰고 다음 달 재시도하며, 한 규칙의 실패가 다른 규칙 처리에 영향을 주면 안 된다.
레벨 4(StandingOrder, 비동기 이벤트 반응)까지 검증한 세 축 — 스케줄링/Task Outbox 배치
(이자 지급·카드 명세서와 동일 패턴), Domain Service(레벨 2의 `RefundEligibilityService`
선례), 규칙 단위 장애 격리 — 를 하나의 태스크에서 **조합**해야 하는지가 관건이었다. 출금/입금
계좌를 의도적으로 같은 BC 안 같은 Aggregate 타입으로 유지해(레벨 3/4와 달리) "동기 조회 vs
비동기 반응" 축과 섞이지 않게 했다.

리소스 경합(5개 언어 testcontainers 동시 실행이 이전에 이 머신의 Docker 데몬을 마비시킨 전례가
있다)을 피하려 2개씩 파도 형태로 실행했다(go+fastapi → nestjs+java-springboot →
kotlin-springboot 단독).

| 언어 | 자체 보고 | 독립 재검증 |
|---|---|---|
| go | harness 669/669, 단위 75+27 통과, E2E는 작성만 하고 미실행 | harness 669/669·단위 일치, **E2E 2/4 FAIL** — 실제 버그 발견(아래) |
| fastapi | harness 850/850, 단위 126 통과, E2E는 작성만 하고 미실행 | harness 850/850·단위 일치, **E2E 5/5 통과** |
| nestjs | A(100/100, raw 855/855), 단위 134/134, E2E 60/60 | 전부 일치 |
| java-springboot | (독립 검증이 자체 보고보다 먼저 완료됨 — 아래 참고) | harness 1477/1477, **최초 E2E 2/4 FAIL** → 버그 수정 후 재검증 191/191 통과 |
| kotlin-springboot | harness 1215/1215, 전체 빌드 179 테스트 0 실패 | harness 1215/1215·179/179 일치(단, 최초 재실행 시도는 gradle 캐시로 인한 `UP-TO-DATE` 오탐 — `--rerun`으로 실제 재실행해 확인) |

**5개 언어 중 3개(fastapi, nestjs, kotlin-springboot)만 첫 제출에서 깨끗하게 통과했다.**
나머지 2개(go, java-springboot)에서 harness와 단위 테스트는 만점이지만 **실제 인프라를 띄운
E2E에서만 드러나는 진짜 버그**가 나왔다 — 이 벤치마크가 "구조 준수"만이 아니라 "실제로
동작하는가"까지 봐야 하는 이유를 보여주는 가장 뚜렷한 사례다.

- **go**: 정기 이체 실행 시 `referenceID := ruleID + "-" + period`(32자리 hex ID + `-YYYY-MM`
  = 40자)를 `transactions.reference_id VARCHAR(36)` 컬럼에 저장하려다 Postgres가
  `value too long for type character varying(36)`으로 거부한다 — 두 번째 달 실행(재시도
  시나리오)마다 실패한다. 단위 테스트는 in-memory fake라 컬럼 길이 제약을 아예 거치지 않아
  잡지 못했다. 벤치마크 코드는 수정하지 않고 결과를 있는 그대로 기록했다(이 라운드의 컨벤션 —
  실제 사용처 없이 인프라만 미리 병합하지 않는 것과 같은 이유).
- **java-springboot**: `RecurringTransferSchedulingE2ETest`가 시나리오별로 3개의 `@Test`
  메서드를 나눠 각각 `enqueueMonthlyRecurringTransfers()`를 호출했는데, 스케줄러의
  `dedupId`(`TASK_TYPE + "-" + YearMonth.now()`)가 같은 달 안에서는 항상 동일해 SQS FIFO의
  5분 중복 제거 윈도우에 걸려 **첫 번째 테스트 메서드의 호출만 실제로 Task Queue에 들어가고
  나머지는 조용히 무시**됐다 — "잔액부족 규칙은 건너뛰고 다른 규칙은 정상 처리" 시나리오의
  정상 규칙이 실제로는 전혀 처리되지 않았는데도 잔액부족 쪽 단언은 우연히 통과해 거짓
  성공처럼 보일 뻔했다. 같은 태스크를 병렬로 수행하던 nestjs 에이전트가 정확히 같은 함정을
  미리 발견해 회피했다는 사실(모든 시나리오를 하나의 테스트 메서드로 묶음)을 근거로 java
  에이전트에게 구체적으로 진단을 전달했고, 3개 `@Test`를 1개로 합쳐 수정 후 재검증에서
  191/191 전부 통과했다.
- **Docker 교차 오염(진짜 버그 아님, 별도 기록)**: 검증 도중 한 번은 java의 E2E가 훨씬 심하게
  실패(`PSQLException: FATAL: terminating connection due to administrator command`)했는데,
  원인은 코드가 아니라 **같은 시점에 끝난 nestjs 에이전트의 "Docker 정리" 마무리 단계가 java가
  아직 쓰고 있던 Postgres/LocalStack 컨테이너까지 함께 종료**시킨 것이었다. 두 에이전트를
  전부 조용한 환경(`docker ps`/`ps aux` 확인)에서 재실행해서야 원래의 좁은 실패(위 항목)만
  재현됨을 확인했다 — 인프라스러운 실패(연결 강제 종료, 인증 연쇄 실패)는 실제 버그로 단정하기
  전에 형제 에이전트의 정리 단계부터 의심해야 한다는 교훈을 남겼다.
- **부수 발견**: go 에이전트가 이 태스크에서 여러 Repository(출금 계좌+입금 계좌+규칙)를
  하나의 트랜잭션으로 묶어야 하는 실제 필요에 부딪혀 `context` 기반 `WithTx`/`TxFromContext`/
  `QuerierFrom` 트랜잭션 매니저를 실제로 구현·검증했다 — 이는 기존에 열려 있던 이슈(멀티
  Repository 트랜잭션 전파 미구현)의 설계를 실제로 검증한 것이었다. 다만 main에는 아직 이를
  쓰는 실사용처가 없어(모든 기존 Handler가 단일 Repository만 사용) 죽은 코드가 되는 것을
  피하려 병합하지 않고 이슈에 설계 검증 결과만 기록했다.

## 확장하는 방법

- **언어 간 비교**: 같은 태스크를 5개 언어 각각에서 실행해 정규화 점수를 나란히 놓는다 —
  특정 언어의 문서/컨벤션이 다른 언어보다 에이전트가 따라가기 쉬운지 드러날 수 있다.
- **모델 간 비교**: 같은 태스크를 다른 모델(예: 더 작은 모델)로 실행해 점수를 비교한다 —
  "아키텍처 문서를 읽고 구조를 지키는 능력"이 모델 규모에 얼마나 좌우되는지를 보는 실험이 된다.
- **태스크 스위트**: 난이도를 나눠 여러 태스크를 미리 정의해두면(단순 CRUD 도메인 / 도메인
  이벤트가 필요한 도메인 / 다른 BC를 동기 조회하는 Adapter가 필요한 도메인) 매번 새로 설계할
  필요 없이 반복 실행 가능한 스위트가 된다.
- **회귀 감시**: 이 저장소의 docs/harness가 바뀔 때마다 같은 태스크를 재실행해 점수가
  떨어지지 않는지 확인하면, 문서/harness 변경이 "에이전트가 따라가기 쉬운 정도"를 실수로
  해친 건 아닌지도 감시할 수 있다.

## 관련 문서

- `docs/harness.md` — harness 자체의 설계 원칙(무엇을 검사하고 무엇을 검사하지 않는지)
- `implementations/<lang>/CLAUDE.md` — 벤치마크 태스크가 에이전트에게 주는 유일한 진입점
- `implementations/<lang>/scripts/create-domain*` — 에이전트가 스스로 발견해 활용할 수 있는
  스캐폴딩 도구(Phase 2 산출물). 벤치마크 프롬프트에 직접 언급하지 않는다 — 발견 자체가
  측정 대상이다.
