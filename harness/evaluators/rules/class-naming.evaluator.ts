import * as path from 'node:path'

import { EvaluatorResult, EvaluatorFailure } from '../shared/types'
import { getWorkspace } from '../shared/workspace'

function extractClassName(content: string): string | null {
  const match = content.match(/(?:export\s+)?(?:abstract\s+)?class\s+(\w+)/)
  return match ? match[1] : null
}

function hasAbstractClass(content: string): boolean {
  return /(?:export\s+)?abstract\s+class\s+/.test(content)
}

function hasEnum(content: string): boolean {
  return /(?:export\s+)?(?:const\s+)?enum\s+/.test(content)
}

export function evaluateClassNaming(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 20

  const ws = getWorkspace(root)
  const files = ws.listTsFiles()

  for (const filePath of files) {
    const fileName = path.basename(filePath)
    const rel = path.relative(root, filePath)
    const content = ws.read(filePath)

    // Rule 1: *-repository.ts in domain/ must be an abstract class named *Repository
    if (fileName.endsWith('-repository.ts') && !fileName.endsWith('-repository-impl.ts')) {
      if (!hasAbstractClass(content)) {
        failures.push({
          ruleId: 'class-naming.repository-must-be-abstract',
          severity: 'high',
          message: `Repository 인터페이스 파일은 abstract class 여야 함: ${rel}`,
          docRef: 'docs/architecture/repository-pattern.md'
        })
        score -= 5
      } else {
        const className = extractClassName(content)
        if (className && !className.endsWith('Repository')) {
          failures.push({
            ruleId: 'class-naming.repository-suffix',
            severity: 'medium',
            message: `Repository 클래스명은 'Repository'로 끝나야 함: ${className} (${rel})`,
            docRef: 'docs/architecture/directory-structure.md#클래스-네이밍-규칙'
          })
          score -= 3
        }
      }
    }

    // Rule 2: *-repository-impl.ts must be a concrete class named *RepositoryImpl
    if (fileName.endsWith('-repository-impl.ts')) {
      const className = extractClassName(content)
      if (className && !className.endsWith('RepositoryImpl')) {
        failures.push({
          ruleId: 'class-naming.repository-impl-suffix',
          severity: 'medium',
          message: `Repository 구현체 클래스명은 'RepositoryImpl'로 끝나야 함: ${className} (${rel})`,
          docRef: 'docs/architecture/directory-structure.md#클래스-네이밍-규칙'
        })
        score -= 3
      }
    }

    // Rule 3: *-command-service.ts must be named *CommandService
    if (fileName.endsWith('-command-service.ts')) {
      const className = extractClassName(content)
      if (className && !className.endsWith('CommandService')) {
        failures.push({
          ruleId: 'class-naming.command-service-suffix',
          severity: 'medium',
          message: `Command Service 클래스명은 'CommandService'로 끝나야 함: ${className} (${rel})`,
          docRef: 'docs/architecture/directory-structure.md#클래스-네이밍-규칙'
        })
        score -= 3
      }
    }

    // Rule 4: *-query-service.ts must be named *QueryService
    if (fileName.endsWith('-query-service.ts')) {
      const className = extractClassName(content)
      if (className && !className.endsWith('QueryService')) {
        failures.push({
          ruleId: 'class-naming.query-service-suffix',
          severity: 'medium',
          message: `Query Service 클래스명은 'QueryService'로 끝나야 함: ${className} (${rel})`,
          docRef: 'docs/architecture/directory-structure.md#클래스-네이밍-규칙'
        })
        score -= 3
      }
    }

    // Rule 5: *-error-message.ts must use enum
    if (fileName.endsWith('-error-message.ts')) {
      if (!hasEnum(content)) {
        failures.push({
          ruleId: 'class-naming.error-message-enum',
          severity: 'medium',
          message: `에러 메시지는 enum으로 정의해야 함: ${rel}`,
          docRef: 'docs/architecture/error-handling.md'
        })
        score -= 3
      }
    }
  }

  return { name: 'class-naming', score: Math.max(score, 0), maxScore: 20, failures }
}
