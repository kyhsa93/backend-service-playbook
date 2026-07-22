// The soft-delete-filter evaluator — persistence.md's "Soft Delete" rule.
//
// This codebase's soft-delete mechanism is TypeORM's automatic filtering: once an Entity has
// `@DeleteDateColumn()` (declared directly or via BaseEntity inheritance), a find-family method
// on the QueryBuilder/Repository API automatically applies `deletedAt IS NULL` unless
// `.withDeleted()` is specified. (The database-queries evaluator already separately checks
// "hard delete (.delete()) is prohibited" and "BaseEntity inheritance," but that rule only
// looks at the Entity in isolation and never connects it to whether a Repository
// implementation actually queries a soft-deletable Entity — this evaluator handles that connection.)
//
// Rules:
// - If a `*-repository-impl.ts` has a `find<X>` method, and that method queries an Entity
//   injected via `@InjectRepository(XEntity)`, that Entity must be soft-deletable (BaseEntity
//   inheritance or its own `@DeleteDateColumn()`) — unless the file itself manually applies a
//   `deletedAt` filter. (A case that intentionally doesn't use soft-delete, like an
//   append-only log-style Entity, typically has no `find` method querying that Entity at all,
//   so it's inherently not a target of this rule.)
// - Fails if a Repository implementation queries a soft-deletable Entity via raw SQL
//   (`.query(...)`) without directly including `deletedAt IS NULL` in the SQL — raw SQL
//   bypasses TypeORM's automatic filter, so it must be filtered manually.

import * as fs from 'node:fs'
import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { readSourceFile, walkTsFiles } from '../shared/ast-utils'

const DOC = 'docs/architecture/persistence.md#soft-delete'

function classNamesDefinedIn(content: string): string[] {
  return [...content.matchAll(/export\s+class\s+(\w+)/g)].map((m) => m[1])
}

function isSoftDeletable(content: string): boolean {
  return /extends\s+\w*BaseEntity/.test(content) || /@DeleteDateColumn\s*\(/.test(content)
}

// Extracts the injected Entity class name from a constructor's @InjectRepository(XEntity) parameter.
function injectedEntityNames(filePath: string): string[] {
  const sf = readSourceFile(filePath)
  const names: string[] = []
  function visit(node: ts.Node) {
    if (ts.isConstructorDeclaration(node)) {
      for (const p of node.parameters) {
        const mods = (p.modifiers as ts.NodeArray<ts.ModifierLike> | undefined) ?? []
        for (const mod of mods) {
          if (!ts.isDecorator(mod)) continue
          const expr = mod.expression
          if (
            ts.isCallExpression(expr)
            && ts.isIdentifier(expr.expression)
            && expr.expression.text === 'InjectRepository'
            && expr.arguments[0]
            && ts.isIdentifier(expr.arguments[0])
          ) {
            names.push(expr.arguments[0].text)
          }
        }
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return names
}

// A trace of directly filtering `deletedAt` within the file (a manual WHERE) — IsNull()/IS NULL, etc.
function hasManualDeletedAtFilter(content: string): boolean {
  return /deletedAt[^;]{0,40}(IsNull\s*\(\s*\)|is\s+null)/i.test(content)
}

export function evaluateSoftDeleteFilter(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const allFiles = walkTsFiles(srcRoot).filter((f) => !f.endsWith('.spec.ts'))
  const repoImplFiles = allFiles.filter((f) => /-repository-impl\.ts$/.test(f))

  if (repoImplFiles.length === 0) {
    return { name: 'soft-delete-filter', score: 0, maxScore: 0, failures: [] }
  }

  // A project-wide map of Entity class name → whether it's soft-deletable.
  const entityFiles = allFiles.filter((f) => f.endsWith('.entity.ts'))
  const entitySoftDeleteMap = new Map<string, boolean>()
  for (const ef of entityFiles) {
    const content = fs.readFileSync(ef, 'utf-8')
    for (const cls of classNamesDefinedIn(content)) {
      entitySoftDeleteMap.set(cls, isSoftDeletable(content))
    }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of repoImplFiles) {
    const content = fs.readFileSync(file, 'utf-8')
    const hasFindMethod = /\basync\s+find[A-Z]\w*\s*\(/.test(content)
    if (!hasFindMethod) continue

    const entities = injectedEntityNames(file)
    const manuallyFiltered = hasManualDeletedAtFilter(content)

    for (const entityName of entities) {
      if (!entitySoftDeleteMap.has(entityName)) continue // a type outside the project, etc. — can't judge, skip
      if (entitySoftDeleteMap.get(entityName)) continue // a soft-deletable Entity — TypeORM's automatic filter applies
      if (manuallyFiltered) continue // the Entity has no soft-delete column, but deletedAt is filtered manually — allowed

      failures.push({
        ruleId: 'soft-delete-filter.entity-not-soft-deletable',
        severity: 'high',
        message: `${rel(file)} — find 메서드가 조회하는 ${entityName}가 soft-delete 불가능합니다(BaseEntity 미상속, @DeleteDateColumn 없음). 삭제된 행이 조회 결과에 섞일 수 있습니다 — Entity에 @DeleteDateColumn을 추가(BaseEntity 상속)하거나 Repository에서 deletedAt IS NULL을 직접 필터링하세요.`,
        docRef: DOC
      })
      score -= penaltyFor('high')
    }

    // raw SQL (.query()) bypasses TypeORM's automatic soft-delete filter — if the raw SQL
    // targets a soft-deletable Entity, the SQL string itself must include a deletedAt filter.
    const softDeletableEntityNames = entities.filter((e) => entitySoftDeleteMap.get(e))
    if (softDeletableEntityNames.length > 0) {
      const rawQueryMatches = [...content.matchAll(/\.query\s*\(\s*(`[\s\S]*?`|'[^']*'|"[^"]*")/g)]
      for (const m of rawQueryMatches) {
        const sql = m[1]
        if (!/deleted_?at\s+is\s+null/i.test(sql)) {
          const line = content.slice(0, m.index ?? 0).split('\n').length
          failures.push({
            ruleId: 'soft-delete-filter.raw-query-missing-filter',
            severity: 'high',
            message: `${rel(file)}:${line} — raw SQL(.query())이 soft-delete 가능한 Entity(${softDeletableEntityNames.join(', ')})를 조회하면서 deletedAt IS NULL 필터가 없습니다. raw SQL은 TypeORM의 자동 soft-delete 필터를 우회하므로 수동으로 필터링해야 합니다.`,
            docRef: DOC
          })
          score -= penaltyFor('high')
        }
      }
    }
  }

  return {
    name: 'soft-delete-filter',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
