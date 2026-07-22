// The config-validation evaluator — verifies ConfigModule setup, environment variable
// validation, and the scope of direct process.env references (guide: docs/architecture/config.md).
//
// Applicability: runs if there's a src/config directory or ConfigModule-using code (maxScore = 20).
//
// Rules:
// - ConfigModule.forRoot() must have a validationSchema or validate option.
// - Directly referencing process.env outside of src/config/*.config.ts fails.
// - Only files named src/config/*.config.ts are recognized as a config factory.

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'

const DOC_REF = 'docs/architecture/config.md'
const PROCESS_ENV_PATTERN = /\bprocess\.env\b/g

function walkFiles(root: string, predicate: (file: string) => boolean): string[] {
  const out: string[] = []
  if (!fs.existsSync(root)) return out

  for (const entry of fs.readdirSync(root)) {
    if (entry === 'node_modules' || entry === 'dist' || entry === 'coverage' || entry === '.git') continue
    const fullPath = path.join(root, entry)
    const stat = fs.statSync(fullPath)
    if (stat.isDirectory()) {
      out.push(...walkFiles(fullPath, predicate))
      continue
    }
    if (predicate(fullPath)) out.push(fullPath)
  }

  return out
}

function isConfigFactoryFile(root: string, file: string): boolean {
  const rel = path.relative(root, file).replace(/\\/g, '/')
  return /^src\/config\/[^/]+\.config\.ts$/.test(rel)
}

function isTypeScriptSource(file: string): boolean {
  return file.endsWith('.ts') && !file.endsWith('.d.ts') && !file.endsWith('.spec.ts')
}

function hasConfigModuleUsage(files: string[]): boolean {
  return files.some((file) => fs.readFileSync(file, 'utf-8').includes('ConfigModule'))
}

function hasForRootWithoutValidation(content: string): boolean {
  const forRootIndex = content.indexOf('ConfigModule.forRoot')
  if (forRootIndex < 0) return false

  const after = content.slice(forRootIndex, forRootIndex + 1200)
  return !/\bvalidationSchema\b|\bvalidate\b/.test(after)
}

export function evaluateConfigValidation(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const configDir = path.join(srcRoot, 'config')
  const tsFiles = walkFiles(srcRoot, isTypeScriptSource)

  if (!fs.existsSync(configDir) && !hasConfigModuleUsage(tsFiles)) {
    return { name: 'config-validation', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 20
  const rel = (file: string) => path.relative(root, file)

  const configFiles = tsFiles.filter((file) => path.relative(root, file).replace(/\\/g, '/').startsWith('src/config/'))
  for (const file of configFiles) {
    if (!file.endsWith('.config.ts') && !file.endsWith('/index.ts')) {
      failures.push({
        ruleId: 'config.file-naming',
        severity: 'low',
        message: `${rel(file)} does not follow the config factory file naming convention (*.config.ts)`,
        docRef: DOC_REF
      })
      score -= 1
    }
  }

  for (const file of tsFiles) {
    const content = fs.readFileSync(file, 'utf-8')

    if (hasForRootWithoutValidation(content)) {
      failures.push({
        ruleId: 'config.validation-required',
        severity: 'high',
        message: `${rel(file)}'s ConfigModule.forRoot() has no validationSchema or validate option`,
        docRef: DOC_REF
      })
      score -= 4
    }

    if (!isConfigFactoryFile(root, file) && PROCESS_ENV_PATTERN.test(content)) {
      failures.push({
        ruleId: 'config.process-env-direct-access',
        severity: 'medium',
        message: `${rel(file)} references process.env directly — it must be encapsulated in src/config/*.config.ts`,
        docRef: DOC_REF
      })
      score -= 2
    }
    PROCESS_ENV_PATTERN.lastIndex = 0
  }

  return { name: 'config-validation', score: Math.max(score, 0), maxScore: 20, failures }
}
