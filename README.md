# Backend Service Playbook

DDD 기반 백엔드 서비스의 **프레임워크·언어 무관** 설계·구현 원칙을 담은 가이드.

코드 예시는 TypeScript를 사용하지만, 패턴 자체는 Go·Java·Python 등 어떤 언어에도 동일하게 적용된다.
언어·프레임워크별 실제 구현 가이드는 `implementations/<lang>/` 참조. `docs/implementations/`는 루트 원칙과 언어별 문서 간 커버리지 감사 리포트다.

---

## 구성

```
docs/
  architecture/     핵심 설계 원칙 (DDD, 레이어, Repository, CQRS 등)
  checklist.md      구현 완료 후 자기 검토 체크리스트
  conventions.md    커밋·브랜치·REST API 컨벤션
  development-process.md  설계→구현 워크플로우 (8 에이전트 역할)

implementations/
  nestjs/             NestJS (TypeScript) 구현 가이드 + 예시 + harness
  go/                 Go 구현 가이드 + 예시 + harness
  java-springboot/    Spring Boot (Java) 구현 가이드 + 예시 + harness
  kotlin-springboot/  Kotlin Spring Boot 구현 가이드 + 예시 + harness
  fastapi/            FastAPI (Python) 구현 가이드 + 예시 + harness

CLAUDE.md           AI 에이전트용 문서 인덱스 (키워드 → 문서)
AGENTS.md           AI 에이전트 작업 가이드 (워크플로우·원칙)
```

---

## 하네스 사용법

각 구현체 디렉토리에 harness가 포함된다. 구조·배치·어노테이션 규칙을 모두 검사한다.

| 구현체 | 실행 방법 | 사전 조건 |
|--------|-----------|-----------|
| NestJS | `bash implementations/nestjs/harness.sh <root>` | node |
| Go | `bash implementations/go/harness.sh <root>` | Go 1.22+ |
| Spring Boot (Java) | `bash implementations/java-springboot/harness.sh <root>` | 없음 |
| Kotlin Spring Boot | `bash implementations/kotlin-springboot/harness.sh <root>` | 없음 |
| FastAPI | `bash implementations/fastapi/harness.sh <root>` | python3 |
