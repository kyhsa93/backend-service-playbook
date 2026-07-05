# AI 에이전트 작업 가이드

이 문서는 AI 에이전트가 이 플레이북을 사용해 백엔드 서비스를 설계·구현할 때의 워크플로우와 핵심 원칙을 담는다.
문서 인덱스(키워드 → 파일)는 `CLAUDE.md` 참조.

---

## 핵심 원칙

**설계 먼저, 구현은 나중에.**
코드를 작성하기 전에 반드시 아래 두 문서를 읽는다:

1. `docs/development-process.md` — 어떤 설계 산출물을 먼저 만들어야 하는지
2. `docs/architecture/` 중 해당 작업 관련 문서 — 규칙 확인

구현 중 규칙이 기억나지 않으면 코드를 작성하지 말고 해당 문서를 먼저 읽는다.

---

## 작업 유형별 워크플로우

### 신규 도메인 추가

```
1. 요구사항 분석 (RA)   → 유비쿼터스 언어, 핵심 유스케이스 목록
2. 전략 설계 (SD)       → Bounded Context, Context Map
3. 데이터 모델 (DM)     → Aggregate, Entity, Value Object 식별
4. 전술 설계 (TD)       → 레이어 구조, Repository 인터페이스, Command/Query 정의
5. 테스트 설계 (TE)     → 도메인 단위 테스트 케이스
6. 구현 (IM)            → 설계 산출물 기준으로 코드 작성
7. 검증 (VA)            → 하네스 실행 + 체크리스트 검토
8. 문서 검토 (LA)       → 설계 산출물과 구현 일치 여부 확인
```

### 레거시 기능 수정

기존 코드를 수정할 때는 Vertical Slice 방식으로 접근한다.
한 번에 전체를 리팩토링하지 않고, 수정하는 슬라이스 단위로 4레이어 구조에 맞게 정리한다.
자세한 절차는 `docs/development-process.md` → 레거시 기능 수정 섹션 참조.

### 버그 수정

1. 버그 재현 테스트 작성
2. 원인 파악 — 레이어 위반 여부 먼저 확인
3. 수정 범위를 최소화하여 패치
4. 하네스 실행으로 구조 회귀 없음 확인

---

## 절대 위반하면 안 되는 규칙

| 규칙 | 근거 문서 |
|------|-----------|
| domain/ 레이어는 외부 라이브러리·프레임워크 import 금지 | `layer-architecture.md` |
| 비즈니스 규칙은 Aggregate Root 메서드 안에만 | `tactical-ddd.md` |
| Repository 인터페이스는 domain/, 구현체는 infrastructure/ | `repository-pattern.md` |
| Application Service는 조율만 — 비즈니스 로직 직접 수행 금지 | `layer-architecture.md` |
| Domain Event → Outbox 경유 발행 (직접 publish 금지) | `domain-events.md` |
| DB 변경 + 이벤트 발행은 같은 트랜잭션 (dual-write 금지) | `domain-events.md` |
| hard delete 금지 — deletedAt soft delete | `repository-pattern.md` |
| 에러는 enum으로 타입화 — free-form 문자열 금지 | `error-handling.md` |

---

## 구현 완료 후 검증

### 1. 하네스 실행

`harness.sh`는 언어 무관한 구조·배치 규칙을 검사한다.

```bash
./harness.sh <projectRoot>
```

FAIL 항목이 있으면 해당 파일을 올바른 레이어로 이동한 뒤 재실행.

**프로젝트에 언어별 추가 검사가 구성돼 있다면 그것도 함께 실행한다.**
언어별 추가 검사가 없다면 팀에 요청하거나 직접 구성한다.
구성 방법은 `README.md` → 프로젝트별 하네스 확장 참조.

### 2. 체크리스트 검토

`docs/checklist.md`를 열고 STEP 1~10 순서로 확인.
설계 산출물이 포함된 작업이라면 STEP 11도 확인.

---

## 문서 탐색 방법

작업 키워드를 `CLAUDE.md`의 표에서 찾는다 → 해당 문서를 읽는다 → 구현한다.

모르는 내용이 생기면 추측으로 코드를 작성하지 말고 관련 문서를 먼저 찾아 읽는다.
문서에 없는 내용이라면 가장 단순한 방식으로 구현하고, 설계 판단이 필요한 경우 사람에게 확인을 요청한다.
