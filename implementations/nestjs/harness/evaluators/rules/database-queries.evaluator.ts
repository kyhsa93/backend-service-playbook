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

  // Rule 1: Entity에 @PrimaryGeneratedColumn() 사용 금지 → @PrimaryColumn({ type: 'char', length: 32 }) 사용
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

  // Rule 2: hard delete(.delete()) 금지 — softDelete 사용
  const infraFiles = files.filter((f) => f.includes('/infrastructure/'))
  for (const file of infraFiles) {
    const content = fs.readFileSync(file, 'utf-8')
    // manager.delete(나 repository.delete( 패턴 감지 (softDelete가 아닌 것)
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

  // Rule 3: Entity는 BaseEntity를 상속해야 한다.
  // - createdAt/updatedAt/deletedAt을 모두 인라인 데코레이터로 중복 선언 (BaseEntity를 상속하지 않고 그대로 베낀 경우)
  // - 감사 컬럼이 하나도 없는 경우 (BaseEntity 상속 자체가 누락된 경우)
  // 는 위반으로 본다. createdAt만 있는 append-only 로그성 테이블처럼 감사 컬럼 일부만 의도적으로
  // 사용하는 경우는 BaseEntity 상속을 강제하지 않는다 (BaseEntity는 updatedAt/deletedAt이 필수라
  // 상속 시 스키마에 없는 컬럼을 참조하게 되기 때문).
  // BaseEntity 정의 파일 자신은 검사 대상에서 제외한다.
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

  // Rule 4: TransactionManager 파일 존재 여부
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
