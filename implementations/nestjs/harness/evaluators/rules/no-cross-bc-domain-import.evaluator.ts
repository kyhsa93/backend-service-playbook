// no-cross-bc-domain-import evaluator — 다른 Bounded Context의 Aggregate는
// ID 참조만 허용하고 객체 참조(직접 import)는 금지한다. 이 원칙은 같은 BC 안의
// Aggregate 간(`no-cross-aggregate-reference`)뿐 아니라 BC 경계를 넘나드는
// 경우에도 동일하게 적용된다 (guide: docs/architecture/tactical-ddd.md —
// "다른 Aggregate는 ID 참조만 허용한다 (객체 참조 금지)").
//
// Check: src/<bc>/domain/*.ts 파일이 'src/<otherBc>/domain/*'에서 무언가를
// import하고 otherBc !== bc이면 실패. 같은 BC 안의 domain import(정상 패턴,
// 예: refund-eligibility-service.ts가 같은 payment BC의 payment.ts를 import)는
// 대상이 아니다. common/outbox/database/config처럼 domain/ 레이어가 없는
// 횡단 관심사 모듈(`isDomainBearing`이 false)에서 오는 import는애초에 이
// 패턴(otherBc의 domain/ 안)에 걸리지 않으므로 자연히 제외된다 — 예를 들어
// `@/payment/payment-enum`(도메인 아님, BC 루트)이나 `@/common/generate-id`는
// 'domain/' 세그먼트가 없어 매치되지 않는다.
//
// Applicability: domain-bearing BC가 2개 이상일 때만 실행 — 단일 도메인
// 프로젝트에서는 크로스 BC 위반이 구조적으로 불가능하다.

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
        message: `${rel(file)} (${ownBc}) — 다른 BC(${targetBc})의 domain/을 직접 import함: '${specifier}'. 다른 Aggregate는 ID 참조만 허용한다(객체 참조 금지)`,
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
