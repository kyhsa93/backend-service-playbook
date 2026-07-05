import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorResult, EvaluatorFailure } from '../shared/types'

// Shared infra dirs that are not domain directories
const SHARED_DIRS = new Set(['common', 'database', 'outbox', 'task-queue', 'config'])
const REQUIRED_LAYERS = ['domain', 'application', 'interface', 'infrastructure']

function listDomains(srcDir: string): string[] {
  if (!fs.existsSync(srcDir)) return []
  return fs.readdirSync(srcDir, { withFileTypes: true })
    .filter(e => e.isDirectory() && !SHARED_DIRS.has(e.name))
    .map(e => e.name)
}

export function evaluateStructure(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 25

  const srcDir = path.join(root, 'src')
  if (!fs.existsSync(srcDir)) {
    return {
      name: 'structure',
      score: 0,
      maxScore: 25,
      failures: [{
        ruleId: 'structure.src.missing',
        severity: 'critical',
        message: 'src/ 디렉토리가 없습니다',
        docRef: 'docs/architecture/directory-structure.md'
      }]
    }
  }

  const domains = listDomains(srcDir)
  if (domains.length === 0) {
    return {
      name: 'structure',
      score: 0,
      maxScore: 25,
      failures: [{
        ruleId: 'structure.domain.none',
        severity: 'critical',
        message: 'src/ 아래에 도메인 디렉토리가 하나도 없습니다',
        docRef: 'docs/architecture/directory-structure.md'
      }]
    }
  }

  for (const domain of domains) {
    const domainDir = path.join(srcDir, domain)
    for (const layer of REQUIRED_LAYERS) {
      const layerDir = path.join(domainDir, layer)
      if (!fs.existsSync(layerDir)) {
        failures.push({
          ruleId: `structure.layer.missing`,
          severity: 'high',
          message: `도메인 '${domain}'에 레이어 디렉토리가 없음: ${layer}/`,
          docRef: 'docs/architecture/directory-structure.md'
        })
        score -= 5
      }
    }
  }

  return { name: 'structure', score: Math.max(score, 0), maxScore: 25, failures }
}
