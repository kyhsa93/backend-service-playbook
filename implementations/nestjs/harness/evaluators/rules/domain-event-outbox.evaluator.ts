// The domain-event-outbox evaluator — verifies that when an Aggregate publishes a domain
// event, it follows the guide's Outbox pattern + respects the Integration Event boundary
// (guide: docs/architecture/domain-events.md).
//
// Applicability gate: runs if any of the following exist — skipped otherwise (maxScore=0).
//   - the `_events.push(new XxxEvent(` pattern in a domain/ layer Aggregate
//   - `@HandleEvent(` / `@HandleIntegrationEvent(` used anywhere in the codebase
//   - an `eventBus.publish(` call anywhere in the codebase
//
// Rules:
// 1. The src/outbox/ module exists.
// 2. A Repository implementation uses the OutboxWriter / saveAll(outbox) pattern.
// 3. There's a trace of a clearEvents() call in a Repository implementation.
// 4. The Application layer never `new`s a domain event object directly
//    (an event is created only inside an Aggregate's domain method).
// 5. The Application layer's OutboxWriter reference is only allowed from an
//    `application/event/` EventHandler (prohibited in other application subdirectories such as the Command Service).
// 6. A file with @HandleEvent is located at application/event/<domain-event>-handler.ts.
// 7. A file with @HandleIntegrationEvent is located at interface/integration-event/<domain>-integration-event-controller.ts.
// 8. Calling EventBus.publish() directly is prohibited — the Outbox path must be followed even when using @nestjs/cqrs.
// 9. OutboxPoller/OutboxConsumer exist, and verify that every published Domain Event type is
//    registered somewhere via EventHandlerRegistry.register(...) (verifying the drain path).
// 10. Verify that a Command Handler doesn't directly reference OutboxRelay/OutboxPoller/OutboxConsumer
//     or call something like processPending() — the Command Service must return immediately
//     after saving, and draining the Outbox is the sole responsibility of the independently,
//     periodically running Poller/Consumer (synchronous draining is prohibited).

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { classifyLayer, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = 'docs/architecture/domain-events.md'

// A very simple comment stripper. Consistent with this evaluator entirely using
// regex-based text checks — introduced so rule 10 (forbidden-sync-drain) doesn't false-positive
// on a code comment that itself explains "why you shouldn't call OutboxPoller." Extreme edge
// cases like a '//' inside a string literal are accepted as a trade-off (the other regex rules
// in this file already use the same level of approximation).
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

// Treats the first directory directly under src/ as the "domain" (e.g. src/order/domain/order.ts → 'order').
// Tracked per-domain rather than as a single global event set, so the registry-coverage-incomplete
// check can pinpoint which domain's event is missing from registration.
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

  // 9. Verify that OutboxPoller/OutboxConsumer exist, and that every published Domain Event
  //    type is registered somewhere via EventHandlerRegistry.register(...). The rules above
  //    only looked at the write (enqueue) path and never verified the drain path at all —
  //    even a single missing registration (e.g. MoneyDeposited) leaves that event permanently
  //    stuck at processed=false in the Outbox table, silently never draining.
  //
  //    Routing is unified into a single shared EventHandlerRegistry rather than a per-domain
  //    relay file — since the roles are split between the Poller (handles only DB→queue
  //    publishing) and the Consumer (queue→handler routing), the check likewise looks at
  //    "was it registered via register() under a string key," not "does a relay file exist."
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

  // 10. Verify that a Command Handler never directly references
  //     OutboxRelay/OutboxPoller/OutboxConsumer or calls something like processPending() —
  //     the Command Service must return immediately after saving, and draining the Outbox is
  //     the sole responsibility of the independently, periodically running Poller/Consumer.
  //     Without this check, nothing would catch it if someone added a drain call back into a
  //     Command Handler out of an old habit.
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
