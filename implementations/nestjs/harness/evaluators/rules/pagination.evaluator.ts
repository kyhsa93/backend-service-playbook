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

// A thin `class X extends BaseDto {}` wrapper has no page/take field·decorator in its own file
// and inherits them from a parent class. This follows the inheritance chain, gathering content
// up to the actual declaration, and includes it as a target for pagination field/decorator
// checks (up to 3 levels).
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

// Checks whether both the page and take properties are declared (including via inheritance) — used to detect a pagination DTO
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

  // Finds a DTO file with page + take fields and uses it as the applicability gate (including via the inheritance chain)
  const paginationDtoFiles = files.filter((f) => f.includes('dto') && isPaginationDto(read(f)))
  if (paginationDtoFiles.length === 0) {
    return { name: 'pagination', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of paginationDtoFiles) {
    const content = read(file)

    // The page field needs @Type(() => Number) + @IsInt()
    const hasPageType = /@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)[\s\S]{0,100}page\b|page\b[\s\S]{0,200}@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)/.test(content)
    const hasPageInt = /@IsInt\s*\(\s*\)[\s\S]{0,100}page\b|page\b[\s\S]{0,200}@IsInt\s*\(\s*\)/.test(content)

    if (!hasPageType || !hasPageInt) {
      failures.push({
        ruleId: 'pagination.page-decorator-missing',
        severity: 'medium',
        message: `${rel(file)}'s page field needs @Type(() => Number) and @IsInt() decorators.`,
        docRef: DOC
      })
      score -= penaltyFor('medium')
    }

    // The take field needs @Type(() => Number) + @IsInt()
    const hasTakeType = /@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)[\s\S]{0,100}take\b|take\b[\s\S]{0,200}@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)/.test(content)
    const hasTakeInt = /@IsInt\s*\(\s*\)[\s\S]{0,100}take\b|take\b[\s\S]{0,200}@IsInt\s*\(\s*\)/.test(content)

    if (!hasTakeType || !hasTakeInt) {
      failures.push({
        ruleId: 'pagination.take-decorator-missing',
        severity: 'medium',
        message: `${rel(file)}'s take field needs @Type(() => Number) and @IsInt() decorators.`,
        docRef: DOC
      })
      score -= penaltyFor('medium')
    }
  }

  // A generic key like data/items/result on a Repository return value is prohibited — but this
  // is checked only within a pagination response signature (shaped
  // `Promise<{ ...; count: number }>`), not across the whole file. Scanning the whole file
  // would false-positive even on a same-named field unrelated to pagination (e.g. a legitimate
  // domain field like `items: order.items` in a save payload) — it just never surfaced because
  // Account/Card happen to have no such name.
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
        message: `${rel(file)}'s pagination response must not use generic keys like data/items/result. Use a domain-specific plural instead (e.g. orders, users).`,
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
