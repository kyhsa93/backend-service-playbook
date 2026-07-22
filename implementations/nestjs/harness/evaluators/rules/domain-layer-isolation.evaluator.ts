// The domain-layer-isolation evaluator — the Domain layer doesn't depend on any layer
// (guide: docs/architecture/layer-architecture.md). Unlike `layer-dependency` (which uses a
// blocklist of framework names — @nestjs/*, typeorm) and `import-graph` (which catches only
// the one direction domain -> infrastructure), this evaluator resolves the import path itself
// to structurally verify that `domain/*.ts` (whether its own domain or another domain) never
// imports toward application/, infrastructure/, or interface/.
//
// Applicability: skipped if there's no src/**/domain/*.ts file (maxScore = 0).

import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { classifyLayer, parseImports, resolveImportPath, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = 'docs/architecture/layer-architecture.md#domain-layer-responsibilities'
const FORBIDDEN_TARGETS = new Set(['application', 'infrastructure', 'interface'])

export function evaluateDomainLayerIsolation(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const domainFiles = walkTsFiles(srcRoot).filter((f) => classifyLayer(f) === 'domain' && !f.endsWith('.spec.ts'))

  if (domainFiles.length === 0) {
    return { name: 'domain-layer-isolation', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 20
  const rel = (f: string) => path.relative(root, f)

  for (const file of domainFiles) {
    for (const specifier of parseImports(file)) {
      const resolved = resolveImportPath(root, file, specifier)
      if (!resolved) continue // bare package specifier — not project-internal

      const targetLayer = classifyLayer(resolved)
      if (!FORBIDDEN_TARGETS.has(targetLayer)) continue

      failures.push({
        ruleId: 'domain-layer-isolation.forbidden-import',
        severity: 'high',
        message: `${rel(file)} — domain 레이어에서 ${targetLayer} 레이어를 import 함: '${specifier}'. Domain은 어떤 레이어에도 의존하지 않는다`,
        docRef: DOC_REF
      })
      score -= penaltyFor('high')
    }
  }

  return {
    name: 'domain-layer-isolation',
    score: Math.max(score, 0),
    maxScore: 20,
    failures
  }
}
