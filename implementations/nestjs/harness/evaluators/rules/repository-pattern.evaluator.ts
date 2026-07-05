import * as fs from 'node:fs'
import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorResult, EvaluatorFailure } from '../shared/types'
import { parseImports, readSourceFile } from '../shared/ast-utils'

const REPOSITORY_IMPL_NAME = /Repository(Impl)?$/

// application 레이어에서 `new XxxRepository(...)` / `new XxxRepositoryImpl(...)` 형태로
// 직접 인스턴스화하는지 확인한다. `new Money(...)`, `new Error(...)` 같은 무관한 `new` 표현식은
// 대상 클래스명이 Repository로 끝나지 않으므로 매치되지 않는다.
function instantiatesRepositoryDirectly(filePath: string): boolean {
  const sf = readSourceFile(filePath)
  let found = false
  function visit(node: ts.Node) {
    if (found) return
    if (ts.isNewExpression(node) && ts.isIdentifier(node.expression) && REPOSITORY_IMPL_NAME.test(node.expression.text)) {
      found = true
      return
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return found
}

function importsTypeormDirectly(filePath: string): boolean {
  return parseImports(filePath).some((spec) => spec === 'typeorm' || spec.startsWith('typeorm/'))
}

export function evaluateRepositoryPattern(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 25

  const domainPath = path.join(root, 'src')

  function walk(dir: string): string[] {
    if (!fs.existsSync(dir)) return []
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry): string[] => {
      const full = path.join(dir, entry.name)
      return entry.isDirectory() ? walk(full) : [full]
    })
  }

  const files = walk(domainPath).filter((f) => f.endsWith('.ts'))

  for (const file of files) {
    if (file.endsWith('-repository.ts')) {
      const content = fs.readFileSync(file, 'utf-8')
      if (!content.includes('abstract class')) {
        failures.push({
          ruleId: 'repository.abstract-class',
          severity: 'high',
          message: `repository는 abstract class여야 함: ${file}`
        })
        score -= 5
      }
    }

    if (file.includes('/application/')) {
      if (instantiatesRepositoryDirectly(file) || importsTypeormDirectly(file)) {
        failures.push({
          ruleId: 'repository.no-direct-instantiation',
          severity: 'high',
          message: `application에서 repository 직접 생성 금지: ${file}`
        })
        score -= 5
      }
    }
  }

  return {
    name: 'repository-pattern',
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  }
}
