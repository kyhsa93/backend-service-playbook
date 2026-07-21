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
