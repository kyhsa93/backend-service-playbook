// checklist evaluator — parses docs/checklist.md for STEP structure and runs
// mechanically-verifiable rules mapped to their originating STEP.
//
// Design:
// - Load the checklist document once at startup to discover STEP titles and
//   total item counts. This surfaces a stable ruleId namespace
//   (checklist.step<N>.<slug>) and lets failure messages include the
//   human-readable STEP title.
// - Apply a curated set of pattern-based rules. Concerns already covered by
//   dedicated evaluators (layer-dependency, repository-pattern, error-handling,
//   file-naming, etc.) are intentionally not duplicated here.
// - Report coverage as an informational failure so it's visible how much of
//   the checklist is mechanically enforced.

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'

type Step = { number: number; title: string; itemCount: number }

// The location of docs/checklist.md depends on the execution cwd. Since it's usually run from
// the project root, a few candidate ancestor directories relative to cwd are searched (the
// harness may also be invoked from somewhere like sandbox/).
function locateChecklistDoc(): string | null {
  const candidates = [
    path.resolve(process.cwd(), 'docs/checklist.md'),
    path.resolve(process.cwd(), '../docs/checklist.md'),
    path.resolve(process.cwd(), '../../docs/checklist.md'),
    path.resolve(process.cwd(), '../../../docs/checklist.md')
  ]
  for (const c of candidates) {
    if (fs.existsSync(c)) return c
  }
  return null
}

function walk(dir: string, files: string[] = []): string[] {
  if (!fs.existsSync(dir)) return files
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(full, files)
    else if (full.endsWith('.ts')) files.push(full)
  }
  return files
}

function parseChecklistSteps(): Step[] {
  const docPath = locateChecklistDoc()
  if (!docPath) return []
  const md = fs.readFileSync(docPath, 'utf-8')
  const lines = md.split('\n')

  const steps: Step[] = []
  let current: Step | null = null

  const stepHeader = /^## STEP (\d+)\s*—\s*(.+)$/

  for (const line of lines) {
    const m = line.match(stepHeader)
    if (m) {
      if (current) steps.push(current)
      current = { number: Number(m[1]), title: m[2].trim(), itemCount: 0 }
      continue
    }
    if (current && /^\[ \]/.test(line)) current.itemCount += 1
  }
  if (current) steps.push(current)
  return steps
}

function stepTitle(steps: Step[], n: number): string {
  return steps.find((s) => s.number === n)?.title ?? `STEP ${n}`
}

