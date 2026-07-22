// The no-orm-autosync-in-prod-config evaluator — TypeORM's auto-schema-sync
// (`synchronize: true`) is prohibited in production. Schema changes must always be managed
// via migrations (guide: docs/architecture/persistence.md — "use synchronize: true only in
// the development environment; manage the schema via migrations in production").
//
// Check: `new DataSource({...})` / `TypeOrmModule.forRoot({...})` /
// Finds the `synchronize` property inside a `TypeOrmModule.forRootAsync({...})` call argument (or `new DataSource({...})`).
//   - Hardcoded as the literal `true` → always fails.
//   - A conditional expression shaped like `process.env.NODE_ENV === 'production'` (or its
//     ternary-operator version) — i.e. one that "evaluates to true when NODE_ENV is
//     production" — fails
//     (conversely, a form like `!== 'production'`, which evaluates to false in production, is allowed).
//   - Anything else (a function call, a ConfigService lookup, etc. — an expression whose
//     truthiness can't be statically proven) isn't flagged as a failure, to avoid false positives.
//   - If the `synchronize` property is simply absent, it's safe since TypeORM's default is false.
//
// Applicability: skipped if there's not a single DataSource/TypeOrmModule.forRoot(Async) call
// anywhere in the project.

import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { readSourceFile, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = '../../docs/architecture/persistence.md#migrations'

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

// Determines whether it's shaped like `left === 'production'` (assumed to be a NODE_ENV
// comparison), and if so, also returns whether this binary expression itself evaluates to true in production (`equalsWhenProd`).
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

// True if it's provable that this expression statically evaluates to true when NODE_ENV=production.
// An unprovable expression (a function call, etc.) is assumed safe (false), to avoid false positives.
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
      message: `synchronize: true — hardcoding this is forbidden. It must never be true in production; manage the schema with migrations instead`
    }
  }

  if (evaluatesTrueInProduction(value, sf)) {
    return {
      ruleId: 'no-orm-autosync-in-prod-config.synchronize-true-in-production',
      message: `synchronize: ${value.getText(sf)} — evaluates to true when NODE_ENV=production. auto-schema-sync is forbidden in production`
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
