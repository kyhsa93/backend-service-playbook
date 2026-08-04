import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { walkTsFiles } from '../shared/ast-utils'

// Request DTOs follow the naming convention <action>-<domain>-request-body.ts /
// *-request-querystring.ts / *-request-param.ts under interface/dto/ (see
// docs/conventions.md). Response DTOs (*-response-body.ts) carry no validation
// decorators, so only request DTOs are checked here.
const REQUEST_DTO_SUFFIXES = ['-request-body.ts', '-request-querystring.ts', '-request-param.ts']

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

function resolveImportPath(moduleSpecifier: string, fromFile: string, srcRoot: string): string {
  const withExt = moduleSpecifier.endsWith('.ts') ? moduleSpecifier : `${moduleSpecifier}.ts`
  if (moduleSpecifier.startsWith('@/')) return path.join(srcRoot, withExt.slice(2))
  return path.resolve(path.dirname(fromFile), withExt)
}

// A thin `class X extends SomeQuery {}` request DTO declares no fields of its own and
// inherits the validation decorators from its parent class (e.g. an Application-layer Query).
// Follow the inheritance chain (up to 3 levels) so those DTOs are not falsely flagged.
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

export function evaluateDtoValidation(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 25

  const srcRoot = path.join(root, 'src')
  const files = walkTsFiles(srcRoot).filter(
    (file) =>
      file.split(path.sep).includes('dto') &&
      REQUEST_DTO_SUFFIXES.some((suffix) => file.endsWith(suffix))
  )

  for (const file of files) {
    const content = collectContentWithBases(file, srcRoot)

    if (!content.includes('@Is') && !content.includes('@Validate')) {
      failures.push({
        ruleId: 'checklist.step6.dto.validation-missing',
        severity: 'medium',
        message: file
      })
      score -= 5
    }
  }

  return {
    name: 'dto-validation',
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  }
}
