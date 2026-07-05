# Backend Service Playbook

DDD 기반 백엔드 서비스의 **프레임워크·언어 무관** 설계·구현 원칙을 담은 가이드.

코드 예시는 TypeScript를 사용하지만, 패턴 자체는 Go·Java·Python 등 어떤 언어에도 동일하게 적용된다.
언어·프레임워크별 구현 상세는 `docs/implementations/` 참조.

---

## 구성

```
docs/
  architecture/     핵심 설계 원칙 (DDD, 레이어, Repository, CQRS 등)
  checklist.md      구현 완료 후 자기 검토 체크리스트
  conventions.md    커밋·브랜치·REST API 컨벤션
  development-process.md  설계→구현 워크플로우 (8 에이전트 역할)

implementations/
  nestjs/           NestJS (TypeScript) 구현 가이드 + 예시 + harness
  go/               Go 구현 가이드 + 예시 + harness
  springboot/       Spring Boot (Java) 구현 가이드 + 예시 + harness
  fastapi/          FastAPI (Python) 구현 가이드 + 예시 + harness

harness.sh          언어 무관 구조·파일 배치 검증 스크립트
CLAUDE.md           AI 에이전트용 문서 인덱스 (키워드 → 문서)
AGENTS.md           AI 에이전트 작업 가이드 (워크플로우·원칙)
```

---

## 하네스 사용법

설치 불필요. bash/zsh가 있으면 바로 실행된다.

```bash
./harness.sh <projectRoot>
```

검사 항목:

| 항목 | 설명 |
|------|------|
| structure | 도메인별 4레이어 디렉토리 존재 여부 |
| cqrs-pattern | application/command/, application/query/ 분리 |
| file-placement | 파일명 suffix 기반 레이어 배치 규칙 |
| shared-infra | outbox·task-queue 패턴 사용 시 공용 모듈 존재 여부 |
| event-placement | 이벤트 핸들러·인티그레이션 이벤트 배치 |

---

## 구현체별 하네스

각 구현체 디렉토리에는 언어별 추가 harness가 포함된다. `harness.sh` (언어 무관 기본 검사)를 먼저 실행한 뒤 구현체 harness를 이어서 실행한다.

| 구현체 | 기본 harness | 구현체 harness |
|--------|-------------|----------------|
| NestJS | `./harness.sh <root>` | `cd implementations/nestjs/harness && npm run evaluate -- <root>` |
| Go | `./harness.sh <root>` | `cd implementations/go/harness && go run . <root>` |
| Spring Boot | `./harness.sh <root>` | `cd implementations/springboot/harness && ./gradlew run --args="<root>"` |
| FastAPI | `./harness.sh <root>` | `cd implementations/fastapi/harness && python harness.py <root>` |

각 구현체 harness의 상세 사용법은 해당 디렉토리의 README 참조.

---

## 프로젝트별 harness 확장

이 플레이북을 참조하는 실제 프로젝트에서 외부에서 harness를 사용하려면:

```bash
#!/usr/bin/env bash
# <your-project>/harness-local.sh

ROOT="${1:-.}"

# 1. 언어 무관 기본 검사
/path/to/backend-service-playbook/harness.sh "$ROOT"

# 2. 언어별 추가 검사 (예: NestJS)
cd /path/to/backend-service-playbook/implementations/nestjs/harness
npm run evaluate -- "$ROOT"
```
