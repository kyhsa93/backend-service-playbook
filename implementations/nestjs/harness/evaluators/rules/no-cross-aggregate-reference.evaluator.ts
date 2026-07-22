// The no-cross-aggregate-reference evaluator — one Aggregate never directly references
// another Aggregate. Only an ID reference (paymentId: string) is allowed; it never holds
// another Aggregate's type itself as a field/constructor parameter
// (guide: ../../docs/architecture/domain-service.md — "one Aggregate never directly
// references another Aggregate" / the RefundEligibilityService example).
//
// Scope: src/payment/domain/ — narrowed to this repo's actual case where two independent
// Aggregates (Payment, Refund) coexist within the same Bounded Context.
// To generalize to other languages/domains, this could be extended to "when a BC's domain/
// has multiple different Aggregate files, do they import each other" — but for now, to
// minimize false-positive risk, only these two actually-verified files are precisely targeted.
//
// Check: verifies precisely, based on the import statement (a named import), that payment.ts
// never imports the `Refund` type from refund.ts, and refund.ts never imports the `Payment`
// type from payment.ts. Mentions of "Payment"/"Refund" inside a comment, or non-matching
// identifiers like PaymentStatus/PaymentErrorMessage, aren't targets.
//
// Applicability: runs only when both src/payment/domain/payment.ts and refund.ts exist.

import * as fs from 'node:fs'
import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { readSourceFile } from '../shared/ast-utils'

const DOC_REF = '../../docs/architecture/domain-service.md#실제-동작하는-예시--refundeligibilityservice-cross-aggregate-조율'

interface Pair {
  file: string
  forbiddenName: string
  forbiddenModuleSuffix: string
}

function importsNamedBindingFrom(filePath: string, forbiddenName: string, moduleSuffix: string): boolean {
  const sf = readSourceFile(filePath)
  let found = false

  sf.forEachChild((node) => {
    if (!ts.isImportDeclaration(node) || !ts.isStringLiteral(node.moduleSpecifier)) return
    const specifier = node.moduleSpecifier.text
    if (!specifier.endsWith(moduleSuffix)) return

    const clause = node.importClause
    const namedBindings = clause?.namedBindings
    if (namedBindings && ts.isNamedImports(namedBindings)) {
      for (const el of namedBindings.elements) {
        if (el.name.text === forbiddenName) found = true
      }
    }
  })

  return found
}

export function evaluateNoCrossAggregateReference(root: string): EvaluatorResult {
  const paymentFile = path.join(root, 'src', 'payment', 'domain', 'payment.ts')
  const refundFile = path.join(root, 'src', 'payment', 'domain', 'refund.ts')

  if (!fs.existsSync(paymentFile) || !fs.existsSync(refundFile)) {
    return { name: 'no-cross-aggregate-reference', score: 0, maxScore: 0, failures: [] }
  }

  const pairs: Pair[] = [
    { file: paymentFile, forbiddenName: 'Refund', forbiddenModuleSuffix: '/refund' },
    { file: refundFile, forbiddenName: 'Payment', forbiddenModuleSuffix: '/payment' }
  ]

  const failures: EvaluatorFailure[] = []
  let score = 10
  const rel = (f: string) => path.relative(root, f)

  for (const pair of pairs) {
    if (!importsNamedBindingFrom(pair.file, pair.forbiddenName, pair.forbiddenModuleSuffix)) continue

    failures.push({
      ruleId: 'no-cross-aggregate-reference.aggregate-field-reference',
      severity: 'high',
      message: `${rel(pair.file)} — 다른 Aggregate(${pair.forbiddenName})를 직접 import함. ID(예: ${pair.forbiddenName.toLowerCase()}Id: string) 참조만 허용된다`,
      docRef: DOC_REF
    })
    score -= penaltyFor('high')
  }

  return {
    name: 'no-cross-aggregate-reference',
    score: Math.max(score, 0),
    maxScore: 10,
    failures
  }
}
