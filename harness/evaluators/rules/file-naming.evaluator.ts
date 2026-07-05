import * as path from 'node:path'

import { EvaluatorResult, EvaluatorFailure } from '../shared/types'
import { getWorkspace } from '../shared/workspace'
import { normPath } from '../shared/ast-utils'

function isKebabCase(name: string): boolean {
  // kebab-case: lowercase letters, digits, hyphens only; no leading/trailing hyphen
  return /^[a-z0-9]+(?:-[a-z0-9]+)*\.[a-z0-9.]+$/.test(name)
}

// Returns true if the normalized path includes the given layer segment
function inLayer(filePath: string, layer: string): boolean {
  return normPath(filePath).includes(`/${layer}/`)
}

export function evaluateFileNaming(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 25

  const ws = getWorkspace(root)
  const files = ws.listTsFiles()

  for (const filePath of files) {
    const rel = path.relative(root, filePath)
    const fileName = path.basename(filePath)

    // Rule 1: all .ts files must use kebab-case
    if (!isKebabCase(fileName)) {
      failures.push({
        ruleId: 'file-naming.kebab-case',
        severity: 'medium',
        message: `kebab-case 규칙 위반: ${rel}`,
        docRef: 'docs/architecture/directory-structure.md#파일-네이밍-규칙'
      })
      score -= 3
      continue // remaining placement checks assume kebab-case
    }

    // Rule 2: *-repository.ts (interface) must be in domain/
    if (fileName.endsWith('-repository.ts') && !fileName.endsWith('-repository-impl.ts')) {
      if (!inLayer(filePath, 'domain')) {
        failures.push({
          ruleId: 'file-naming.repository-in-domain',
          severity: 'high',
          message: `Repository 인터페이스는 domain/ 레이어에 있어야 함: ${rel}`,
          docRef: 'docs/architecture/directory-structure.md#파일-네이밍-규칙'
        })
        score -= 5
      }
    }

    // Rule 3: *-repository-impl.ts must be in infrastructure/
    if (fileName.endsWith('-repository-impl.ts')) {
      if (!inLayer(filePath, 'infrastructure')) {
        failures.push({
          ruleId: 'file-naming.repository-impl-in-infrastructure',
          severity: 'high',
          message: `Repository 구현체는 infrastructure/ 레이어에 있어야 함: ${rel}`,
          docRef: 'docs/architecture/directory-structure.md#파일-네이밍-규칙'
        })
        score -= 5
      }
    }

    // Rule 4: *-command-service.ts must be in application/command/
    if (fileName.endsWith('-command-service.ts')) {
      if (!normPath(filePath).includes('/application/command/')) {
        failures.push({
          ruleId: 'file-naming.command-service-in-application-command',
          severity: 'medium',
          message: `Command Service는 application/command/ 에 있어야 함: ${rel}`,
          docRef: 'docs/architecture/directory-structure.md#파일-네이밍-규칙'
        })
        score -= 3
      }
    }

    // Rule 5: *-query-service.ts must be in application/query/
    if (fileName.endsWith('-query-service.ts')) {
      if (!normPath(filePath).includes('/application/query/')) {
        failures.push({
          ruleId: 'file-naming.query-service-in-application-query',
          severity: 'medium',
          message: `Query Service는 application/query/ 에 있어야 함: ${rel}`,
          docRef: 'docs/architecture/directory-structure.md#파일-네이밍-규칙'
        })
        score -= 3
      }
    }

    // Rule 6: *-scheduler.ts must be in infrastructure/
    if (fileName.endsWith('-scheduler.ts')) {
      if (!inLayer(filePath, 'infrastructure')) {
        failures.push({
          ruleId: 'file-naming.scheduler-in-infrastructure',
          severity: 'medium',
          message: `Scheduler는 infrastructure/ 레이어에 있어야 함: ${rel}`,
          docRef: 'docs/architecture/scheduling.md'
        })
        score -= 3
      }
    }
  }

  return { name: 'file-naming', score: Math.max(score, 0), maxScore: 25, failures }
}
