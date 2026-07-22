// The no-cross-bc-repository-in-application evaluator — another Bounded Context's Repository
// is never directly imported in the Application layer. A cross-domain lookup goes through an
// Adapter (ACL) — the Adapter interface lives in this domain's own application/adapter/, its
// implementation in infrastructure/, calling only the counterpart BC's Query (a read-only
// interface) (guide: cross-domain-communication.md —
// "never inject an external BC's Repository or Service directly in the Application layer").
//
// Check: fails if a `src/<domain>/application/**/*.ts` file has an import shaped like
// 'src/<otherDomain>/domain/*-repository.ts' where otherDomain !== domain. A Repository import
// within the same domain (the normal pattern of a Command Service using its own domain's
// Repository) isn't a target.
//
// Applicability: runs only when there are 2 or more domain-bearing BCs and application/ files
// exist (skipped in a single-domain project, where a cross-domain violation is structurally impossible).

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

const DOC_REF = '../../docs/architecture/cross-domain-communication.md#synchronous-calls--the-adapter-pattern-acl'

// Since an import specifier resolves with no extension (e.g. '@/user/domain/user-repository'), the '.ts' suffix is left optional.
function isRepositoryFile(filePath: string): boolean {
  return /-repository(\.ts)?$/.test(filePath.replace(/\\/g, '/')) && classifyLayer(filePath) === 'domain'
}

export function evaluateNoCrossBcRepositoryInApplication(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const allFiles = walkTsFiles(srcRoot)

  const domainBearingCount = new Set(
    allFiles.map((f) => domainSegment(root, f)).filter((d): d is string => d !== null && isDomainBearing(root, d))
  ).size

  const applicationFiles = allFiles.filter((f) => classifyLayer(f) === 'application' && !f.endsWith('.spec.ts'))

  if (domainBearingCount < 2 || applicationFiles.length === 0) {
    return { name: 'no-cross-bc-repository-in-application', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of applicationFiles) {
    const ownDomain = domainSegment(root, file)
    if (!ownDomain) continue

    for (const specifier of parseImports(file)) {
      const resolved = resolveImportPath(root, file, specifier)
      if (!resolved || !isRepositoryFile(resolved)) continue

      const targetDomain = domainSegment(root, resolved)
      if (!targetDomain || targetDomain === ownDomain) continue

      failures.push({
        ruleId: 'no-cross-bc-repository-in-application.cross-domain-repository-import',
        severity: 'high',
        message: `${rel(file)} (${ownDomain}) — directly imports another BC's (${targetDomain}) Repository: '${specifier}'. It must go through an Adapter (ACL)`,
        docRef: DOC_REF
      })
      score -= penaltyFor('high')
    }
  }

  return {
    name: 'no-cross-bc-repository-in-application',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
