// domain-event-outbox evaluator — Aggregate가 도메인 이벤트를 발행할 때 가이드의
// Outbox 패턴을 따르는지 + Integration Event 경계를 지키는지 검증
// (guide: docs/architecture/domain-events.md).
//
// Applicability gate: 아래 중 하나라도 존재해야 실행 — 없으면 skip(maxScore=0).
//   - domain/ 레이어의 Aggregate에 `_events.push(new XxxEvent(` 패턴
//   - 코드베이스 어디든 `@HandleEvent(` / `@HandleIntegrationEvent(` 사용
//   - 코드베이스 어디든 `eventBus.publish(` 호출
//
// Rules:
// 1. src/outbox/ 모듈 존재.
// 2. Repository 구현체 중 OutboxWriter / saveAll(outbox) 패턴 사용.
// 3. Repository 구현체에 clearEvents() 호출 흔적.
// 4. Application 레이어가 도메인 이벤트 객체를 직접 `new` 하지 않음
//    (이벤트는 Aggregate 내부 도메인 메서드에서만 생성).
// 5. Application 레이어의 OutboxWriter 참조는 `application/event/` EventHandler에서만 허용
//    (Command Service 등 다른 application 서브디렉토리에서는 금지).
// 6. @HandleEvent 보유 파일은 application/event/<domain-event>-handler.ts 위치.
// 7. @HandleIntegrationEvent 보유 파일은 interface/integration-event/<domain>-integration-event-controller.ts 위치.
// 8. EventBus.publish() 직접 호출 금지 — @nestjs/cqrs 사용 중에도 Outbox 경로 준수.
// 9. OutboxPoller/OutboxConsumer가 존재하고, 발행되는 모든 Domain Event 타입이
//    EventHandlerRegistry.register(...)로 어딘가에 등록되어 있는지 검증(드레인 경로 검증).
// 10. Command Handler가 OutboxRelay/OutboxPoller/OutboxConsumer를 직접 참조하거나
//     processPending()류를 호출하지 않는지 검증 — Command Service는 저장 후 곧바로
//     반환해야 하며, Outbox 드레인은 독립적으로 주기 실행되는 Poller/Consumer만의
//     책임이다(동기 드레인 금지, issue 대응: 2026-07 async 전환).

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { classifyLayer, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = 'docs/architecture/domain-events.md'

// 매우 단순한 주석 제거. 이 evaluator 전체가 정규식 기반 텍스트 검사를 쓰므로 일관된
// 접근이다 — rule 10(forbidden-sync-drain)이 "왜 OutboxPoller를 호출하면 안 되는지"를
// 설명하는 코드 주석 자체를 위반으로 오탐하는 것을 막기 위해 도입했다. 문자열 리터럴
// 안의 '//' 같은 극단적 엣지 케이스는 감수한다(이 파일의 다른 정규식 규칙들도 동일한
// 수준의 근사치를 이미 쓰고 있다).
function stripComments(content: string): string {
  return content.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
}

function collectDomainEventClassNames(domainFiles: string[]): Set<string> {
  const names = new Set<string>()
  for (const f of domainFiles) {
    const content = fs.readFileSync(f, 'utf-8')
    const regex = /_events\.push\s*\(\s*new\s+(\w+)\s*\(/g
    let m: RegExpExecArray | null
    while ((m = regex.exec(content)) !== null) {
      names.add(m[1])
    }
  }
  return names
}

// src/ 바로 아래 첫 번째 디렉토리를 "도메인"으로 취급한다(예: src/order/domain/order.ts → 'order').
// registry-coverage-incomplete 검사가 어떤 도메인의 이벤트가 등록에서 빠졌는지 특정할 수
// 있도록, 전역 이벤트 집합이 아니라 도메인별로 나눠서 추적한다.
function topDomainSegment(file: string, srcDir: string): string {
  const relPath = path.relative(srcDir, file).replace(/\\/g, '/')
  return relPath.split('/')[0]
}

function collectDomainEventClassNamesByDomain(domainFiles: string[], srcDir: string): Map<string, Set<string>> {
  const byDomain = new Map<string, Set<string>>()
  for (const f of domainFiles) {
    const content = fs.readFileSync(f, 'utf-8')
    const regex = /_events\.push\s*\(\s*new\s+(\w+)\s*\(/g
    let m: RegExpExecArray | null
    while ((m = regex.exec(content)) !== null) {
      const domain = topDomainSegment(f, srcDir)
      if (!byDomain.has(domain)) byDomain.set(domain, new Set())
      byDomain.get(domain)!.add(m[1])
    }
  }
  return byDomain
}

export function evaluateDomainEventOutbox(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []

  const srcDir = path.join(root, 'src')
  const files = walkTsFiles(srcDir)
  const rel = (f: string) => path.relative(root, f)

  const domainFiles = files.filter((f) => classifyLayer(f) === 'domain')
  const aggregatesWithEvents = domainFiles.filter((f) => {
    const c = fs.readFileSync(f, 'utf-8')
    return /\b(?:domainEvents|_events)\b/.test(c) && /\bpush\s*\(\s*new\s+\w+\s*\(/.test(c)
  })

  const hasHandleEvent = files.some((f) => /@HandleEvent\s*\(/.test(fs.readFileSync(f, 'utf-8')))
  const hasHandleIntegrationEvent = files.some((f) => /@HandleIntegrationEvent\s*\(/.test(fs.readFileSync(f, 'utf-8')))
  const hasEventBusPublish = files.some((f) => /\beventBus\s*\.\s*publish\s*\(/.test(fs.readFileSync(f, 'utf-8')))

  if (aggregatesWithEvents.length === 0 && !hasHandleEvent && !hasHandleIntegrationEvent && !hasEventBusPublish) {
    return { name: 'domain-event-outbox', score: 0, maxScore: 0, failures: [] }
  }

  let score = 25

  const outboxDir = path.join(srcDir, 'outbox')
  if (aggregatesWithEvents.length > 0 && !fs.existsSync(outboxDir)) {
    failures.push({
      ruleId: 'domain-event-outbox.module-missing',
      severity: 'high',
      message: `Domain Events(${aggregatesWithEvents.length}개 Aggregate)이 발행되는데 src/outbox/ 공유 모듈 부재 — Outbox 패턴 구성 필요`,
      docRef: DOC_REF
    })
    score -= 6
  }

  const infraFiles = files.filter((f) => classifyLayer(f) === 'infrastructure')
  const repoImpls = infraFiles.filter((f) => /-repository-impl\.ts$/.test(path.basename(f)))

  if (aggregatesWithEvents.length > 0) {
    if (repoImpls.length > 0) {
      const anyUsesOutbox = repoImpls.some((f) => {
        const c = fs.readFileSync(f, 'utf-8')
        return /\bOutboxWriter\b/.test(c)
          || /outbox[A-Za-z]*\.saveAll\s*\(/.test(c)
          || /\bdomainEvents\b[\s\S]*\boutbox\b/i.test(c)
      })
      if (!anyUsesOutbox) {
        failures.push({
          ruleId: 'domain-event-outbox.repository-does-not-persist-events',
          severity: 'high',
          message: `Repository 구현체가 OutboxWriter/outbox saveAll 패턴을 사용하지 않음 — Aggregate가 발행한 도메인 이벤트가 트랜잭션으로 저장되지 않을 위험`,
          docRef: DOC_REF
        })
        score -= 5
      }
    } else {
      failures.push({
        ruleId: 'domain-event-outbox.repository-impl-missing',
        severity: 'medium',
        message: `Domain Events가 발행되는데 Repository 구현체(-repository-impl.ts)를 찾지 못함`,
        docRef: DOC_REF
      })
      score -= 3
    }

    const clearEventsCalled = infraFiles.some((f) => /\bclearEvents\s*\(\s*\)/.test(fs.readFileSync(f, 'utf-8')))
    if (!clearEventsCalled) {
      failures.push({
        ruleId: 'domain-event-outbox.clear-events-missing',
        severity: 'low',
        message: `Repository 구현체에서 Aggregate.clearEvents() 호출 흔적 없음 — 이벤트 중복 발행 방지 관례 확인 권장`,
        docRef: DOC_REF
      })
      score -= 1
    }
  }

  const eventClassNames = collectDomainEventClassNames(domainFiles)
  const applicationFiles = files.filter((f) => classifyLayer(f) === 'application')

  for (const f of applicationFiles) {
    const content = fs.readFileSync(f, 'utf-8')
    for (const name of eventClassNames) {
      const pattern = new RegExp(`\\bnew\\s+${name}\\s*\\(`)
      if (pattern.test(content)) {
        failures.push({
          ruleId: 'domain-event-outbox.command-service.event-construction',
          severity: 'high',
          message: `Application 레이어가 도메인 이벤트(${name})를 직접 생성: ${rel(f)} — 이벤트는 Aggregate 내부 도메인 메서드에서만 생성`,
          docRef: DOC_REF
        })
        score -= 4
        break
      }
    }
  }

  for (const f of applicationFiles) {
    const normalized = f.replace(/\\/g, '/')
    if (normalized.includes('/application/event/')) continue
    const content = fs.readFileSync(f, 'utf-8')
    if (/\bOutboxWriter\b/.test(content)) {
      failures.push({
        ruleId: 'domain-event-outbox.command-service.outbox-writer-injection',
        severity: 'high',
        message: `Application 레이어(application/event/ 외)가 OutboxWriter를 참조: ${rel(f)} — outbox는 Repository 구현체 또는 application/event/ EventHandler에서만 사용`,
        docRef: DOC_REF
      })
      score -= 4
    }
  }

  for (const f of files) {
    const content = fs.readFileSync(f, 'utf-8')
    if (!/@HandleEvent\s*\(/.test(content)) continue
    const normalized = f.replace(/\\/g, '/')
    const inEventDir = normalized.includes('/application/event/')
    const correctSuffix = /-handler\.ts$/.test(path.basename(f))
    if (!inEventDir || !correctSuffix) {
      failures.push({
        ruleId: 'domain-event-outbox.handler.layer',
        severity: 'medium',
        message: `@HandleEvent 보유 파일이 application/event/<domain-event>-handler.ts 경로를 따르지 않음: ${rel(f)}`,
        docRef: DOC_REF
      })
      score -= 2
    }
  }

  for (const f of files) {
    const content = fs.readFileSync(f, 'utf-8')
    if (!/@HandleIntegrationEvent\s*\(/.test(content)) continue
    const normalized = f.replace(/\\/g, '/')
    const inDir = normalized.includes('/interface/integration-event/')
    const correctSuffix = /-integration-event-controller\.ts$/.test(path.basename(f))
    if (!inDir || !correctSuffix) {
      failures.push({
        ruleId: 'domain-event-outbox.integration-event.controller.layer',
        severity: 'medium',
        message: `@HandleIntegrationEvent 보유 파일이 interface/integration-event/<domain>-integration-event-controller.ts 경로를 따르지 않음: ${rel(f)}`,
        docRef: DOC_REF
      })
      score -= 2
    }
  }

  for (const f of files) {
    const content = fs.readFileSync(f, 'utf-8')
    if (/\beventBus\s*\.\s*publish\s*\(/.test(content)) {
      failures.push({
        ruleId: 'domain-event-outbox.event-bus.direct-publish',
        severity: 'high',
        message: `EventBus.publish() 직접 호출: ${rel(f)} — @nestjs/cqrs 사용 중에도 Outbox → SQS 경로를 따라야 함`,
        docRef: DOC_REF
      })
      score -= 4
    }
  }

  // 9. OutboxPoller/OutboxConsumer가 존재하고, 발행되는 모든 Domain Event 타입이
  //    EventHandlerRegistry.register(...)로 어딘가에 등록되어 있는지 검증. 이전까지의
  //    규칙들은 write(적재) 경로만 봤을 뿐 드레인 경로는 전혀 검증하지 않았다 —
  //    등록 하나가 빠져도(예: MoneyDeposited) 그 이벤트는 Outbox 테이블에 영원히
  //    processed=false로 남아 조용히 드레인되지 않는다.
  //
  //    과거에는 도메인마다 전용 OutboxRelay(*outbox-relay.ts)가 생성자 주입 고정 맵으로
  //    이 라우팅을 담당했지만(issue #229), 동기 드레인을 전면 제거하면서 Poller(DB→큐
  //    발행만 담당)와 Consumer(큐→핸들러 라우팅)로 역할이 분리되고, 라우팅 자체는 도메인
  //    별 relay 파일이 아니라 하나의 공유 EventHandlerRegistry로 통합됐다 — 검사도
  //    "relay 파일이 존재하는가"가 아니라 "문자열 키로 register() 등록됐는가"를 본다.
  if (aggregatesWithEvents.length > 0) {
    const hasPoller = files.some((f) => /outbox-poller\.ts$/.test(path.basename(f)))
    const hasConsumer = files.some((f) => /outbox-consumer\.ts$/.test(path.basename(f)))
    if (!hasPoller) {
      failures.push({
        ruleId: 'domain-event-outbox.poller-missing',
        severity: 'high',
        message: 'Domain Event가 발행되는데 outbox-poller.ts를 찾지 못함 — outbox 테이블에 적재된 이벤트를 큐로 발행할 경로가 없음',
        docRef: DOC_REF
      })
      score -= 5
    }
    if (!hasConsumer) {
      failures.push({
        ruleId: 'domain-event-outbox.consumer-missing',
        severity: 'high',
        message: 'Domain Event가 발행되는데 outbox-consumer.ts를 찾지 못함 — 큐에서 수신해 EventHandler를 호출할 경로가 없음',
        docRef: DOC_REF
      })
      score -= 5
    }

    if (eventClassNames.size > 0) {
      const registerCalls = files.map((f) => fs.readFileSync(f, 'utf-8')).join('\n')
      const eventsByDomain = collectDomainEventClassNamesByDomain(domainFiles, srcDir)
      for (const [domain, domainEvents] of eventsByDomain) {
        const missing = [...domainEvents].filter(
          (name) => !new RegExp(`\\bregister\\s*\\(\\s*['"\`]${name}['"\`]`).test(registerCalls)
        )
        if (missing.length > 0) {
          failures.push({
            ruleId: 'domain-event-outbox.registry-coverage-incomplete',
            severity: 'high',
            message: `${domain} 도메인의 다음 Domain Event가 EventHandlerRegistry.register(...)로 등록되지 않음: ${missing.join(', ')} — 해당 이벤트는 Outbox에 적재되어도 OutboxConsumer가 처리할 핸들러를 찾지 못함`,
            docRef: DOC_REF
          })
          score -= 4
        }
      }
    }
  }

  // 10. Command Handler가 OutboxRelay/OutboxPoller/OutboxConsumer를 직접 참조하거나
  //     processPending()류를 호출하지 않는지 검증(예전 규칙의 정반대) — 동기 드레인을
  //     전면 제거한 뒤에는 Command Service가 저장 후 곧바로 반환해야 하며, Outbox 드레인은
  //     독립적으로 주기 실행되는 Poller/Consumer만의 책임이다. 이 검사가 없으면 누군가
  //     예전 습관대로 Command Handler에 드레인 호출을 다시 추가해도 잡아내지 못한다.
  const commandHandlerFiles = files.filter(
    (f) => classifyLayer(f) === 'application' && /-command-handler\.ts$/.test(path.basename(f))
  )
  for (const f of commandHandlerFiles) {
    const content = stripComments(fs.readFileSync(f, 'utf-8'))
    const forbiddenSymbol = /\bOutboxRelay\b|\bOutboxPoller\b|\bOutboxConsumer\b/.exec(content)
    const forbiddenCall = /\.\s*(?:processPending|poll|drainOnce)\s*\(/.exec(content)
    if (forbiddenSymbol || forbiddenCall) {
      failures.push({
        ruleId: 'domain-event-outbox.command-handler.forbidden-sync-drain',
        severity: 'high',
        message: `${rel(f)}가 OutboxRelay/OutboxPoller/OutboxConsumer를 직접 참조하거나 드레인을 호출함 — Command Handler는 저장 후 곧바로 반환해야 하며, Outbox → 큐 발행/수신은 독립적으로 주기 실행되는 Poller/Consumer만의 책임이다(동기 드레인 금지)`,
        docRef: DOC_REF
      })
      score -= 6
    }
  }

  return { name: 'domain-event-outbox', score: Math.max(score, 0), maxScore: 25, failures }
}
