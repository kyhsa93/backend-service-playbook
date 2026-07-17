import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'

const DOC = 'docs/architecture/api-response.md'

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

function resolveImportPath(moduleSpecifier: string, fromFile: string, srcRoot: string): string {
  const withExt = moduleSpecifier.endsWith('.ts') ? moduleSpecifier : `${moduleSpecifier}.ts`
  if (moduleSpecifier.startsWith('@/')) return path.join(srcRoot, withExt.slice(2))
  return path.resolve(path.dirname(fromFile), withExt)
}

function findExtendsBaseName(content: string): string | null {
  const match = content.match(/class\s+\w+\s+extends\s+(\w+)/)
  return match ? match[1] : null
}

function findImportModuleFor(content: string, name: string): string | null {
  const importRegex = /import\s*\{([^}]*)\}\s*from\s*['"]([^'"]+)['"]/g
  let m: RegExpExecArray | null
  while ((m = importRegex.exec(content))) {
    const names = m[1].split(',').map((s) => s.trim())
    if (names.includes(name)) return m[2]
  }
  return null
}

// 얇은 `class X extends BaseDto {}` 래퍼는 자기 파일에 page/take 필드·데코레이터가 없고
// 부모 클래스에서 상속받는다. 상속 체인을 따라가며 실제 선언부까지의 내용을 모아
// 페이지네이션 필드/데코레이터 검사 대상에 포함시킨다 (최대 3단계).
function collectContentWithBases(filePath: string, srcRoot: string, depth = 0): string {
  const content = fs.readFileSync(filePath, 'utf-8')
  if (depth >= 3) return content

  const baseName = findExtendsBaseName(content)
  if (!baseName) return content

  const modulePath = findImportModuleFor(content, baseName)
  if (!modulePath) return content

  const resolved = resolveImportPath(modulePath, filePath, srcRoot)
  if (!fs.existsSync(resolved)) return content

  return `${content}\n${collectContentWithBases(resolved, srcRoot, depth + 1)}`
}

// page와 take 프로퍼티가 (상속 포함) 모두 선언되어 있는지 확인 (pagination DTO 판별)
function isPaginationDto(content: string): boolean {
  return /\bpage\b/.test(content) && /\btake\b/.test(content)
}

export function evaluatePagination(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const files = walkTsFiles(srcRoot)
  const contentOf = new Map<string, string>()
  const read = (f: string): string => {
    let c = contentOf.get(f)
    if (c === undefined) {
      c = collectContentWithBases(f, srcRoot)
      contentOf.set(f, c)
    }
    return c
  }

  // page + take 필드가 있는 DTO 파일을 찾아 gate 조건으로 사용 (상속 체인 포함)
  const paginationDtoFiles = files.filter((f) => f.includes('dto') && isPaginationDto(read(f)))
  if (paginationDtoFiles.length === 0) {
    return { name: 'pagination', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of paginationDtoFiles) {
    const content = read(file)

    // page 필드에 @Type(() => Number) + @IsInt() 필요
    const hasPageType = /@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)[\s\S]{0,100}page\b|page\b[\s\S]{0,200}@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)/.test(content)
    const hasPageInt = /@IsInt\s*\(\s*\)[\s\S]{0,100}page\b|page\b[\s\S]{0,200}@IsInt\s*\(\s*\)/.test(content)

    if (!hasPageType || !hasPageInt) {
      failures.push({
        ruleId: 'pagination.page-decorator-missing',
        severity: 'medium',
        message: `${rel(file)}의 page 필드에 @Type(() => Number)와 @IsInt() 데코레이터가 필요합니다.`,
        docRef: DOC
      })
      score -= penaltyFor('medium')
    }

    // take 필드에 @Type(() => Number) + @IsInt() 필요
    const hasTakeType = /@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)[\s\S]{0,100}take\b|take\b[\s\S]{0,200}@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)/.test(content)
    const hasTakeInt = /@IsInt\s*\(\s*\)[\s\S]{0,100}take\b|take\b[\s\S]{0,200}@IsInt\s*\(\s*\)/.test(content)

    if (!hasTakeType || !hasTakeInt) {
      failures.push({
        ruleId: 'pagination.take-decorator-missing',
        severity: 'medium',
        message: `${rel(file)}의 take 필드에 @Type(() => Number)와 @IsInt() 데코레이터가 필요합니다.`,
        docRef: DOC
      })
      score -= penaltyFor('medium')
    }
  }

  // Repository 반환값에 data/items/result 같은 범용 키 사용 금지 — 단, 파일 전체가 아니라
  // 페이지네이션 응답 시그니처(`Promise<{ ...; count: number }>` 형태) 안에서만 검사한다.
  // 파일 전체를 훑으면 페이지네이션과 무관한 동명 필드(예: 저장 payload의 `items: order.items`
  // 같은 정당한 도메인 필드)까지 오탐한다 — Account/Card에는 우연히 이런 이름이 없어 드러나지
  // 않았을 뿐이다.
  const repoFiles = files.filter((f) => f.endsWith('-repository.ts') || f.endsWith('-repository-impl.ts'))
  for (const file of repoFiles) {
    const content = fs.readFileSync(file, 'utf-8')
    const promiseObjectPattern = /Promise<\{([^}]*)\}>/g
    let match: RegExpExecArray | null
    let flagged = false
    while (!flagged && (match = promiseObjectPattern.exec(content)) !== null) {
      const body = match[1]
      if (/\b(data|items|result)\s*:/.test(body) && /\bcount\b/.test(body)) flagged = true
    }
    if (flagged) {
      failures.push({
        ruleId: 'pagination.generic-response-key',
        severity: 'medium',
        message: `${rel(file)}의 페이지네이션 응답에 data/items/result 범용 키를 사용하지 마세요. 도메인 복수형(예: orders, users)을 사용하세요.`,
        docRef: DOC
      })
      score -= penaltyFor('medium')
    }
  }

  return {
    name: 'pagination',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
