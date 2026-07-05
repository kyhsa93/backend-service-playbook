# FastAPI 구현 가이드

DDD 기반 FastAPI(Python) 서비스의 구현 상세를 담는다.

> **설계 원칙 (프레임워크 무관)** 은 루트의 [CLAUDE.md](../../CLAUDE.md) 및 `../../docs/architecture/`를 참조한다.
> 이 문서는 FastAPI 구현 상세에 집중한다.

## 작업 시 참조할 문서

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 전체 구현 가이드 (디렉토리, 패턴, 에러처리, DI) | `docs/guide.md` |
| 예시 코드 (Order 도메인 전체) | `examples/` |

## FastAPI 구현 원칙 요약

- 패키지: `src/<domain>/domain/`, `application/command/`, `application/query/`, `infrastructure/persistence/`, `interface/rest/`
- Repository: domain/에 ABC, infrastructure/에 구현체
- CQRS: `XxxHandler` 클래스 + `async def execute(self, cmd/query)` 메서드
- 에러: `domain/errors.py`에 예외 클래스 정의, `main.py`의 `@app.exception_handler`로 HTTP 매핑
- Soft delete: `deleted_at: datetime | None` — hard delete 금지
- DI: FastAPI `Depends`로 Handler를 라우터에 주입

## 구현 검증

```bash
# 언어 무관 기본 검사
../../harness.sh <projectRoot>

# FastAPI 전용 검사
python implementations/fastapi/harness/harness.py <projectRoot>
```
