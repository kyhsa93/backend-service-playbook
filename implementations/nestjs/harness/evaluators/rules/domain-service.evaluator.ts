import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'

const DOC = '../../docs/architecture/domain-service.md'

function walkTsFiles(root: string): string[] {
  const out: string[] = []
  if (!fs.existsSync(root)) return out
  for (const entry of fs.readdirSync(root)) {
    if (['node_modules', 'dist', 'coverage', '.git'].includes(entry)) continue
    const full = path.join(root, entry)
    if (fs.statSync(full).isDirectory()) { out.push(...walkTsFiles(full)); continue }
    if (full.endsWith('.ts') && !full.endsWith('.d.ts') && !full.endsWith('.spec.ts')) out.push(full)
  }
  return out
}

function isDomainServiceFile(root: string, file: string): boolean {
  const rel = path.relative(root, file).replace(/\\/g, '/')
  // The src/<domain>/domain/*-service.ts pattern
  return /^src\/[^/]+\/domain\/[^/]+-service\.ts$/.test(rel)
}

export function evaluateDomainService(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const files = walkTsFiles(srcRoot)
  const domainServiceFiles = files.filter((f) => isDomainServiceFile(root, f))

  if (domainServiceFiles.length === 0) {
    return { name: 'domain-service', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 10
  const rel = (f: string) => path.relative(root, f)

  for (const file of domainServiceFiles) {
    const content = fs.readFileSync(file, 'utf-8')

    // A Domain Service must not have @Injectable() — framework independence is required
    if (/@Injectable\s*\(\s*\)/.test(content)) {
      failures.push({
        ruleId: 'domain-service.injectable-forbidden',
        severity: 'high',
        message: `${rel(file)} must not use @Injectable(). A Domain Service must not depend on the NestJS framework.`,
        docRef: DOC
      })
      score -= penaltyFor('high')
    }

    // A Domain Service must not have NestJS decorators like @Module, @Controller
    if (/@(Module|Controller|Get|Post|Put|Patch|Delete)\s*\(/.test(content)) {
      failures.push({
        ruleId: 'domain-service.nestjs-decorator-forbidden',
        severity: 'high',
        message: `${rel(file)} must not use NestJS routing/module decorators. A Domain Service must be a plain TypeScript class.`,
        docRef: DOC
      })
      score -= penaltyFor('high')
    }
  }

  return {
    name: 'domain-service',
    score: Math.max(score, 0),
    maxScore: 10,
    failures
  }
}
