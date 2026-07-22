// scheduler evaluator — AST-based enforcement for @Cron/@Interval Scheduler files.
//
// Rules:
// - Scheduler with @Cron or @Interval lives in infrastructure/ (exception:
//   task-queue/ & outbox/ framework-internal files) — scheduling.md: even "a single plain
//   @Cron" is a valid choice, but its placement is always Infrastructure. The same principle
//   applies to @Interval for the same reason (across multiple instances, the timer behaves as
//   the framework scheduler) (the "Interval / Timeout" section).
// - Every @Cron/@Interval method body has try-catch or uses runSafely() helper.
// - Domain Scheduler must NOT inject Repository/DataSource/CommandService
//   (should only depend on TaskQueue abstract).
//
// Applicability: if no @Cron/@Interval decorators exist in src/, evaluator is skipped.

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import {
  classifyLayer,
  listConstructorParams,
  listMethodDecorators,
  walkTsFiles
} from '../shared/ast-utils'

function isFrameworkInternal(filePath: string): boolean {
  const normalized = filePath.replace(/\\/g, '/')
  return normalized.includes('/src/task-queue/') || normalized.includes('/src/outbox/')
}

export function evaluateScheduler(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []

  const srcDir = path.join(root, 'src')
  const files = walkTsFiles(srcDir)
  const rel = (f: string) => path.relative(root, f)

  // Applicability gate
  const anyCron = files.some((f) => /@Cron\s*\(|@Interval\s*\(/.test(fs.readFileSync(f, 'utf-8')))
  if (!anyCron) {
    return { name: 'scheduler', score: 0, maxScore: 0, failures: [] }
  }

  let score = 15

  for (const file of files) {
    const methods = listMethodDecorators(file)
    const cronMethods = methods.filter((m) => m.decorators.some((d) => d.name === 'Cron' || d.name === 'Interval'))
    if (cronMethods.length === 0) continue

    const layer = classifyLayer(file)
    const frameworkInternal = isFrameworkInternal(file)

    // Rule 1: the Scheduler is located in the infrastructure/ layer (framework-internal is an exception)
    if (!frameworkInternal && layer !== 'infrastructure') {
      failures.push({
        ruleId: 'scheduler.layer',
        severity: 'high',
        message: `A Scheduler using @Cron/@Interval is located outside infrastructure/, in the ${layer} layer: ${rel(file)}`,
        docRef: 'docs/architecture/scheduling.md#scheduler--cron--taskqueue'
      })
      score -= 4
    }

    // Rule 1b: the Scheduler file-name suffix convention (framework-internal excluded)
    if (!frameworkInternal && !/-scheduler\.ts$/.test(path.basename(file))) {
      failures.push({
        ruleId: 'scheduler.file-suffix',
        severity: 'medium',
        message: `An Infrastructure file with @Cron/@Interval must follow the *-scheduler.ts naming format: ${rel(file)}`
      })
      score -= 2
    }

    // Rule 2: each @Cron/@Interval method must be wrapped in try-catch or use runSafely
    for (const m of cronMethods) {
      const hasTry = /\btry\s*\{/.test(m.body)
      const hasCatch = /\bcatch\s*\(/.test(m.body)
      const usesRunSafely = /\brunSafely\s*\(/.test(m.body)
      if (!(hasTry && hasCatch) && !usesRunSafely) {
        failures.push({
          ruleId: 'scheduler.cron.try-catch',
          severity: 'medium',
          message: `Cron/Interval method ${m.methodName} has no try-catch (or runSafely helper): ${rel(file)} — @nestjs/schedule silently swallows exceptions`,
          docRef: 'docs/architecture/scheduling.md#scheduler--cron--taskqueue'
        })
        score -= 2
      }
    }

    // Rule 3: a domain Scheduler only calls TaskQueue.enqueue (injecting business dependencies is prohibited)
    if (!frameworkInternal) {
      const params = listConstructorParams(file)
      if (params.some((p) => /\bRepository<.+>/.test(p.typeText))) {
        failures.push({
          ruleId: 'scheduler.no-repository-injection',
          severity: 'high',
          message: `The Scheduler injects Repository<Entity> (suspected of containing business logic): ${rel(file)} — delegate it to TaskQueue`
        })
        score -= 3
      }
      if (params.some((p) => /\bDataSource\b/.test(p.typeText))) {
        failures.push({
          ruleId: 'scheduler.no-datasource-injection',
          severity: 'high',
          message: `The Scheduler injects DataSource: ${rel(file)} — a Scheduler must only call TaskQueue.enqueue`
        })
        score -= 3
      }
      if (params.some((p) => /CommandService\b/.test(p.typeText))) {
        failures.push({
          ruleId: 'scheduler.no-command-service-injection',
          severity: 'medium',
          message: `The Scheduler injects CommandService: ${rel(file)} — running business logic is the Task Controller's responsibility`
        })
        score -= 2
      }
    }
  }

  return { name: 'scheduler', score: Math.max(score, 0), maxScore: 15, failures }
}
