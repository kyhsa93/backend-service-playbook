// The logging evaluator — verifies direct console usage and error-swallowing in production code
// (guide: docs/architecture/observability.md).
//
// Applicability: runs if there's TypeScript source under src/ (maxScore = 15).
//
// Rules:
// - direct use of console.log/warn/error/debug/info is prohibited
// - fails if a catch block is empty or doesn't log/rethrow the error
// - no logging of any kind in the domain/ layer (NestJS Logger/@nestjs/common import,
//   console.*, winston — all prohibited) — "the Domain layer never uses Logger. The result of
//   domain logic is logged from the Application layer"
//   (observability.md's per-layer logging criteria table)

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'

const DOC_REF = 'docs/architecture/observability.md'
const DOMAIN_LOGGING_DOC_REF = `${DOC_REF}#per-layer-logging-criteria`
const CONSOLE_PATTERN = /\bconsole\.(log|warn|error|debug|info)\s*\(/g
const EMPTY_CATCH_PATTERN = /catch\s*\([^)]*\)\s*\{\s*\}/g
const CATCH_BLOCK_PATTERN = /catch\s*\([^)]*\)\s*\{([\s\S]*?)\}/g
const HANDLED_ERROR_PATTERN = /\b(logger|this\.logger|Logger)\.(error|warn|log|debug)\s*\(|\bthrow\b/
const NESTJS_LOGGER_NAMED_IMPORT_PATTERN = /import\s*\{[^}]*\bLogger\b[^}]*\}\s*from\s*['"]@nestjs\/common['"]/
const WINSTON_IMPORT_PATTERN = /from\s*['"]winston['"]/
const DOMAIN_LOGGER_USAGE_PATTERN = /\bnew\s+Logger\s*\(|\blogger\.(log|error|warn|debug|verbose)\s*\(/

function walkTsFiles(root: string): string[] {
  const out: string[] = []
  if (!fs.existsSync(root)) return out

  for (const entry of fs.readdirSync(root)) {
    if (entry === 'node_modules' || entry === 'dist' || entry === 'coverage' || entry === '.git') continue
    const fullPath = path.join(root, entry)
    const stat = fs.statSync(fullPath)
    if (stat.isDirectory()) {
      out.push(...walkTsFiles(fullPath))
      continue
    }
    if (fullPath.endsWith('.ts') && !fullPath.endsWith('.d.ts') && !fullPath.endsWith('.spec.ts')) {
      out.push(fullPath)
    }
  }

  return out
}

function lineOf(source: string, index: number): number {
  return source.slice(0, index).split('\n').length
}

export function evaluateLogging(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const files = walkTsFiles(srcRoot)
  if (files.length === 0) {
    return { name: 'logging', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (file: string) => path.relative(root, file)

  for (const file of files) {
    const content = fs.readFileSync(file, 'utf-8')

    CONSOLE_PATTERN.lastIndex = 0
    let consoleMatch: RegExpExecArray | null
    while ((consoleMatch = CONSOLE_PATTERN.exec(content)) !== null) {
      failures.push({
        ruleId: 'logging.no-console',
        severity: 'medium',
        message: `${rel(file)}:${lineOf(content, consoleMatch.index)} uses console.${consoleMatch[1]} directly — must use Logger instead`,
        docRef: DOC_REF
      })
      score -= 2
    }

    EMPTY_CATCH_PATTERN.lastIndex = 0
    let emptyCatchMatch: RegExpExecArray | null
    while ((emptyCatchMatch = EMPTY_CATCH_PATTERN.exec(content)) !== null) {
      failures.push({
        ruleId: 'logging.no-empty-catch',
        severity: 'high',
        message: `${rel(file)}:${lineOf(content, emptyCatchMatch.index)}'s catch block is empty — must log the error or rethrow it`,
        docRef: DOC_REF
      })
      score -= 4
    }

    CATCH_BLOCK_PATTERN.lastIndex = 0
    let catchMatch: RegExpExecArray | null
    while ((catchMatch = CATCH_BLOCK_PATTERN.exec(content)) !== null) {
      const block = catchMatch[1]
      if (block.trim().length === 0) continue
      if (HANDLED_ERROR_PATTERN.test(block)) continue
      failures.push({
        ruleId: 'logging.no-swallowed-error',
        severity: 'high',
        message: `${rel(file)}:${lineOf(content, catchMatch.index)}'s catch block neither logs the error nor rethrows it`,
        docRef: DOC_REF
      })
      score -= 4
    }

    if (file.replace(/\\/g, '/').includes('/domain/')) {
      const usesNestjsLogger = NESTJS_LOGGER_NAMED_IMPORT_PATTERN.test(content) || DOMAIN_LOGGER_USAGE_PATTERN.test(content)
      const usesWinston = WINSTON_IMPORT_PATTERN.test(content)
      if (usesNestjsLogger || usesWinston) {
        failures.push({
          ruleId: 'logging.no-logging-in-domain',
          severity: 'high',
          message: `${rel(file)} — logging is forbidden in the domain layer (${usesWinston ? 'winston' : '@nestjs/common Logger'}). The result of domain logic must be logged in the Application layer`,
          docRef: DOMAIN_LOGGING_DOC_REF
        })
        score -= 4
      }
    }
  }

  return { name: 'logging', score: Math.max(score, 0), maxScore: 15, failures }
}
