# Harness 설계 원칙

각 `implementations/<lang>/harness/`는 그 언어의 `examples/`가 `docs/`(공통) + `implementations/<lang>/docs/`(언어별)의 가이드 규칙을 실제로 지키는지 정적으로 검증하는 자동 평가기(evaluator/linter)다. 언어마다 구현 방식(TypeScript AST 분석, Go 프로그램, bash+grep, Python AST 분석)은 다르지만, 새 evaluator/규칙을 추가하거나 확장할 때는 아래 원칙을 언어와 무관하게 따른다.

## 핵심 원칙

- **`examples/`의 비즈니스 예시(Account 도메인 등)는 설명용 샘플이다.** 특정 업무 도메인을 정답으로 고정하지 않는다 — harness 규칙이 "Account 도메인이 이렇게 동작해야 한다"를 전제로 삼으면 안 된다.
- **harness는 아키텍처 규칙 준수 능력을 평가하지, 비즈니스 로직의 정답 여부를 평가하지 않는다.** 레이어 배치, 의존 방향, 네이밍, 트랜잭션 경계, Outbox 패턴 같은 *구조적* 규칙만 대상으로 한다.
- **정답 코드를 하나로 고정하기보다 assertion + evaluator 기반 부분 점수 방식을 우선한다.** "이 파일이 정확히 이렇게 생겨야 한다"가 아니라 "이 규칙을 위반했는가"를 개별적으로 검사해 합산한다 — 유효한 구현 방식이 여러 개 있을 수 있다는 전제다.
- **`docs/checklist.md`(및 언어별 `checklist.md`)는 사람이 읽는 문서이면서 evaluator 구현의 명세 역할도 한다.** 새 체크리스트 항목을 추가할 때는 그것이 기계적으로 검증 가능한지 함께 검토한다.

## 비평가 대상 (harness가 검사하지 않는 것)

harness는 특정 업무 도메인 지식을 평가하지 않는다.

예: 주문 취소, 결제 승인, 재고 예약, 회원 등급 정책, 계좌 정지/재개 규칙 등.

이런 내용은 문서 예시나 `examples/`의 runnable example에는 들어갈 수 있지만, **기본 harness 규칙의 필수 전제가 되어서는 안 된다.** 예를 들어 "Outbox 패턴이 같은 트랜잭션에서 이벤트를 적재하는가"는 검사 대상이지만, "계좌 정지는 SUSPENDED 상태에서만 재개할 수 있는가" 같은 도메인 규칙은 harness가 아니라 코드 리뷰나 도메인 단위 테스트로 검증한다.

이 구분이 흐려지면, 새 evaluator가 실수로 특정 비즈니스 도메인(현재는 Account)에 결합되어 harness가 프레임워크 무관·도메인 무관 아키텍처 가이드가 아니라 "Account 서비스 전용 linter"로 변질된다.

## 관련 문서

- `docs/checklist.md` — 자기 검토 체크리스트(대부분 harness가 자동 검증, 일부는 수동 검증 대상으로 명시)
- `implementations/<lang>/harness/` — 언어별 evaluator 구현
- `implementations/<lang>/harness.sh` — 실행 진입점(`bash implementations/<lang>/harness.sh <projectRoot>`)
