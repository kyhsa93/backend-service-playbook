// no-orm-autosync-in-prod-config evaluator — TypeORM의 auto-schema-sync
// (`synchronize: true`)는 운영 환경에서 금지된다. 스키마 변경은 반드시
// 마이그레이션으로 관리한다 (guide: docs/architecture/persistence.md —
// "synchronize: true는 개발 환경에서만 사용하고, 운영 환경에서는 마이그레이션으로
// 스키마를 관리한다").
//
// Check: `new DataSource({...})` / `TypeOrmModule.forRoot({...})` /
// `TypeOrmModule.forRootAsync({...})` 호출 인자 안의 `synchronize` 프로퍼티를
// 찾는다.
//   - 리터럴 `true`로 하드코딩 → 항상 실패.
//   - `process.env.NODE_ENV === 'production'` 형태(또는 그 삼항 연산자 버전)처럼
//     "NODE_ENV가 production일 때 true로 평가되는" 조건식 → 실패
//     (반대로 `!== 'production'`처럼 production일 때 false로 평가되는 형태는 허용).
//   - 그 외(함수 호출, ConfigService 조회 등 정적으로 진위를 증명할 수 없는 표현)는
//     오탐을 피하기 위해 실패로 잡지 않는다.
//   - `synchronize` 프로퍼티 자체가 없으면 TypeORM 기본값(false)이므로 안전.
//
// Applicability: 프로젝트 안에 DataSource/TypeOrmModule.forRoot(Async) 호출이
// 하나도 없으면 skip.

import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { readSourceFile, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = '../../docs/architecture/persistence.md#마이그레이션'

function isTypeOrmConfigCall(node: ts.Node, sf: ts.SourceFile): boolean {
  if (ts.isNewExpression(node)) {
    return node.expression.getText(sf) === 'DataSource'
  }
  if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)) {
    const objectText = node.expression.expression.getText(sf)
    const methodName = node.expression.name.text
    return objectText === 'TypeOrmModule' && (methodName === 'forRoot' || methodName === 'forRootAsync')
  }
  return false
}

function stringLiteralEquals(node: ts.Node, value: string): boolean {
  return ts.isStringLiteral(node) && node.text === value
}

// `left === 'production'` 형태(NODE_ENV 비교로 가정)인지 판별하고, 그 경우
// 이 이항식 자체가 production일 때 true로 평가되는지(`equalsWhenProd`)를 함께 반환한다.
function productionComparison(node: ts.BinaryExpression): { matches: boolean; equalsWhenProd: boolean } {
  const op = node.operatorToken.getText()
  const isEq = op === '===' || op === '=='
  const isNeq = op === '!==' || op === '!='
  if (!isEq && !isNeq) return { matches: false, equalsWhenProd: false }
  if (!stringLiteralEquals(node.right, 'production') && !stringLiteralEquals(node.left, 'production')) {
    return { matches: false, equalsWhenProd: false }
  }
  return { matches: true, equalsWhenProd: isEq }
}

// 이 표현식이 NODE_ENV=production일 때 정적으로 true로 평가된다고 증명 가능하면 true.
// 증명할 수 없는 표현(함수 호출 등)은 안전하다고 간주(false)해 오탐을 피한다.
function evaluatesTrueInProduction(node: ts.Expression, sf: ts.SourceFile): boolean {
  if (node.kind === ts.SyntaxKind.TrueKeyword) return true
  if (node.kind === ts.SyntaxKind.FalseKeyword) return false
  if (ts.isParenthesizedExpression(node)) return evaluatesTrueInProduction(node.expression, sf)

  if (ts.isBinaryExpression(node)) {
    const { matches, equalsWhenProd } = productionComparison(node)
    return matches ? equalsWhenProd : false
  }

  if (ts.isConditionalExpression(node) && ts.isBinaryExpression(node.condition)) {
    const { matches, equalsWhenProd } = productionComparison(node.condition)
    if (!matches) return false
    const activeBranch = equalsWhenProd ? node.whenTrue : node.whenFalse
    return evaluatesTrueInProduction(activeBranch, sf)
  }

  return false
}

interface Violation {
  ruleId: string
  message: string
}

function inspectSynchronizeProperty(prop: ts.PropertyAssignment, sf: ts.SourceFile): Violation | null {
  const value = prop.initializer

  if (value.kind === ts.SyntaxKind.TrueKeyword) {
    return {
      ruleId: 'no-orm-autosync-in-prod-config.synchronize-hardcoded-true',
      message: `synchronize: true — 하드코딩 금지. 운영 환경에서는 절대 true가 되면 안 되며 마이그레이션으로 스키마를 관리한다`
    }
  }

  if (evaluatesTrueInProduction(value, sf)) {
    return {
      ruleId: 'no-orm-autosync-in-prod-config.synchronize-true-in-production',
      message: `synchronize: ${value.getText(sf)} — NODE_ENV=production일 때 true로 평가됨. 운영 환경에서는 auto-schema-sync를 금지한다`
    }
  }

  return null
}

function findSynchronizeViolations(callNode: ts.Node, sf: ts.SourceFile): Violation[] {
  const violations: Violation[] = []

  function visit(node: ts.Node) {
    if (
      ts.isPropertyAssignment(node)
      && ((ts.isIdentifier(node.name) && node.name.text === 'synchronize') || (ts.isStringLiteral(node.name) && node.name.text === 'synchronize'))
    ) {
      const v = inspectSynchronizeProperty(node, sf)
      if (v) violations.push(v)
    }
    ts.forEachChild(node, visit)
  }
  visit(callNode)
  return violations
}

export function evaluateNoOrmAutosyncInProdConfig(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const files = walkTsFiles(srcRoot).filter((f) => !f.endsWith('.spec.ts'))

  const failures: EvaluatorFailure[] = []
  let configCallCount = 0
  const rel = (f: string) => path.relative(root, f)

  for (const file of files) {
    const sf = readSourceFile(file)
    let matched = false

    function visit(node: ts.Node) {
      if (isTypeOrmConfigCall(node, sf)) {
        matched = true
        for (const v of findSynchronizeViolations(node, sf)) {
          failures.push({
            ruleId: v.ruleId,
            severity: 'critical',
            message: `${rel(file)} — ${v.message}`,
            docRef: DOC_REF
          })
        }
      }
      ts.forEachChild(node, visit)
    }
    visit(sf)

    if (matched) configCallCount += 1
  }

  if (configCallCount === 0) {
    return { name: 'no-orm-autosync-in-prod-config', score: 0, maxScore: 0, failures: [] }
  }

  let score = 10
  for (const f of failures) score -= penaltyFor(f.severity)

  return {
    name: 'no-orm-autosync-in-prod-config',
    score: Math.max(score, 0),
    maxScore: 10,
    failures
  }
}
