import * as path from 'node:path'

import { EvaluatorResult, EvaluatorFailure } from '../shared/types'
import { getWorkspace } from '../shared/workspace'
import { normPath } from '../shared/ast-utils'

export function evaluateRepositoryPattern(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 20

  const ws = getWorkspace(root)
  const files = ws.listTsFiles()

  const repoInterfaceFiles = files.filter(f => {
    const name = path.basename(f)
    return name.endsWith('-repository.ts') && !name.endsWith('-repository-impl.ts')
  })
  const repoImplFiles = files.filter(f => path.basename(f).endsWith('-repository-impl.ts'))

  // Rule 1: Repository interface (*-repository.ts) must use abstract class
  for (const filePath of repoInterfaceFiles) {
    const content = ws.read(filePath)
    const rel = path.relative(root, filePath)

    if (!/abstract\s+class/.test(content)) {
      failures.push({
        ruleId: 'repository.interface.must-be-abstract',
        severity: 'high',
        message: `Repository 인터페이스는 abstract class 여야 함: ${rel}`,
        docRef: 'docs/architecture/repository-pattern.md'
      })
      score -= 6
    }
  }

  // Rule 2: Repository impl (*-repository-impl.ts) must NOT be in domain/
  for (const filePath of repoImplFiles) {
    const rel = path.relative(root, filePath)
    if (normPath(filePath).includes('/domain/')) {
      failures.push({
        ruleId: 'repository.impl.not-in-domain',
        severity: 'critical',
        message: `Repository 구현체는 domain/ 에 있으면 안 됨: ${rel}`,
        docRef: 'docs/architecture/repository-pattern.md'
      })
      score -= 7
    }
  }

  // Rule 3: Application service files (command/) must not directly instantiate a Repository
  // Detected by: `new <Something>Repository(` in application/ files
  const appFiles = files.filter(f => normPath(f).includes('/application/'))
  for (const filePath of appFiles) {
    const content = ws.read(filePath)
    const rel = path.relative(root, filePath)
    if (/new\s+\w+Repository\s*\(/.test(content)) {
      failures.push({
        ruleId: 'repository.no-direct-instantiation',
        severity: 'high',
        message: `application 레이어에서 Repository 직접 생성(new) 금지 — DI를 통해 주입받아야 함: ${rel}`,
        docRef: 'docs/architecture/repository-pattern.md'
      })
      score -= 5
    }
  }

  // Evaluator only applies if the submission has repository files at all
  if (repoInterfaceFiles.length === 0 && repoImplFiles.length === 0) {
    return { name: 'repository-pattern', score: 0, maxScore: 0, failures: [] }
  }

  return { name: 'repository-pattern', score: Math.max(score, 0), maxScore: 20, failures }
}
