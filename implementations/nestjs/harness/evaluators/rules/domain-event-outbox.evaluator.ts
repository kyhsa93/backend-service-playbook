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
      message: `Domain Events are published (${aggregatesWithEvents.length} Aggregates) but the shared src/outbox/ module is missing — the Outbox pattern must be set up`,
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
          message: `The Repository implementation does not use the OutboxWriter/outbox saveAll pattern — the domain events published by the Aggregate risk not being saved transactionally`,
          docRef: DOC_REF
        })
        score -= 5
      }
    } else {
      failures.push({
        ruleId: 'domain-event-outbox.repository-impl-missing',
        severity: 'medium',
        message: `Domain Events are published but no Repository implementation (-repository-impl.ts) was found`,
        docRef: DOC_REF
      })
      score -= 3
    }

    const clearEventsCalled = infraFiles.some((f) => /\bclearEvents\s*\(\s*\)/.test(fs.readFileSync(f, 'utf-8')))
    if (!clearEventsCalled) {
      failures.push({
        ruleId: 'domain-event-outbox.clear-events-missing',
        severity: 'low',
        message: `No trace of an Aggregate.clearEvents() call in the Repository implementation — recommend checking the convention that prevents duplicate event publication`,
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
          message: `The Application layer creates the domain event (${name}) directly: ${rel(f)} — events must only be created inside an Aggregate's domain methods`,
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
        message: `The Application layer (outside application/event/) references OutboxWriter: ${rel(f)} — outbox may only be used by a Repository implementation or an application/event/ EventHandler`,
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
        message: `The file with @HandleEvent does not follow the application/event/<domain-event>-handler.ts path convention: ${rel(f)}`,
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
        message: `The file with @HandleIntegrationEvent does not follow the interface/integration-event/<domain>-integration-event-controller.ts path convention: ${rel(f)}`,
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
        message: `Direct call to EventBus.publish(): ${rel(f)} — even when using @nestjs/cqrs, it must go through the Outbox -> SQS path`,
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
        message: 'A Domain Event is published but outbox-poller.ts was not found — there is no path to publish events queued in the outbox table to the queue',
        docRef: DOC_REF
      })
      score -= 5
    }
    if (!hasConsumer) {
      failures.push({
        ruleId: 'domain-event-outbox.consumer-missing',
        severity: 'high',
        message: 'A Domain Event is published but outbox-consumer.ts was not found — there is no path to receive from the queue and call an EventHandler',
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
            message: `The following Domain Events in the ${domain} domain are not registered via EventHandlerRegistry.register(...): ${missing.join(', ')} — even if queued in the Outbox, OutboxConsumer will find no handler to process them`,
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
        message: `${rel(f)} references OutboxRelay/OutboxPoller/OutboxConsumer directly or calls a drain — a Command Handler must return right after saving, and Outbox -> queue publish/receive is solely the responsibility of the independently-scheduled Poller/Consumer (synchronous draining is forbidden)`,
        docRef: DOC_REF
      })
      score -= 6
    }
  }

  return { name: 'domain-event-outbox', score: Math.max(score, 0), maxScore: 25, failures }
}
