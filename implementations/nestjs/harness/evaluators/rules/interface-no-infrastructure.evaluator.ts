// interface-no-infrastructure evaluator — Controller(Interface 레이어)는
// Application Service를 NestJS DI로 주입받아 호출하고, Infrastructure
// 구현체(Repository impl, Query impl 등)를 직접 import하지 않는다
// (guide: docs/architecture/layer-architecture.md).
//
// Scope: src/<domain>/interface/**/*.ts 중 <domain>이 실제 Bounded Context인
// 경우만 대상으로 한다 — src/<domain>/domain/ 이 존재하는지로 판별한다.
// 이렇게 범위를 좁히는 이유: src/common/interface/health-controller.ts는
// src/common/infrastructure/shutdown-state.ts를 의도적으로 직접 import한다
// (docs/architecture/graceful-shutdown.md에 문서화된 패턴 — common은
// interface/application/infrastructure 폴더는 있지만 domain/이 없는
// 횡단 관심사 기술 모듈이라 BC 단위 Adapter 경유 원칙의 대상이 아니다).
//
// Applicability: 위 조건을 만족하는 interface/*.ts 파일이 없으면 skip.

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

const DOC_REF = 'docs/architecture/layer-architecture.md#interface-레이어-역할'

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
        message: `${rel(file)} — Controller가 infrastructure를 직접 import 함: '${specifier}'. Application Service를 통해서만 접근한다`,
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
