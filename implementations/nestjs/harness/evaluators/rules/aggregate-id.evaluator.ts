import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'

const DOC = 'docs/architecture/aggregate-id.md'

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

export function evaluateAggregateId(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const files = walkTsFiles(srcRoot)
  const entityFiles = files.filter((f) => f.endsWith('.entity.ts'))

  if (entityFiles.length === 0) {
    return { name: 'aggregate-id', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of entityFiles) {
    const content = fs.readFileSync(file, 'utf-8')

    // Using @PrimaryGeneratedColumn is prohibited
    if (/@PrimaryGeneratedColumn\s*\(/.test(content)) {
      failures.push({
        ruleId: 'aggregate-id.primary-generated-column-forbidden',
        severity: 'high',
        message: `${rel(file)} must not use @PrimaryGeneratedColumn() — generate the ID in the application with generateId() and use @PrimaryColumn({ type: 'char', length: 32 }).`,
        docRef: DOC
      })
      score -= penaltyFor('high')
      continue
    }

    // If @PrimaryColumn exists, verify type: 'char' length: 32
    if (/@PrimaryColumn\s*\(/.test(content)) {
      const primaryColMatch = /@PrimaryColumn\s*\(([^)]*)\)/.exec(content)
      if (primaryColMatch) {
        const opts = primaryColMatch[1]
        const hasChar = /type\s*:\s*['"]char['"]/.test(opts)
        const hasLength = /length\s*:\s*32\b/.test(opts)
        if (!hasChar || !hasLength) {
          failures.push({
            ruleId: 'aggregate-id.primary-column-type',
            severity: 'medium',
            message: `${rel(file)}'s @PrimaryColumn options are not { type: 'char', length: 32 }. Use the char(32) type for Aggregate IDs.`,
            docRef: DOC
          })
          score -= penaltyFor('medium')
        }
      }
    }
  }

  // Check whether the generateId() function exists
  const generateIdFile = files.find((f) => {
    const content = fs.readFileSync(f, 'utf-8')
    return /export\s+(function\s+generateId|const\s+generateId)/.test(content)
  })
  if (!generateIdFile) {
    failures.push({
      ruleId: 'aggregate-id.generate-id-missing',
      severity: 'medium',
      message: 'The generateId() function is missing. Create a crypto.randomUUID()-based ID generation function at src/common/generate-id.ts.',
      docRef: DOC
    })
    score -= penaltyFor('medium')
  } else {
    // If generateId() exists, verify it actually strips the hyphens — returning randomUUID()
    // with the hyphens intact violates the guide's 32-character hex rule. Checks via regex
    // whether it's stripped with .replace(/-/g, '') or .replaceAll('-', '') (since this is
    // runtime behavior that AST alone can't verify the value of, a source-pattern check is used instead).
    const content = fs.readFileSync(generateIdFile, 'utf-8')
    const stripsHyphens = /\.replace\s*\(\s*\/-\/g\s*,\s*(['"`])\1\s*\)/.test(content)
      || /\.replaceAll\s*\(\s*['"`]-['"`]\s*,\s*(['"`])\1\s*\)/.test(content)
    if (/randomUUID\s*\(/.test(content) && !stripsHyphens) {
      failures.push({
        ruleId: 'aggregate-id.generate-id-raw-uuid',
        severity: 'high',
        message: `${rel(generateIdFile)}'s generateId() returns randomUUID() as-is without stripping hyphens — use .replace(/-/g, '') to strip the hyphens and produce a 32-character hex string.`,
        docRef: DOC
      })
      score -= penaltyFor('high')
    }
  }

  return {
    name: 'aggregate-id',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
