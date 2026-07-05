import * as path from 'node:path'

import { EvaluatorResult, EvaluatorFailure } from '../shared/types'
import { getWorkspace } from '../shared/workspace'
import { normPath } from '../shared/ast-utils'

export function evaluateCqrsPattern(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 20

  const ws = getWorkspace(root)
  const files = ws.listTsFiles()

  const hasCommandDir = files.some(f => normPath(f).includes('/application/command/'))
  const hasQueryDir = files.some(f => normPath(f).includes('/application/query/'))

  if (!hasCommandDir) {
    failures.push({
      ruleId: 'cqrs.command-directory-missing',
      severity: 'medium',
      message: 'application/command/ 디렉토리가 없습니다',
      docRef: 'docs/architecture/cqrs-pattern.md'
    })
    score -= 8
  }

  if (!hasQueryDir) {
    failures.push({
      ruleId: 'cqrs.query-directory-missing',
      severity: 'medium',
      message: 'application/query/ 디렉토리가 없습니다',
      docRef: 'docs/architecture/cqrs-pattern.md'
    })
    score -= 8
  }

  // Rule: files in application/command/ should not be named *-query-service.ts
  // Rule: files in application/query/ should not be named *-command-service.ts
  for (const filePath of files) {
    const np = normPath(filePath)
    const rel = path.relative(root, filePath)
    const name = path.basename(filePath)

    if (np.includes('/application/command/') && name.endsWith('-query-service.ts')) {
      failures.push({
        ruleId: 'cqrs.query-service-in-command-dir',
        severity: 'medium',
        message: `Query Service가 application/command/ 에 있음: ${rel}`,
        docRef: 'docs/architecture/cqrs-pattern.md'
      })
      score -= 4
    }

    if (np.includes('/application/query/') && name.endsWith('-command-service.ts')) {
      failures.push({
        ruleId: 'cqrs.command-service-in-query-dir',
        severity: 'medium',
        message: `Command Service가 application/query/ 에 있음: ${rel}`,
        docRef: 'docs/architecture/cqrs-pattern.md'
      })
      score -= 4
    }
  }

  // Evaluator not applicable if no application/ directory exists at all
  const hasApplicationDir = files.some(f => normPath(f).includes('/application/'))
  if (!hasApplicationDir) {
    return { name: 'cqrs-pattern', score: 0, maxScore: 0, failures: [] }
  }

  return { name: 'cqrs-pattern', score: Math.max(score, 0), maxScore: 20, failures }
}
