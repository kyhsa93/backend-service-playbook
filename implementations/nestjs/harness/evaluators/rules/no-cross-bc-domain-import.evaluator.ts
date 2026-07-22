// The no-cross-bc-domain-import evaluator — another Bounded Context's Aggregate may only be
// referenced by ID; an object reference (a direct import) is prohibited. This principle
// applies equally both between Aggregates within the same BC (`no-cross-aggregate-reference`)
// and across a BC boundary (guide: docs/architecture/tactical-ddd.md —
// "another Aggregate may only be referenced by ID (object references are prohibited)").
//
// Check: fails if a `src/<bc>/domain/*.ts` file imports something from
// 'src/<otherBc>/domain/*' where otherBc !== bc. A domain import within the same BC (a normal
// pattern — e.g. refund-eligibility-service.ts importing payment.ts from the same payment BC)
// isn't a target. An import coming from a cross-cutting-concern module with no domain/ layer
// (`isDomainBearing` is false), like common/outbox/database/config, never matches this pattern
// (inside another BC's domain/) to begin with, so it's naturally excluded — for instance,
// `@/payment/payment-enum` (not a domain, the BC root) or `@/common/generate-id` have no
// 'domain/' segment, so they don't match.
//
// Applicability: runs only when there are 2 or more domain-bearing BCs — in a single-domain
// project, a cross-BC violation is structurally impossible.

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

const DOC_REF = '../../docs/architecture/tactical-ddd.md#aggregate-root'

export function evaluateNoCrossBcDomainImport(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const allFiles = walkTsFiles(srcRoot)

  const domainBearingBcs = new Set(
    allFiles.map((f) => domainSegment(root, f)).filter((d): d is string => d !== null && isDomainBearing(root, d))
  )

  const domainFiles = allFiles.filter((f) => classifyLayer(f) === 'domain' && !f.endsWith('.spec.ts'))

  if (domainBearingBcs.size < 2 || domainFiles.length === 0) {
    return { name: 'no-cross-bc-domain-import', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of domainFiles) {
    const ownBc = domainSegment(root, file)
    if (!ownBc) continue

    for (const specifier of parseImports(file)) {
      const resolved = resolveImportPath(root, file, specifier)
      if (!resolved || classifyLayer(resolved) !== 'domain') continue

      const targetBc = domainSegment(root, resolved)
      if (!targetBc || targetBc === ownBc || !domainBearingBcs.has(targetBc)) continue

      failures.push({
        ruleId: 'no-cross-bc-domain-import.cross-bc-domain-import',
        severity: 'high',
        message: `${rel(file)} (${ownBc}) — directly imports another BC's (${targetBc}) domain/: '${specifier}'. Another Aggregate may only be referenced by ID (object references are forbidden)`,
        docRef: DOC_REF
      })
      score -= penaltyFor('high')
    }
  }

  return {
    name: 'no-cross-bc-domain-import',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
