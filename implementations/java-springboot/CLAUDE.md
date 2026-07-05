# Spring Boot 구현 가이드

DDD 기반 Spring Boot(Java) 서비스의 구현 상세를 담는다.

> **설계 원칙 (프레임워크 무관)** 은 루트의 [CLAUDE.md](../../CLAUDE.md) 및 `../../docs/architecture/`를 참조한다.
> 이 문서는 Spring Boot 구현 상세에 집중한다.

## 작업 시 참조할 문서

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 전체 구현 가이드 (패키지 구조, 어노테이션, 패턴) | `docs/guide.md` |
| 예시 코드 (Order 도메인 전체) | `examples/` |

## Spring Boot 구현 원칙 요약

- 패키지: `<root>.order.domain`, `.application.command`, `.application.query`, `.infrastructure.persistence`, `.interfaces.rest`
- domain/에는 Spring 어노테이션 금지 (`@Service`, `@Repository`, `@Component`)
- Repository 인터페이스: domain/ 패키지에 정의, 구현체: infrastructure/에 `@Repository`
- Command Service: `@Service @Transactional`, Query Service: `@Service @Transactional(readOnly = true)`
- Soft delete: `deletedAt` 컬럼, hard delete 금지
- 도메인 이벤트: `ApplicationEventPublisher::publishEvent` (동기 발행)

## 구현 검증

```bash
# 언어 무관 기본 검사
../../harness.sh <projectRoot>

# Spring Boot 전용 검사
./harness.sh <projectRoot>
```
