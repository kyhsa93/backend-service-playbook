// The interface-no-infrastructure evaluator — a Controller (Interface layer) injects and
// calls the Application Service via NestJS DI, and never directly imports an Infrastructure
// implementation (a Repository impl, Query impl, etc.)
// (guide: docs/architecture/layer-architecture.md).
//
// Scope: only targets `src/<domain>/interface/**/*.ts` where `<domain>` is an actual Bounded
// Context — judged by whether `src/<domain>/domain/` exists.
// Why the scope is narrowed this way: src/common/interface/health-controller.ts
// intentionally imports src/common/infrastructure/shutdown-state.ts directly
// (a pattern documented in docs/architecture/graceful-shutdown.md — common has
// interface/application/infrastructure folders but no domain/, making it a cross-cutting-concern
// technical module that isn't a target of the per-BC Adapter-routing principle).
//
// Applicability: skipped if there's no interface/*.ts file satisfying the above condition.

import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import {
  classifyLayer,
  domainSegment,
  isDomainBearing,
  parseImports,
  resolveImportPath,
  walkTsFiles
} from '../shared/ast-utils'

const DOC_REF = 'docs/architecture/layer-architecture.md#interface-layer-responsibilities'

export function evaluateInterfaceNoInfrastructure(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const interfaceFiles = walkTsFiles(srcRoot).filter((f) => {
    if (classifyLayer(f) !== 'interface' || f.endsWith('.spec.ts')) return false
    const domain = domainSegment(root, f)
    return domain !== null && isDomainBearing(root, domain)
  })

  if (interfaceFiles.length === 0) {
    return { name: 'interface-no-infrastructure', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of interfaceFiles) {
    for (const specifier of parseImports(file)) {
      const resolved = resolveImportPath(root, file, specifier)
      if (!resolved) continue

      if (classifyLayer(resolved) !== 'infrastructure') continue

      failures.push({
        ruleId: 'interface-no-infrastructure.forbidden-import',
        severity: 'high',
        message: `${rel(file)} — the Controller imports infrastructure directly: '${specifier}'. Access must go only through an Application Service`,
        docRef: DOC_REF
      })
      score -= penaltyFor('high')
    }
  }

  return {
    name: 'interface-no-infrastructure',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
