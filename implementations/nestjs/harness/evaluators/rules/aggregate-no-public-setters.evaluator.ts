// aggregate-no-public-setters evaluator — Aggregate/Entity/Value Object
// State changes must always happen only through a named domain method (deposit(), suspend(),
// etc.) (guide: docs/architecture/layer-architecture.md, tactical-ddd.md).
// A public setter or public mutable field that can be assigned to directly from the outside
// could bypass this invariant.
//
// Scope: every class in src/<domain>/domain/*.ts (regardless of Aggregate, Entity, Value
// Object — this repo's actual convention is that all three use the same pattern, exposing only
// private/readonly fields + getters. A Domain Service (stateless) has no fields at all, so it's
// naturally excluded).
//
// Rules (a blocklist):
// - a public `set x(...)` accessor is prohibited
// - an instance property that isn't private/protected/readonly is prohibited (a public mutable field)
//
// Applicability: skipped if there's no domain/ class.

import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { classifyLayer, readSourceFile, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = 'docs/architecture/layer-architecture.md#domain-layer-responsibilities'

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
