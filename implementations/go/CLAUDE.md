# Go 구현 가이드

DDD 기반 Go 백엔드 서비스의 구현 상세를 담는다.

> **설계 원칙 (프레임워크 무관)** 은 루트의 [CLAUDE.md](../../CLAUDE.md) 및 `../../docs/architecture/`를 참조한다.
> 이 문서는 Go 구현 상세에 집중한다.

## 작업 시 참조할 문서

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 전체 구현 가이드 (디렉토리, 패턴, 에러처리) | `docs/guide.md` |
| 예시 코드 (Order 도메인 전체) | `examples/` |

## Go 구현 원칙 요약

- 패키지: `internal/domain/<domain>`, `internal/application/command`, `internal/application/query`, `internal/infrastructure/persistence`, `internal/interface/http`
- Repository: domain 패키지에 `interface`, infrastructure 패키지에 구현체
- CQRS: `XxxHandler` 구조체 + `Handle(ctx, cmd/query) (result, error)` 메서드
- 에러: `var ErrXxx = errors.New(...)` sentinel + `fmt.Errorf("...: %w", err)` wrapping
- Soft delete: `DeletedAt *time.Time` — hard delete 금지
- DI: 프레임워크 없이 `cmd/server/main.go`에서 생성자 체이닝
- 컴파일 타임 interface 검증: `var _ domain.Repository = (*RepositoryImpl)(nil)`

## 구현 검증

```bash
./harness.sh <projectRoot>
```
