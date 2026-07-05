# Kotlin Spring Boot 구현 가이드

DDD 기반 Kotlin Spring Boot 서비스의 구현 상세를 담는다.

> **설계 원칙 (프레임워크 무관)** 은 루트의 [CLAUDE.md](../../CLAUDE.md) 및 `../../docs/architecture/`를 참조한다.
> Java Spring Boot와 구조는 동일하다 — Kotlin 관용 표현 차이만 `docs/guide.md`에서 확인한다.

## 작업 시 참조할 문서

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 전체 구현 가이드 (data class, open, nullable, sealed class) | `docs/guide.md` |
| Java Spring Boot와 공통 패턴 | `../springboot/docs/guide.md` |
| 예시 코드 (Order 도메인 전체) | `examples/` |

## Kotlin Spring Boot 구현 원칙 요약

- 패키지: `<root>.order.domain`, `.application.command`, `.application.query`, `.infrastructure.persistence`, `.interfaces.rest`
- `data class`: Command, Result, Event — 불변 DTO
- `interface`: domain Repository — Spring 무의존
- `open class` 또는 `kotlin-spring` 플러그인: @Service/@Repository 클래스 프록시 허용
- Nullable `Order?`: Optional 대신 사용
- domain/에 Spring 어노테이션 금지
- Soft delete: `deletedAt: LocalDateTime?`

## 구현 검증

```bash
# 언어 무관 기본 검사
../../harness.sh <projectRoot>

# Kotlin Spring Boot 전용 검사
./harness.sh <projectRoot>
```
