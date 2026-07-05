# 컨벤션

## 1. REST API 설계 원칙

### URL 구조 — 리소스 중심, 복수 명사

URL은 **행위(동사)가 아닌 리소스(명사)**를 나타낸다. HTTP 메서드가 행위를 표현한다.

```
GET    /orders              주문 목록 조회
GET    /orders/:orderId     주문 단건 조회
POST   /orders              주문 생성
PUT    /orders/:orderId     주문 전체 수정
PATCH  /orders/:orderId     주문 부분 수정
DELETE /orders/:orderId     주문 삭제
```

잘못된 방식:
```
GET  /getOrders      동사를 URL에 넣지 않는다
POST /createOrder    동사를 URL에 넣지 않는다
GET  /order/:id      단수형 사용 금지 — 항상 복수형
```

### HTTP 메서드와 응답 코드

| 메서드 | 용도 | 성공 코드 | 응답 바디 |
|--------|------|----------|----------|
| `GET` | 리소스 조회 | 200 OK | 있음 |
| `POST` | 리소스 생성 | 201 Created | 선택 |
| `PUT` | 리소스 전체 수정 | 200 OK | 있음 |
| `PATCH` | 리소스 부분 수정 | 200 OK | 있음 |
| `DELETE` | 리소스 삭제 | 204 No Content | 없음 |

### 비 CRUD 행위 — 하위 리소스 경로

```
POST   /orders/:orderId/cancel     주문 취소
POST   /orders/:orderId/refund     주문 환불
POST   /users/:userId/verify-email 이메일 인증
```

### URL 네이밍 규칙

- **복수 명사**: `/orders`, `/users` (단수형 사용 금지)
- **kebab-case**: `/order-items`, `/payment-methods`
- **소문자만**: `/Orders` (X) → `/orders` (O)
- **후행 슬래시 없음**: `/orders/` (X)

### 목록 조회 — 페이지네이션

```
GET /orders?page=0&take=20&status=pending
```

- `page`: 0부터 시작
- `take`: 페이지 크기
- 필터: querystring으로 전달

---

## 2. 커밋 메시지 컨벤션

[Conventional Commits](https://www.conventionalcommits.org/) 스펙을 따른다.

### 메시지 구조

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### type 목록

| type | 설명 |
|------|------|
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 기능 변경 없이 코드 구조 변경 |
| `docs` | 문서만 변경 |
| `test` | 테스트 추가 또는 수정 |
| `chore` | 빌드, CI, 의존성 등 코드 외적인 작업 |
| `style` | 코드 포맷팅, 동작에 영향 없는 변경 |
| `perf` | 성능 개선 |

### scope 규칙

- scope는 **서비스 도메인명**: `order`, `user`, `payment`, `auth`
- 여러 도메인에 걸친 변경이면 scope 생략 또는 상위 개념 사용
- 코드 외적 변경: `ci`, `deps`, `docker` 등

### description 규칙

- 명령형이 아닌 서술형: "추가", "수정", "제거"
- 끝에 마침표 없음

### BREAKING CHANGE

```
feat(order)!: 주문 응답 스키마 변경

BREAKING CHANGE: GetOrderResponseBody의 totalPrice → totalAmount 필드명 변경
```

---

## 3. 브랜치 네이밍

```
<type>/<scope>-<short-description>
```

예시: `feat/order-cancel`, `fix/order-status-update`, `docs/cqrs-pattern`

**규칙:**
- 모든 단어는 `kebab-case`
- `main` 브랜치에서 분기한다
- `main` 브랜치에 직접 commit/push하지 않는다

### PR 워크플로우

```
1. main에서 새 브랜치 생성
2. 작업 후 commit (Conventional Commits 형식)
3. 원격에 push
4. main 브랜치로 PR 생성
```

### 머지 전략

- **Squash and merge**를 기본으로 사용한다.
- 머지 후 원격 브랜치는 자동 삭제한다.

---

## 4. 메서드 네이밍 원칙

### Service / Handler 메서드

- 동사 사용: `get`, `find`, `create`, `update`, `delete`, `cancel`, `transfer` 등
- 반환 타입 항상 명시

### Repository 메서드

- 조회: `find<Noun>s` (목록, 단건 동일)
- 저장: `save<Noun>`
- 삭제: `delete<Noun>`
- 수정(update) 메서드 금지 — 조회 후 도메인 메서드로 수정, `save<Noun>`으로 저장
