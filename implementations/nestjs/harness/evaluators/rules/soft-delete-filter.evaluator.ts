// soft-delete-filter evaluator — persistence.md "Soft Delete" 규칙.
//
// 이 코드베이스의 soft-delete 메커니즘은 TypeORM의 자동 필터링이다: Entity가
// `@DeleteDateColumn()`(직접 선언 또는 BaseEntity 상속)을 가지면, QueryBuilder/Repository API의
// find 계열 메서드는 `.withDeleted()`를 명시하지 않는 한 자동으로 `deletedAt IS NULL`을 적용한다.
// (database-queries evaluator가 이미 "hard delete(.delete()) 금지"와 "BaseEntity 상속"을 따로
// 검사하지만, 그 규칙은 Entity 단독으로만 보고 Repository 구현체가 실제로 soft-delete 가능한
// Entity를 조회하는지는 연결해서 보지 않는다 — 이 evaluator가 그 연결을 담당한다.)
//
// Rules:
// - `*-repository-impl.ts`에 `find<X>` 메서드가 있고, 그 메서드가 `@InjectRepository(XEntity)`로
//   주입된 Entity를 조회한다면, 해당 Entity는 soft-delete 가능해야 한다(BaseEntity 상속 또는
//   자체 `@DeleteDateColumn()`) — 파일 자체에서 수동으로 `deletedAt` 필터를 걸지 않는 한.
//   (append-only 로그성 Entity처럼 의도적으로 soft-delete를 쓰지 않는 경우는 해당 Entity를 조회하는
//   `find` 메서드 자체가 없는 것이 일반적이므로 원천적으로 이 규칙 대상이 아니다.)
// - Repository 구현체가 raw SQL(`.query(...)`)로 soft-delete 가능한 Entity를 조회하면서
//   `deletedAt IS NULL`을 SQL에 직접 포함하지 않으면 실패 — raw SQL은 TypeORM의 자동 필터를
//   우회하기 때문에 수동으로 필터링해야 한다.

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

// 생성자의 @InjectRepository(XEntity) 파라미터에서 주입된 Entity 클래스명을 추출한다.
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

// 파일 안에서 `deletedAt`을 직접 필터링하는 흔적(수동 WHERE) — IsNull()/IS NULL 등.
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

  // 프로젝트 전역 Entity 클래스명 → soft-delete 가능 여부 맵.
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
      if (!entitySoftDeleteMap.has(entityName)) continue // 프로젝트 밖 타입 등 — 판단 불가, 스킵
      if (entitySoftDeleteMap.get(entityName)) continue // soft-delete 가능한 Entity — TypeORM 자동 필터 적용
      if (manuallyFiltered) continue // Entity는 soft-delete 컬럼이 없지만 수동으로 deletedAt을 필터링 — 허용

      failures.push({
        ruleId: 'soft-delete-filter.entity-not-soft-deletable',
        severity: 'high',
        message: `${rel(file)} — find 메서드가 조회하는 ${entityName}가 soft-delete 불가능합니다(BaseEntity 미상속, @DeleteDateColumn 없음). 삭제된 행이 조회 결과에 섞일 수 있습니다 — Entity에 @DeleteDateColumn을 추가(BaseEntity 상속)하거나 Repository에서 deletedAt IS NULL을 직접 필터링하세요.`,
        docRef: DOC
      })
      score -= penaltyFor('high')
    }

    // raw SQL(.query())은 TypeORM의 자동 soft-delete 필터를 우회한다 — soft-delete 가능한
    // Entity를 대상으로 한 raw SQL이면 SQL 문자열 자체에 deletedAt 필터가 있어야 한다.
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
