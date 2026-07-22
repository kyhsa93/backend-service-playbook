// Harness CLI

import * as fs from 'node:fs'
import * as path from 'node:path'

import { evaluateLayerDependency } from '../rules/layer-dependency.evaluator'
import { evaluateRepositoryPattern } from '../rules/repository-pattern.evaluator'
import { evaluateRepositoryNaming } from '../rules/repository-naming.evaluator'
import { evaluateControllerPath } from '../rules/controller-path.evaluator'
import { evaluateChecklist } from '../rules/checklist.evaluator'
import { evaluateStructure } from '../rules/structure.evaluator'
import { evaluateFileNaming } from '../rules/file-naming.evaluator'
import { evaluateCqrsPattern } from '../rules/cqrs-pattern.evaluator'
import { evaluateErrorHandling } from '../rules/error-handling.evaluator'
import { evaluateTestPresence } from '../rules/test-presence.evaluator'
import { evaluateDtoValidation } from '../rules/dto-validation.evaluator'
import { evaluateTaskQueue } from '../rules/task-queue.evaluator'
import { evaluateScheduler } from '../rules/scheduler.evaluator'
import { evaluateDeprecatedApi } from '../rules/deprecated-api.evaluator'
import { evaluateModuleDI } from '../rules/module-di.ast.evaluator'
import { evaluateImportGraph } from '../rules/import-graph.evaluator'
import { evaluateDomainEventOutbox } from '../rules/domain-event-outbox.evaluator'
import { evaluateBuild } from '../rules/build.evaluator'
import { evaluateTestRun } from '../rules/test-run.evaluator'
import { evaluateSecretManager } from '../rules/secret-manager.evaluator'
import { evaluateConfigValidation } from '../rules/config-validation.evaluator'
import { evaluateLogging } from '../rules/logging.evaluator'
import { evaluateAuth } from '../rules/auth.evaluator'
import { evaluateBootstrapHealthcheck } from '../rules/bootstrap-healthcheck.evaluator'
import { evaluateE2eQuality } from '../rules/e2e-quality.evaluator'
import { evaluateDockerfile } from '../rules/dockerfile.evaluator'
import { evaluateLocalDev } from '../rules/local-dev.evaluator'
import { evaluateRateLimiting } from '../rules/rate-limiting.evaluator'
import { evaluatePagination } from '../rules/pagination.evaluator'
import { evaluateDatabaseQueries } from '../rules/database-queries.evaluator'
import { evaluateDomainService } from '../rules/domain-service.evaluator'
import { evaluateAggregateId } from '../rules/aggregate-id.evaluator'
import { evaluateDomainLayerIsolation } from '../rules/domain-layer-isolation.evaluator'
import { evaluateInterfaceNoInfrastructure } from '../rules/interface-no-infrastructure.evaluator'
import { evaluateAggregateNoPublicSetters } from '../rules/aggregate-no-public-setters.evaluator'
import { evaluateNoCrossAggregateReference } from '../rules/no-cross-aggregate-reference.evaluator'
import { evaluateNoCrossBcRepositoryInApplication } from '../rules/no-cross-bc-repository-in-application.evaluator'
import { evaluateSoftDeleteFilter } from '../rules/soft-delete-filter.evaluator'
import { evaluateNoGenericResponseKeys } from '../rules/no-generic-response-keys.evaluator'
import { evaluateQueryHandlerNoRawAggregate } from '../rules/query-handler-no-raw-aggregate.evaluator'
import { evaluateNoCrossBcDomainImport } from '../rules/no-cross-bc-domain-import.evaluator'
import { evaluateNoOrmAutosyncInProdConfig } from '../rules/no-orm-autosync-in-prod-config.evaluator'
import { evaluateApiDocumentation } from '../rules/api-documentation.evaluator'
import { evaluateUserContextStore } from '../rules/user-context-store.evaluator'
import { aggregate } from '../shared/score'
import type { EvaluatorResult } from '../shared/types'

type EvaluatorFn = (root: string) => EvaluatorResult

