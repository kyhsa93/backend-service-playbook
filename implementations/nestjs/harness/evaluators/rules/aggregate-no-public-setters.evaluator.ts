// aggregate-no-public-setters evaluator — Aggregate/Entity/Value Object
// 상태 변경은 반드시 이름 있는 도메인 메서드(deposit(), suspend() 등)를 통해서만
// 이뤄진다 (guide: docs/architecture/layer-architecture.md, tactical-ddd.md).
// 외부에서 직접 대입 가능한 public setter나 public mutable 필드가 있으면 이
// 불변식이 우회될 수 있다.
//
// Scope: src/<domain>/domain/*.ts의 모든 class(Aggregate, Entity, Value Object
// 구분 없이 — 이 저장소의 실제 관례는 세 종류 모두 private/readonly 필드 +
// getter만 노출하는 동일한 패턴을 쓴다. Domain Service(상태 없음)는 필드 자체가
// 없어 자연히 대상에서 제외된다).
//
// Rules (블록리스트):
// - public `set x(...)` accessor 금지
// - private/protected/readonly가 아닌 인스턴스 property 금지 (public mutable field)
//
// Applicability: domain/ 클래스가 없으면 skip.

import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { classifyLayer, readSourceFile, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = 'docs/architecture/layer-architecture.md#domain-레이어-역할'

interface Violation {
  ruleId: string
  message: string
}

function hasModifier(mods: ts.NodeArray<ts.ModifierLike> | undefined, kind: ts.SyntaxKind): boolean {
  return (mods ?? []).some((m) => m.kind === kind)
}

function inspectFile(filePath: string): Violation[] {
  const sf = readSourceFile(filePath)
  const violations: Violation[] = []

  function visit(node: ts.Node) {
    if (ts.isClassDeclaration(node) && node.name) {
      for (const member of node.members) {
        const mods = (member as ts.Node & { modifiers?: ts.NodeArray<ts.ModifierLike> }).modifiers
        const isPrivate = hasModifier(mods, ts.SyntaxKind.PrivateKeyword)
        const isProtected = hasModifier(mods, ts.SyntaxKind.ProtectedKeyword)
        const isStatic = hasModifier(mods, ts.SyntaxKind.StaticKeyword)

        if (ts.isSetAccessor(member) && member.name && !isPrivate && !isProtected) {
          const name = member.name.getText(sf)
          violations.push({
            ruleId: 'aggregate-no-public-setters.public-setter',
            message: `${node.name.text}.${name}(...) — public setter 금지. 상태 변경은 이름 있는 도메인 메서드로만 노출한다`
          })
        }

        if (ts.isPropertyDeclaration(member) && member.name && ts.isIdentifier(member.name)) {
          const isReadonly = hasModifier(mods, ts.SyntaxKind.ReadonlyKeyword)
          if (!isPrivate && !isProtected && !isReadonly && !isStatic) {
            violations.push({
              ruleId: 'aggregate-no-public-setters.public-mutable-field',
              message: `${node.name.text}.${member.name.text} — public mutable field 금지. private로 감추고 readonly 또는 도메인 메서드로만 변경한다`
            })
          }
        }
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return violations
}

export function evaluateAggregateNoPublicSetters(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const domainFiles = walkTsFiles(srcRoot).filter((f) => classifyLayer(f) === 'domain' && !f.endsWith('.spec.ts'))

  if (domainFiles.length === 0) {
    return { name: 'aggregate-no-public-setters', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of domainFiles) {
    for (const v of inspectFile(file)) {
      failures.push({
        ruleId: v.ruleId,
        severity: 'high',
        message: `${rel(file)} — ${v.message}`,
        docRef: DOC_REF
      })
      score -= penaltyFor('high')
    }
  }

  return {
    name: 'aggregate-no-public-setters',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
