import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'

const DOC = 'docs/architecture/persistence.md'

function walkTsFiles(root: string): string[] {
  const out: string[] = []
  if (!fs.existsSync(root)) return out
  for (const entry of fs.readdirSync(root)) {
    if (['node_modules', 'dist', 'coverage', '.git'].includes(entry)) continue
    const full = path.join(root, entry)
    if (fs.statSync(full).isDirectory()) { out.push(...walkTsFiles(full)); continue }
    if (full.endsWith('.ts') && !full.endsWith('.d.ts') && !full.endsWith('.spec.ts')) out.push(full)
  }
  return out
}

export function evaluateDatabaseQueries(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const files = walkTsFiles(srcRoot)
  const entityFiles = files.filter((f) => f.endsWith('.entity.ts'))

  if (entityFiles.length === 0) {
    return { name: 'database-queries', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 20
  const rel = (f: string) => path.relative(root, f)

  // Rule 1: using @PrimaryGeneratedColumn() on an Entity is prohibited → use @PrimaryColumn({ type: 'char', length: 32 })
  for (const file of entityFiles) {
    const content = fs.readFileSync(file, 'utf-8')
    if (/@PrimaryGeneratedColumn\s*\(/.test(content)) {
      failures.push({
        ruleId: 'database-queries.primary-generated-column',
        severity: 'high',
        message: `${rel(file)}에서 @PrimaryGeneratedColumn() 사용 금지 — @PrimaryColumn({ type: 'char', length: 32 })를 사용하고 ID는 generateId()로 생성하세요.`,
        docRef: DOC
      })
      score -= penaltyFor('high')
    }
  }

  // Rule 2: hard delete (.delete()) is prohibited — use softDelete
  const infraFiles = files.filter((f) => f.includes('/infrastructure/'))
  for (const file of infraFiles) {
    const content = fs.readFileSync(file, 'utf-8')
    // Detects the manager.delete( or repository.delete( pattern (as opposed to softDelete)
    if (/\b(manager|repository|this\.\w+)\s*\.\s*delete\s*\(/.test(content) &&
        !/softDelete/.test(content)) {
      failures.push({
        ruleId: 'database-queries.hard-delete-forbidden',
        severity: 'high',
        message: `${rel(file)}에서 .delete() 직접 호출 금지 — softDelete()를 사용해 논리 삭제하세요.`,
        docRef: DOC
      })
      score -= penaltyFor('high')
    }
  }

  // Rule 3: an Entity must extend BaseEntity.
  // Treated as a violation when:
  // - createdAt/updatedAt/deletedAt are all redundantly declared with inline decorators (copied as-is instead of extending BaseEntity)
  // - there are no audit columns at all (BaseEntity inheritance itself is missing)
  // A case that intentionally uses only some audit columns, like an append-only log-style
  // table with only createdAt, doesn't force BaseEntity inheritance (since BaseEntity requires
  // updatedAt/deletedAt, inheriting it would reference columns not in the schema).
  // The BaseEntity definition file itself is excluded from this check.
  for (const file of entityFiles) {
    const content = fs.readFileSync(file, 'utf-8')
    if (/export\s+abstract\s+class\s+\w*BaseEntity/.test(content)) continue

    const auditColumnCount = [
      /@CreateDateColumn/,
      /@UpdateDateColumn/,
      /@DeleteDateColumn/
    ].filter((re) => re.test(content)).length
    const extendsBaseEntity = /extends\s+\w*BaseEntity/.test(content)

    if (!extendsBaseEntity && (auditColumnCount === 3 || auditColumnCount === 0)) {
      failures.push({
        ruleId: 'database-queries.base-entity-missing',
        severity: 'medium',
        message: `${rel(file)}가 BaseEntity를 상속하지 않습니다. createdAt/updatedAt/deletedAt 공통 컬럼은 BaseEntity를 상속해 재사용하세요 (인라인 @CreateDateColumn/@UpdateDateColumn/@DeleteDateColumn 중복 선언 금지).`,
        docRef: DOC
      })
      score -= penaltyFor('medium')
    }
  }

  // Rule 4: whether a TransactionManager file exists
  const hasTxManager = fs.existsSync(path.join(srcRoot, 'database', 'transaction-manager.ts')) ||
    files.some((f) => f.endsWith('transaction-manager.ts'))
  if (!hasTxManager) {
    failures.push({
      ruleId: 'database-queries.transaction-manager-missing',
      severity: 'medium',
      message: 'src/database/transaction-manager.ts 파일이 없습니다. AsyncLocalStorage 기반 TransactionManager가 필요합니다.',
      docRef: DOC
    })
    score -= penaltyFor('medium')
  }

  return {
    name: 'database-queries',
    score: Math.max(score, 0),
    maxScore: 20,
    failures
  }
}