const EVALUATORS: Record<string, EvaluatorFn> = {
  structure: evaluateStructure,
  'file-naming': evaluateFileNaming,
  'layer-dependency': evaluateLayerDependency,
  'repository-pattern': evaluateRepositoryPattern,
  'repository-naming': evaluateRepositoryNaming,
  'controller-path': evaluateControllerPath,
  checklist: evaluateChecklist,
  'cqrs-pattern': evaluateCqrsPattern,
  'error-handling': evaluateErrorHandling,
  'test-presence': evaluateTestPresence,
  'dto-validation': evaluateDtoValidation,
  'task-queue': evaluateTaskQueue,
  scheduler: evaluateScheduler,
  'deprecated-api': evaluateDeprecatedApi,
  'module-di-ast': evaluateModuleDI,
  'import-graph': evaluateImportGraph,
  'domain-event-outbox': evaluateDomainEventOutbox,
  build: evaluateBuild,
  'test-run': evaluateTestRun,
  'secret-manager': evaluateSecretManager,
  'config-validation': evaluateConfigValidation,
  logging: evaluateLogging,
  auth: evaluateAuth,
  'bootstrap-healthcheck': evaluateBootstrapHealthcheck,
  'e2e-quality': evaluateE2eQuality,
  dockerfile: evaluateDockerfile,
  'local-dev': evaluateLocalDev,
  'rate-limiting': evaluateRateLimiting,
  pagination: evaluatePagination,
  'database-queries': evaluateDatabaseQueries,
  'domain-service': evaluateDomainService,
  'aggregate-id': evaluateAggregateId,
  'domain-layer-isolation': evaluateDomainLayerIsolation,
  'interface-no-infrastructure': evaluateInterfaceNoInfrastructure,
  'aggregate-no-public-setters': evaluateAggregateNoPublicSetters,
  'no-cross-aggregate-reference': evaluateNoCrossAggregateReference,
  'no-cross-bc-repository-in-application': evaluateNoCrossBcRepositoryInApplication,
  'soft-delete-filter': evaluateSoftDeleteFilter,
  'no-generic-response-keys': evaluateNoGenericResponseKeys,
  'query-handler-no-raw-aggregate': evaluateQueryHandlerNoRawAggregate,
  'no-cross-bc-domain-import': evaluateNoCrossBcDomainImport,
  'no-orm-autosync-in-prod-config': evaluateNoOrmAutosyncInProdConfig,
  'api-documentation': evaluateApiDocumentation,
  'user-context-store': evaluateUserContextStore
}

interface CliArgs {
  projectRoot: string
  only: string[] | null
  out: string | null
}

function parseArgs(argv: string[]): CliArgs {
  let projectRoot: string | null = null
  let only: string[] | null = null
  let out: string | null = null

  for (const arg of argv) {
    if (arg.startsWith('--only=')) {
      only = arg.slice('--only='.length).split(',').map((s) => s.trim()).filter(Boolean)
    } else if (arg.startsWith('--out=')) {
      out = arg.slice('--out='.length)
    } else if (!arg.startsWith('--')) {
      projectRoot = arg
    }
  }

  if (!projectRoot) {
    console.error('Usage: npm run evaluate -- <projectRoot> [--only=a,b,c] [--out=report.json]')
    process.exit(1)
  }

  return { projectRoot, only, out }
}

function gradeFor(total: number): string {
  if (total >= 90) return 'A'
  if (total >= 80) return 'B'
  if (total >= 70) return 'C'
  if (total >= 60) return 'D'
  return 'F'
}

function main(): void {
  const { projectRoot, only, out } = parseArgs(process.argv.slice(2))
  const absRoot = path.resolve(projectRoot)

  if (!fs.existsSync(absRoot)) {
    console.error(`projectRoot not found: ${absRoot}`)
    process.exit(1)
  }

  const names = only ?? Object.keys(EVALUATORS)
  const unknown = names.filter((name) => !(name in EVALUATORS))
  if (unknown.length > 0) {
    console.error(`Unknown evaluator(s): ${unknown.join(', ')}`)
    console.error(`Available: ${Object.keys(EVALUATORS).join(', ')}`)
    process.exit(1)
  }

  const results: EvaluatorResult[] = names.map((name) => EVALUATORS[name](absRoot))
  const report = aggregate(results)

  const output = {
    projectRoot: absRoot,
    totalScore: report.total,
    grade: gradeFor(report.total),
    rawScore: report.rawScore,
    rawMax: report.rawMax,
    runEvaluators: names,
    skippedEvaluators: report.skippedEvaluators,
    failures: report.failures
  }

  const json = JSON.stringify(output, null, 2)

  if (out) {
    fs.writeFileSync(out, json)
    console.log(`Report written to ${out}`)
  } else {
    console.log(json)
  }

  console.log(
    `\n${output.grade} (${output.totalScore}/100, raw ${output.rawScore}/${output.rawMax}) — `
    + `${output.failures.length} failure(s) across ${names.length} evaluator(s), `
    + `${output.skippedEvaluators.length} skipped (not applicable)`
  )

  // 'low'-severity entries are informational (e.g. checklist.meta.coverage, test-run.skipped)
  // and present on every run regardless of code quality — only a medium/high/critical finding
  // should fail the build, matching every other language's harness in this repo (they all
  // exit non-zero on any FAIL; this CLI has no PASS/FAIL binary, so severity stands in for it).
  const blocking = report.failures.filter((f) => f.severity !== 'low')
  if (blocking.length > 0) {
    process.exit(1)
  }
}

main()
