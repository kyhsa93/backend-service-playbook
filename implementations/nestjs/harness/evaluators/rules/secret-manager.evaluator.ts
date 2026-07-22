// The secret-manager evaluator — verifies that a src/config/*.config.ts factory doesn't
// source a sensitive key from process.env alone, and has a Secrets Manager path in place
// (guide: docs/architecture/secret-manager.md).
//
// Applicability: runs only if the src/config/ directory exists (maxScore = 10).
//
// Rule:
// - If a `process.env.*` reference in a *.config.ts file has a key name containing
//   PASSWORD/SECRET/API_KEY/APIKEY/TOKEN, it fails unless that same file has at least one of a
//   NODE_ENV branch, SecretsManagerClient, SecretService, secretService, or getSecret.
//   (Detecting a fake guard isn't handled here — this is a text heuristic.)

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'

const DOC_REF = 'docs/architecture/secret-manager.md'
const SENSITIVE_KEY_PATTERN = /process\.env\.([A-Z_]*(?:PASSWORD|SECRET|APIKEY|API_KEY|TOKEN)[A-Z_]*)/g
const GUARD_PATTERN = /\bNODE_ENV\b|\bSecretsManagerClient\b|\bSecretService\b|\bsecretService\b|\bgetSecret\b/

export function evaluateSecretManager(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  const configDir = path.join(root, 'src', 'config')
  if (!fs.existsSync(configDir) || !fs.statSync(configDir).isDirectory()) {
    return { name: 'secret-manager', score: 0, maxScore: 0, failures: [] }
  }

  let score = 10
  const rel = (f: string) => path.relative(root, f)

  const configFiles = fs.readdirSync(configDir)
    .filter((name) => name.endsWith('.config.ts'))
    .map((name) => path.join(configDir, name))

  for (const file of configFiles) {
    const content = fs.readFileSync(file, 'utf-8')
    const sensitiveKeys = new Set<string>()
    SENSITIVE_KEY_PATTERN.lastIndex = 0
    let m: RegExpExecArray | null
    while ((m = SENSITIVE_KEY_PATTERN.exec(content)) !== null) {
      sensitiveKeys.add(m[1])
    }
    if (sensitiveKeys.size === 0) continue
    if (GUARD_PATTERN.test(content)) continue
    failures.push({
      ruleId: 'secret-manager.config.sensitive-env-without-guard',
      severity: 'high',
      message: `${rel(file)} reads sensitive keys (${[...sensitiveKeys].join(', ')}) only from process.env — a NODE_ENV branch or SecretService/SecretsManagerClient is required`,
      docRef: DOC_REF
    })
    score -= 4
  }

  return { name: 'secret-manager', score: Math.max(score, 0), maxScore: 10, failures }
}
