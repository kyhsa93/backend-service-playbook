// The auth evaluator — verifies whether a Controller route's protected/public intent is stated explicitly
// (guide: docs/architecture/authentication.md).
//
// Applicability: runs if a *-controller.ts file exists (maxScore = 20).
//
// Rules:
// - a Controller class or route method must have @UseGuards (or a composite decorator wrapping
//   it, like this repo's own @Authenticated()) or a public-intent marker like @Public.
// - if there are no Auth/Jwt/Guard-related files at all, it's treated as missing JWT/Bearer authentication setup.

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'

const DOC_REF = 'docs/architecture/authentication.md'
const METHOD_PATTERN = /@(Get|Post|Put|Patch|Delete)\s*\([^)]*\)[\s\S]*?(?:async\s+)?([A-Za-z0-9_]+)\s*\(/g
// @Authenticated() is this repo's own composite decorator (applyDecorators(UseGuards(AuthGuard),
// UseInterceptors(...))) — recognized here as equivalent intent to a bare @UseGuards(...), since
// requiring the verbose form everywhere would undo the point of having a composite decorator.
const PROTECTED_OR_PUBLIC_PATTERN = /@UseGuards\s*\(|@Authenticated\s*\(|@Public\s*\(|@SkipAuth\s*\(|@AllowAnonymous\s*\(/
const AUTH_FILE_PATTERN = /(auth|jwt|guard|strategy)/i

function walkFiles(root: string): string[] {
  const out: string[] = []
  if (!fs.existsSync(root)) return out

  for (const entry of fs.readdirSync(root)) {
    if (entry === 'node_modules' || entry === 'dist' || entry === 'coverage' || entry === '.git') continue
    const fullPath = path.join(root, entry)
    const stat = fs.statSync(fullPath)
    if (stat.isDirectory()) {
      out.push(...walkFiles(fullPath))
      continue
    }
    if (fullPath.endsWith('.ts') && !fullPath.endsWith('.d.ts')) out.push(fullPath)
  }

  return out
}

function lineOf(source: string, index: number): number {
  return source.slice(0, index).split('\n').length
}

function hasAuthInfrastructure(files: string[]): boolean {
  return files.some((file) => AUTH_FILE_PATTERN.test(file) || /UseGuards|JwtStrategy|PassportStrategy|AuthGuard/.test(fs.readFileSync(file, 'utf-8')))
}

export function evaluateAuth(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const files = walkFiles(srcRoot)
  const controllerFiles = files.filter((file) => file.endsWith('controller.ts'))

  if (controllerFiles.length === 0) {
    return { name: 'auth', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 20
  const rel = (file: string) => path.relative(root, file)

  if (!hasAuthInfrastructure(files)) {
    failures.push({
      ruleId: 'auth.jwt-strategy-required',
      severity: 'medium',
      message: 'Controllers exist but no AuthGuard/JwtStrategy/guard-related authentication configuration was found',
      docRef: DOC_REF
    })
    score -= 2
  }

  for (const file of controllerFiles) {
    const content = fs.readFileSync(file, 'utf-8')
    const classHasIntent = PROTECTED_OR_PUBLIC_PATTERN.test(content.slice(0, content.indexOf('export class') > -1 ? content.indexOf('export class') : content.length))

    METHOD_PATTERN.lastIndex = 0
    let methodMatch: RegExpExecArray | null
    let methodCount = 0
    while ((methodMatch = METHOD_PATTERN.exec(content)) !== null) {
      methodCount += 1
      const methodStart = Math.max(0, methodMatch.index - 300)
      const methodDecorators = content.slice(methodStart, methodMatch.index)
      if (classHasIntent || PROTECTED_OR_PUBLIC_PATTERN.test(methodDecorators)) continue

      failures.push({
        ruleId: 'auth.route-intent-required',
        severity: 'medium',
        message: `${rel(file)}:${lineOf(content, methodMatch.index)} the ${methodMatch[2]} route has no @UseGuards, @Authenticated, or @Public intent marker`,
        docRef: DOC_REF
      })
      score -= 2
    }

    if (methodCount > 0 && !PROTECTED_OR_PUBLIC_PATTERN.test(content)) {
      failures.push({
        ruleId: 'auth.controller-intent-required',
        severity: 'medium',
        message: `${rel(file)} has no protection/public intent marker at all (@UseGuards, @Authenticated, or @Public)`,
        docRef: DOC_REF
      })
      score -= 2
    }
  }

  return { name: 'auth', score: Math.max(score, 0), maxScore: 20, failures }
}
