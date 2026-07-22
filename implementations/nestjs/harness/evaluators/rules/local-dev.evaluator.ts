import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'

const DOC = 'docs/architecture/local-dev.md'

export function evaluateLocalDev(root: string): EvaluatorResult {
  const composePath = path.join(root, 'docker-compose.yml')
  const composeYmlPath = path.join(root, 'docker-compose.yaml')
  const composefile = fs.existsSync(composePath)
    ? composePath
    : fs.existsSync(composeYmlPath)
      ? composeYmlPath
      : null

  if (!composefile) {
    return { name: 'local-dev', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const content = fs.readFileSync(composefile, 'utf-8')

  // A postgres service definition
  if (!/postgres/i.test(content)) {
    failures.push({
      ruleId: 'local-dev.postgres-service-missing',
      severity: 'high',
      message: 'docker-compose.yml has no postgres service.',
      docRef: DOC
    })
    score -= penaltyFor('high')
  }

  // A healthcheck definition
  if (!/healthcheck/i.test(content)) {
    failures.push({
      ruleId: 'local-dev.healthcheck-missing',
      severity: 'medium',
      message: 'The docker-compose.yml service has no healthcheck. It is needed for depends_on condition: service_healthy.',
      docRef: DOC
    })
    score -= penaltyFor('medium')
  }

  // .env.development or .env.example exists
  const hasEnvFile =
    fs.existsSync(path.join(root, '.env.development')) ||
    fs.existsSync(path.join(root, '.env.example')) ||
    fs.existsSync(path.join(root, '.env'))
  if (!hasEnvFile) {
    failures.push({
      ruleId: 'local-dev.env-file-missing',
      severity: 'low',
      message: 'The .env.development or .env.example file is missing.',
      docRef: DOC
    })
    score -= penaltyFor('low')
  }

  return {
    name: 'local-dev',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
