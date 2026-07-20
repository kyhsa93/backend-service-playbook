// no-generic-response-keys evaluator — 목록 조회 응답의 배열 필드는 도메인
// 복수형 명사를 사용해야 하며 `result`/`data`/`items` 같은 범용 키를 쓰지
// 않는다 (guide: docs/architecture/api-response.md — "키 이름은 도메인 객체명
// 복수형 ... `result`, `data`, `items` 같은 범용 키를 사용하지 않는다").
//
// Scope: application/ 과 interface/ 레이어의 class 선언. `count: number`
// 필드와 배열 타입(`X[]`/`Array<X>`) 필드가 함께 있는 class만 "목록 응답"
// 시그니처로 간주한다 — count 없이 단독으로 있는 `items` 필드(예: 주문의
// 라인 아이템 `items: OrderItem[]`)는 목록 "응답 래퍼"가 아니라 정당한 도메인
// 필드이므로 오탐을 막기 위해 count 동반 여부로 좁힌다
// (docs/architecture/api-response.md의 단건 조회 응답 예시 참고).
//
// Applicability: application/ 또는 interface/ 레이어에 이런 class가 없으면 skip.

import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { classifyLayer, readSourceFile, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = '../../docs/architecture/api-response.md#목록-조회-응답-형식'

const GENERIC_KEYS = new Set(['result', 'data', 'items'])

interface Violation {
  ruleId: string
  message: string
}

function propertyTypeText(member: ts.ClassElement, sf: ts.SourceFile): string | null {
  if (!ts.isPropertyDeclaration(member) || !member.type) return null
  return member.type.getText(sf)
}

function isArrayTypeText(typeText: string): boolean {
  return /\[\]\s*$/.test(typeText) || /^Array\s*</.test(typeText)
}

function inspectFile(filePath: string): Violation[] {
  const sf = readSourceFile(filePath)
  const violations: Violation[] = []

  function visit(node: ts.Node) {
    if (ts.isClassDeclaration(node) && node.name) {
      const propertyNames = node.members
        .filter((m): m is ts.PropertyDeclaration => ts.isPropertyDeclaration(m) && !!m.name && ts.isIdentifier(m.name))
        .map((m) => (m.name as ts.Identifier).text)
      const hasCount = propertyNames.includes('count')

      if (hasCount) {
        for (const member of node.members) {
          if (!ts.isPropertyDeclaration(member) || !member.name || !ts.isIdentifier(member.name)) continue
          const name = member.name.text
          if (!GENERIC_KEYS.has(name)) continue

          const typeText = propertyTypeText(member, sf)
          if (!typeText || !isArrayTypeText(typeText)) continue

          violations.push({
            ruleId: 'no-generic-response-keys.generic-list-field',
            message: `${node.name.text}.${name} — 목록 응답 배열 필드에 범용 키(${name}) 사용 금지. 도메인 복수형 명사를 사용한다 (예: orders, accounts)`
          })
        }
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return violations
}

export function evaluateNoGenericResponseKeys(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const targetFiles = walkTsFiles(srcRoot).filter((f) => {
    const layer = classifyLayer(f)
    return (layer === 'application' || layer === 'interface') && !f.endsWith('.spec.ts')
  })

  if (targetFiles.length === 0) {
    return { name: 'no-generic-response-keys', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of targetFiles) {
    for (const v of inspectFile(file)) {
      failures.push({
        ruleId: v.ruleId,
        severity: 'medium',
        message: `${rel(file)} — ${v.message}`,
        docRef: DOC_REF
      })
      score -= penaltyFor('medium')
    }
  }

  return {
    name: 'no-generic-response-keys',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
