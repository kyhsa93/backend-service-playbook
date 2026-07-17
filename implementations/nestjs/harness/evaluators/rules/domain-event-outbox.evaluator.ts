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
// 9. OutboxRelay의 핸들러 맵이 발행되는 모든 Domain Event 타입을 커버(드레인 경로 검증).
// 10. OutboxRelay를 참조하는 Command Handler가 save 이후 processPending()을 호출(순서 포함).

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { classifyLayer, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = 'docs/architecture/domain-events.md'

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
// OutboxRelay는 도메인마다 하나씩 두고 자기 도메인이 발행하는 이벤트만 드레인하는 게 이
// 저장소의 실제 컨벤션이라(account/application/event/outbox-relay.ts는 Account 이벤트만
// 다룸), relay-handler-map-incomplete 검사는 전역 이벤트 집합이 아니라 도메인별로 좁혀야 한다.
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

  // 9. OutboxRelay의 핸들러 맵이 자기 도메인이 발행하는 모든 Domain Event 타입을
  //    커버하는지 검증. 이전까지의 규칙들은 write(적재) 경로만 봤을 뿐 relay/dispatch
  //    (드레인) 경로는 전혀 검증하지 않았다 — 핸들러 맵에서 항목 하나가 빠져도(예:
  //    MoneyDeposited 삭제) 그 이벤트는 Outbox 테이블에 영원히 processed=false로 남아
  //    조용히 드레인되지 않는다. 도메인마다 자기 이벤트만 처리하는 전용 OutboxRelay를
  //    두는 게 이 저장소의 실제 컨벤션이므로(issue #229), 검사 범위는 도메인별로 좁힌다
  //    — relay 파일이 다른 도메인의 이벤트까지 다뤄야 한다고 요구하지 않는다.
  if (aggregatesWithEvents.length > 0 && eventClassNames.size > 0) {
    const relayFiles = files.filter((f) => /outbox-relay\.ts$/.test(path.basename(f)))
    const eventsByDomain = collectDomainEventClassNamesByDomain(domainFiles, srcDir)

    for (const [domain, domainEvents] of eventsByDomain) {
      const domainRelayFiles = relayFiles.filter((f) => topDomainSegment(f, srcDir) === domain)
      if (domainRelayFiles.length === 0) {
        failures.push({
          ruleId: 'domain-event-outbox.relay-missing',
          severity: 'high',
          message: `${domain} 도메인이 Domain Event(${[...domainEvents].join(', ')})를 발행하는데 해당 도메인의 OutboxRelay(*outbox-relay.ts)를 찾지 못함 — 적재된 이벤트를 드레인할 경로가 없음`,
          docRef: DOC_REF
        })
        score -= 5
        continue
      }
      for (const relayFile of domainRelayFiles) {
        const content = fs.readFileSync(relayFile, 'utf-8')
        const missing = [...domainEvents].filter((name) => !new RegExp(`\\b${name}\\s*:`).test(content))
        if (missing.length > 0) {
          failures.push({
            ruleId: 'domain-event-outbox.relay-handler-map-incomplete',
            severity: 'high',
            message: `${rel(relayFile)}의 핸들러 맵이 다음 Domain Event를 다루지 않음: ${missing.join(', ')} — 해당 이벤트는 Outbox에 적재되어도 영원히 드레인되지 않음`,
            docRef: DOC_REF
          })
          score -= 4
        }
      }
    }
  }

  // 10. Command Handler가 OutboxRelay를 참조한다면, 저장(save) 이후 processPending()을
  //     호출하는지(순서 포함) 검증. 이게 없으면 dual-write 시절 패턴(직접 알림 호출)으로
  //     되돌리거나 processPending() 호출을 지워도 다른 어떤 규칙도 이를 잡지 못했다.
  const commandHandlerFiles = files.filter(
    (f) => classifyLayer(f) === 'application' && /-command-handler\.ts$/.test(path.basename(f))
  )
  for (const f of commandHandlerFiles) {
    const content = fs.readFileSync(f, 'utf-8')
    if (!/\bOutboxRelay\b/.test(content)) continue
    const saveMatch = /\.\w*[Ss]ave\w*\(/.exec(content)
    const ppMatch = /\.processPending\s*\(/.exec(content)
    if (!saveMatch) {
      failures.push({
        ruleId: 'domain-event-outbox.command-handler.save-missing',
        severity: 'medium',
        message: `${rel(f)}가 OutboxRelay를 참조하지만 save 호출을 찾을 수 없음`,
        docRef: DOC_REF
      })
      score -= 2
    } else if (!ppMatch) {
      failures.push({
        ruleId: 'domain-event-outbox.command-handler.process-pending-missing',
        severity: 'high',
        message: `${rel(f)}가 OutboxRelay를 참조하지만 processPending() 호출이 없음 — 저장 직후 Outbox 드레인 누락`,
        docRef: DOC_REF
      })
      score -= 4
    } else if (ppMatch.index < saveMatch.index) {
      failures.push({
        ruleId: 'domain-event-outbox.command-handler.process-pending-order',
        severity: 'high',
        message: `${rel(f)}의 processPending() 호출이 save 호출보다 먼저 등장함 — 커밋 이후 드레인 순서 위반`,
        docRef: DOC_REF
      })
      score -= 4
    }
  }

  return { name: 'domain-event-outbox', score: Math.max(score, 0), maxScore: 25, failures }
}