export function evaluateChecklist(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 100
  const steps = parseChecklistSteps()
  const files = walk(path.join(root, 'src'))

  const rel = (f: string) => path.relative(root, f)
  const push = (ruleId: string, severity: EvaluatorFailure['severity'], message: string, penalty: number) => {
    failures.push({ ruleId, severity, message })
    score -= penalty
  }

  for (const file of files) {
    const content = fs.readFileSync(file, 'utf-8')
    const layer = file.includes('/domain/') ? 'domain'
      : file.includes('/application/') ? 'application'
      : file.includes('/interface/') ? 'interface'
      : file.includes('/infrastructure/') ? 'infrastructure' : 'unknown'

    // STEP 2 — the Domain layer (framework-independent)
    if (layer === 'domain') {
      if (content.includes('@Injectable(')) {
        push('checklist.step2.domain.no-nest-decorator', 'high',
          `${stepTitle(steps, 2)} — Domain uses @Injectable(): ${rel(file)}`, 8)
      }
      if (/from\s+['"]class-validator['"]/.test(content) || /from\s+['"]class-transformer['"]/.test(content)) {
        push('checklist.step2.domain.no-validator-import', 'high',
          `${stepTitle(steps, 2)} — Domain imports class-validator/class-transformer: ${rel(file)}`, 6)
      }
      if (/@Entity\(/.test(content)) {
        push('checklist.step2.domain.no-typeorm-entity', 'high',
          `${stepTitle(steps, 2)} — Domain has an @Entity() decorator (TypeORM leak): ${rel(file)}`, 8)
      }
      if (/\bLogger\b/.test(content) && /from\s+['"]@nestjs\/common['"]/.test(content)) {
        push('checklist.step2.domain.no-logger', 'medium',
          `${stepTitle(steps, 2)} — Domain uses the NestJS Logger (logging belongs in Application): ${rel(file)}`, 4)
      }
    }

    // STEP 3 — the Application layer
    if (layer === 'application') {
      if (content.includes('HttpException')) {
        push('checklist.step3.application.no-http-exception', 'high',
          `${stepTitle(steps, 3)} — Application uses HttpException: ${rel(file)}`, 8)
      }
      if (/from\s+['"]@aws-sdk\//.test(content)) {
        push('checklist.step3.application.no-aws-sdk', 'medium',
          `${stepTitle(steps, 3)} — Application imports the AWS SDK directly: ${rel(file)}`, 5)
      }
      // Application must not directly import a Repository implementation (only the abstract class should be used)
      if (/from\s+['"][^'"]*-repository-impl['"]/.test(content)) {
        push('checklist.step3.application.no-impl-import', 'high',
          `${stepTitle(steps, 3)} — Application imports an -impl directly (must go through the abstract class): ${rel(file)}`, 6)
      }
    }

    // STEP 4 — Infrastructure *-impl.ts misplacement
    if (/-impl\.ts$/.test(file) && !file.includes('/infrastructure/')) {
      push('checklist.step4.impl-outside-infrastructure', 'medium',
        `${stepTitle(steps, 4)} — *-impl.ts is located outside infrastructure/: ${rel(file)}`, 4)
    }

    // STEP 5 — Interface: 1 @Controller per file
    const controllerCount = (content.match(/@Controller\s*\(/g) ?? []).length
    if (controllerCount > 1) {
      push('checklist.step5.interface.single-controller-per-file', 'medium',
        `${stepTitle(steps, 5)} — ${controllerCount} @Controller decorators in a single file: ${rel(file)}`, 3)
    }

    // STEP 5 — a Module file lives at the domain root (must not be inside interface/application/infrastructure)
    if (/-module\.ts$/.test(file) && (layer === 'application' || layer === 'interface' || layer === 'infrastructure')) {
      push('checklist.step5.module-placement', 'medium',
        `${stepTitle(steps, 5)} — the Module file is located inside ${layer}/ (recommended at the domain root): ${rel(file)}`, 3)
    }

    // STEP 12 — an Entity file must be located at infrastructure/entity/
    // However, this rule applies only to an Entity within a domain's 4-layer structure
    // (domain/application/infrastructure/interface). Shared modules like outbox/, task-queue/
    // aren't 4-layer structures to begin with — they're flat packages (see domain-events.md,
    // shared-modules.md) — so their layer is judged 'unknown'. If Entities of such modules
    // (outbox.entity.ts, task-outbox.entity.ts, etc.) were also forced into the
    // infrastructure/ placement requirement, code correctly following the guide would false-positive FAIL.
    // A domain-only Technical Service's Entity (e.g. account's
    // infrastructure/notification/sent-email.entity.ts) is inside the 4-layer structure, so
    // it's not part of this exception and normally has the infrastructure/ placement rule applied.
    if (/\.entity\.ts$/.test(file) && layer !== 'unknown' && !file.includes('/infrastructure/') && !file.includes('/database/')) {
      // base.entity.ts is an exception (database/)
      if (path.basename(file) !== 'base.entity.ts') {
        push('checklist.step12.entity-placement', 'medium',
          `${stepTitle(steps, 12)} — *.entity.ts is located outside infrastructure/: ${rel(file)}`, 3)
      }
    }

    // STEP 3 — using a Repository in the Query Service is prohibited (CQRS — only the Query interface should be used)
    if (/-query-service\.ts$/.test(file) && /\bRepository\b/.test(content) && !/\bQuery\b/.test(content)) {
      push('checklist.step3.query-service-uses-repository', 'medium',
        `${stepTitle(steps, 3)} — the Query Service uses a Repository (must use the Query interface instead): ${rel(file)}`, 4)
    }

    // STEP 11 — an async Task Controller method returns a Promise
    // (skipped — redundant with TypeScript's own type checking)

    // STEP 12 — hardcoding Migration/sync is prohibited (checked against production NODE_ENV)
    if (/synchronize\s*:\s*true(?![^,})]*process\.env)/.test(content)) {
      push('checklist.step12.typeorm-synchronize-unconditional', 'high',
        `${stepTitle(steps, 12)} — TypeORM synchronize: true is set unconditionally (risk of a production incident): ${rel(file)}`, 6)
    }

    // STEP 12 — detecting a hardcoded secret (*.spec.ts is excluded, even if a mock/fixture value looks like a real one)
    if (!file.endsWith('.spec.ts') && /(?:password|secret|apikey|api_key|token)\s*[:=]\s*['"][A-Za-z0-9_-]{8,}['"]/i.test(content)) {
      push('checklist.step12.no-hardcoded-secret', 'critical',
        `${stepTitle(steps, 12)} — suspected hardcoded secret (use process.env instead): ${rel(file)}`, 8)
    }

    // STEP 14 — cleanup (a leftover TODO is prohibited)
    if (/\bTODO\b/.test(content)) {
      push('checklist.step14.no-todo', 'low',
        `${stepTitle(steps, 14)} — a leftover TODO comment remains: ${rel(file)}`, 2)
    }

    // STEP 14 — relative-path imports are prohibited (using ../ — an absolute @/ path is recommended)
    const relImportCount = (content.match(/from\s+['"]\.\.\//g) ?? []).length
    if (relImportCount >= 3) {
      push('checklist.step14.avoid-relative-imports', 'low',
        `${stepTitle(steps, 14)} — ${relImportCount} relative-path ('../') imports (an absolute @/ path is recommended): ${rel(file)}`, 1)
    }
  }

  // Informational: STEP parsing and coverage summary
  if (steps.length > 0) {
    const totalItems = steps.reduce((sum, s) => sum + s.itemCount, 0)
    failures.push({
      ruleId: 'checklist.meta.coverage',
      severity: 'low',
      message: `Parsed docs/checklist.md: ${steps.length} STEPs, ${totalItems} check items (the harness mechanically verifies only some of them)`
    })
  } else {
    failures.push({
      ruleId: 'checklist.meta.doc-missing',
      severity: 'medium',
      message: `docs/checklist.md not found — skipping STEP structure parsing`
    })
  }

  return { name: 'checklist', score: Math.max(score, 0), maxScore: 100, failures }
}
