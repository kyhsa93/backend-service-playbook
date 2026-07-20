// no-cross-bc-repository-in-application evaluator — 다른 Bounded Context의
// Repository를 Application 레이어에서 직접 import하지 않는다. 크로스 도메인
// 조회는 Adapter(ACL)를 거친다 — Adapter 인터페이스는 자기 도메인의
// application/adapter/에, 구현체는 infrastructure/에 두고 상대 BC의
// Query(읽기 전용 인터페이스)만 호출한다 (guide: cross-domain-communication.md
// "외부 BC의 Repository나 Service를 Application 레이어에서 직접 주입하지
// 않는다").
//
// Check: src/<domain>/application/**/*.ts 파일이 'src/<otherDomain>/domain/*-repository.ts'
// 형태의 import를 갖고 있고 otherDomain !== domain이면 실패. 같은 도메인 안의
// Repository import(Command Service가 자기 도메인 Repository를 쓰는 정상 패턴)는
// 대상이 아니다.
//
// Applicability: 2개 이상의 domain-bearing BC가 있고, application/ 파일이
// 존재할 때만 실행 (단일 도메인 프로젝트에서는 크로스 도메인 위반이 구조적으로
// 불가능하므로 skip).

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

const DOC_REF = '../../docs/architecture/cross-domain-communication.md#동기-호출--adapter-패턴-acl'

// import specifier가 확장자 없이 resolve되므로(예: '@/user/domain/user-repository')
// '.ts' suffix는 옵션으로 둔다.
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
        message: `${rel(file)} (${ownDomain}) — 다른 BC(${targetDomain})의 Repository를 직접 import함: '${specifier}'. Adapter(ACL)를 거쳐야 한다`,
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
