// task-queue evaluator — enforces guide rules for Task Queue subsystem.
// AST-based (listMethodDecorators, listConstructorParams, findClassDecorator).
//
// Rules:
// - Task Controller (@TaskConsumer holder) lives in interface/ layer.
// - Task Controller injects CommandService; not DataSource/Repository/TaskExecutionLog.
// - Task Controller body does not call generateErrorResponse (meaningless in a Task context).
// - taskType passed to @TaskConsumer is globally unique across the codebase.
// - If @Cron or @TaskConsumer is used, AppModule imports ScheduleModule/TaskQueueModule.
//
// Applicability: if neither @TaskConsumer nor @Cron are present anywhere in
// src/, the evaluator is skipped (maxScore = 0) so aggregate() excludes it
// from grade normalization.

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import {
  classifyLayer,
  findClassDecorator,
  listConstructorParams,
  listMethodDecorators,
  walkTsFiles
} from '../shared/ast-utils'

function extractTaskTypeArg(argsText: string): string | null {
  // @TaskConsumer('order.archive', { ... }) → first string arg
  const m = argsText.match(/^\s*['"`]([^'"`]+)['"`]/)
  return m ? m[1] : null
}

function findAppModuleFile(files: string[]): string | null {
  // Prefer a file whose class declaration carries @Module and whose basename
  // matches app[-.]module.ts, then fall back to any @Module file with
  // ScheduleModule.forRoot() or *Module named AppModule.
  const candidates = files.filter((f) => /app[-.]?module\.ts$/i.test(path.basename(f)))
  for (const c of candidates) {
    if (findClassDecorator(c, 'Module')) return c
  }
  // Fallback: any file with @Module declaring an AppModule class
  for (const f of files) {
    const content = fs.readFileSync(f, 'utf-8')
    if (findClassDecorator(f, 'Module') && /class\s+AppModule\b/.test(content)) return f
  }
  return null
}

export function evaluateTaskQueue(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []

  const srcDir = path.join(root, 'src')
  const files = walkTsFiles(srcDir)
  const rel = (f: string) => path.relative(root, f)

  // Applicability gate
  const hasTaskQueueUsage = files.some((f) => {
    const content = fs.readFileSync(f, 'utf-8')
    return /@TaskConsumer\s*\(/.test(content) || /@Cron\s*\(/.test(content)
  })
  if (!hasTaskQueueUsage) {
    return { name: 'task-queue', score: 0, maxScore: 0, failures: [] }
  }

  let score = 20
  const taskTypesSeen = new Map<string, string[]>()
  let anyTaskConsumer = false
  let anyCron = false

  for (const file of files) {
    const content = fs.readFileSync(file, 'utf-8')
    const methods = listMethodDecorators(file)
    const fileHasTaskConsumer = methods.some((m) => m.decorators.some((d) => d.name === 'TaskConsumer'))
    const fileHasCron = methods.some((m) => m.decorators.some((d) => d.name === 'Cron'))
    if (fileHasTaskConsumer) anyTaskConsumer = true
    if (fileHasCron) anyCron = true

    // Collect taskType args from @TaskConsumer decorators
    for (const m of methods) {
      for (const d of m.decorators) {
        if (d.name !== 'TaskConsumer') continue
        const tt = extractTaskTypeArg(d.argsText)
        if (!tt) continue
        const list = taskTypesSeen.get(tt) ?? []
        list.push(rel(file))
        taskTypesSeen.set(tt, list)
      }
    }

    const layer = classifyLayer(file)

    // Rule 1: Task Controller in interface/
    if (fileHasTaskConsumer && layer !== 'interface') {
      failures.push({
        ruleId: 'task-queue.controller.layer',
        severity: 'high',
        message: `The Task Controller (has @TaskConsumer) is located outside interface/, in the ${layer} layer: ${rel(file)}`,
        docRef: 'docs/architecture/scheduling.md#taskcontroller--executing-commands-with-taskconsumer-methods-interface-layer'
      })
      score -= 5
    }

    // Rule 1b: the Task Controller file-name suffix convention
    if (fileHasTaskConsumer && !/-task-controller\.ts$/.test(path.basename(file))) {
      failures.push({
        ruleId: 'task-queue.controller.file-suffix',
        severity: 'medium',
        message: `A file with @TaskConsumer must follow the *-task-controller.ts naming format: ${rel(file)}`,
        docRef: 'docs/architecture/scheduling.md#layer-placement'
      })
      score -= 2
    }

    // Rules 2-4: Task Controller injection/error-handling constraints
    if (fileHasTaskConsumer) {
      const params = listConstructorParams(file)
      const hasDataSource = params.some((p) => /\bDataSource\b/.test(p.typeText))
      const hasRepository = params.some((p) => /\bRepository<.+>/.test(p.typeText))
      const hasExecLog = params.some((p) => /\bTaskExecutionLog\b/.test(p.typeText))
      // A Service-style domain injects CommandService, while an @nestjs/cqrs-style domain
      // injects CommandBus directly, the same as the HTTP Controller — both satisfy the same
      // "delegate to a Command" constraint (scheduling.md, the TaskController section).
      const hasCommandService = params.some((p) => /CommandService\b|\bCommandBus\b/.test(p.typeText))

      if (hasDataSource) {
        failures.push({
          ruleId: 'task-queue.controller.no-datasource',
          severity: 'high',
          message: `The Task Controller injects DataSource directly: ${rel(file)} (use a CommandService or the idempotencyKey option instead)`,
          docRef: 'docs/architecture/scheduling.md#taskcontroller--executing-commands-with-taskconsumer-methods-interface-layer'
        })
        score -= 4
      }
      if (hasRepository) {
        failures.push({
          ruleId: 'task-queue.controller.no-repository',
          severity: 'high',
          message: `The Task Controller injects Repository<Entity> directly: ${rel(file)}`,
          docRef: 'docs/architecture/scheduling.md#taskcontroller--executing-commands-with-taskconsumer-methods-interface-layer'
        })
        score -= 4
      }

      // A double-ledger check (TaskExecutionLog injected + the idempotencyKey option both at once)
      const hasIdempotencyKeyOption = /idempotencyKey\s*:/.test(content)
      if (hasExecLog && hasIdempotencyKeyOption) {
        failures.push({
          ruleId: 'task-queue.controller.double-ledger-check',
          severity: 'medium',
          message: `The Task Controller both injects TaskExecutionLog and uses the idempotencyKey option: ${rel(file)} — this double-checks. Remove the option for a 3-step pattern, or remove the injection for a 2-step pattern`
        })
        score -= 2
      }

      if (!hasCommandService) {
        failures.push({
          ruleId: 'task-queue.controller.command-service-injection',
          severity: 'medium',
          message: `The Task Controller has no CommandService/CommandBus injected: ${rel(file)}`
        })
        score -= 3
      }

      // Rule: using generateErrorResponse in a Task Controller method is prohibited
      for (const m of methods) {
        if (!m.decorators.some((d) => d.name === 'TaskConsumer')) continue
        if (/\bgenerateErrorResponse\s*\(/.test(m.body)) {
          failures.push({
            ruleId: 'task-queue.controller.no-http-error-response',
            severity: 'high',
            message: `Task Controller method ${m.methodName} calls generateErrorResponse: ${rel(file)} — exceptions must be propagated via throw and delegated to TaskQueueConsumer`
          })
          score -= 4
        }
      }
    }
  }

  // Rule 5: taskType is globally unique
  for (const [taskType, locations] of taskTypesSeen) {
    if (locations.length > 1) {
      failures.push({
        ruleId: 'task-queue.task-type.unique',
        severity: 'critical',
        message: `taskType '${taskType}' is registered redundantly in ${locations.length} places — ${locations.join(', ')}`,
        docRef: 'docs/architecture/scheduling.md#taskconsumer-decorator'
      })
      score -= 6
    }
  }

  // Rule 6: verify ScheduleModule.forRoot() / TaskQueueModule are registered in AppModule
  const appModule = findAppModuleFile(files)
  if (appModule) {
    const appContent = fs.readFileSync(appModule, 'utf-8')
    if (anyCron && !/ScheduleModule\.forRoot\s*\(/.test(appContent)) {
      failures.push({
        ruleId: 'task-queue.app-module.schedule-module',
        severity: 'critical',
        message: `@Cron is used but AppModule has no ScheduleModule.forRoot() registration — the Cron method silently won't run`,
        docRef: 'docs/architecture/scheduling.md#appmodule-configuration'
      })
      score -= 6
    }
    if (anyTaskConsumer && !/TaskQueueModule\b/.test(appContent)) {
      failures.push({
        ruleId: 'task-queue.app-module.task-queue-module',
        severity: 'high',
        message: `@TaskConsumer is used but AppModule has no TaskQueueModule import`,
        docRef: 'docs/architecture/scheduling.md#appmodule-configuration'
      })
      score -= 4
    }
  }

  return { name: 'task-queue', score: Math.max(score, 0), maxScore: 20, failures }
}
