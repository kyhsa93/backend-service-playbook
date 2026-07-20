// no-cross-aggregate-reference evaluator — 하나의 Aggregate는 다른 Aggregate를
// 직접 참조하지 않는다. ID 참조(paymentId: string)만 허용하고, 다른 Aggregate의
// 타입 자체를 필드/생성자 파라미터로 갖지 않는다
// (guide: ../../docs/architecture/domain-service.md — "하나의 Aggregate는 다른
// Aggregate를 직접 참조하지 않는다" / RefundEligibilityService 예시).
//
// Scope: src/payment/domain/ — 같은 Bounded Context 안에 두 개의 독립된
// Aggregate(Payment, Refund)가 공존하는 이 저장소의 실제 케이스로 좁힌다.
// 다른 언어/도메인에 일반화하려면 '한 BC의 domain/ 안에 서로 다른 Aggregate
// 파일이 여럿 있을 때 서로를 import하지 않는가'로 확장할 수 있지만, 지금은
// 오탐 위험을 최소화하기 위해 실제 검증된 두 파일만 정밀 대상으로 삼는다.
//
// Check: payment.ts가 refund.ts에서 `Refund` 타입을 import하지 않고,
// refund.ts가 payment.ts에서 `Payment` 타입을 import하지 않는지 — import
// 구문(named import) 기준의 정밀 검사다. 주석 안의 "Payment"/"Refund" 언급이나
// PaymentStatus/PaymentErrorMessage 같은 동명이 아닌 식별자는 대상이 아니다.
//
// Applicability: src/payment/domain/payment.ts와 refund.ts가 모두 존재할 때만 실행.

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
