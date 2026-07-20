// domain-layer-isolation evaluator — Domain 레이어는 어떤 레이어에도 의존하지
// 않는다(guide: docs/architecture/layer-architecture.md). `layer-dependency`가
// 프레임워크 이름 블록리스트(@nestjs/*, typeorm)로, `import-graph`가
// domain -> infrastructure 한 방향만 잡는 것과 달리, 이 evaluator는 import
// 경로 자체를 해석해 domain/*.ts가 (자기 도메인이든 다른 도메인이든)
// application/, infrastructure/, interface/ 어디로도 향하는 import를 하지
// 않는지 구조적으로 검증한다.
//
// Applicability: src/**/domain/*.ts 파일이 없으면 skip (maxScore = 0).

import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { classifyLayer, parseImports, resolveImportPath, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = 'docs/architecture/layer-architecture.md#domain-레이어-역할'
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
