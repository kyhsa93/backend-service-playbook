// The no-generic-response-keys evaluator — an array field in a list-lookup response must use
// a domain-specific plural noun, never a generic key like `result`/`data`/`items` (guide:
// docs/architecture/api-response.md — "the key name is the plural of the domain object name
// ... never a generic key like `result`, `data`, `items`").
//
// Scope: class declarations in the application/ and interface/ layers. Only a class that has
// both a `count: number` field and an array-typed (`X[]`/`Array<X>`) field is treated as a
// "list response" signature — a standalone `items` field with no count (e.g. an order's line
// items `items: OrderItem[]`) is a legitimate domain field, not a list "response wrapper," so
// this is narrowed to whether count is present, to avoid false positives
// (see the single-record-lookup-response example in docs/architecture/api-response.md).
//
// Applicability: skipped if no such class exists in the application/ or interface/ layer.

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
