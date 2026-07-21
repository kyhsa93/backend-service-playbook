import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'

const DOC = 'docs/architecture/container.md'

export function evaluateDockerfile(root: string): EvaluatorResult {
  const dockerfilePath = path.join(root, 'Dockerfile')
  if (!fs.existsSync(dockerfilePath)) {
    return { name: 'dockerfile', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const content = fs.readFileSync(dockerfilePath, 'utf-8')

  // 멀티스테이지 빌드 필수
  if (!/\bAS\s+build\b/i.test(content)) {
    failures.push({
      ruleId: 'dockerfile.multistage-required',
      severity: 'critical',
      message: 'Dockerfile에 멀티스테이지 빌드(AS build)가 없습니다. build → production 2단계 구조가 필요합니다.',
      docRef: DOC
    })
    score -= penaltyFor('critical')
  }

  // npm wrapper 대신 node 직접 실행 (SIGTERM 처리)
  if (/^\s*CMD\s+\[?"npm/m.test(content) || /^\s*CMD\s+\[?"yarn/m.test(content)) {
    failures.push({
      ruleId: 'dockerfile.cmd-node-direct',
      severity: 'high',
      message: 'CMD에서 npm/yarn wrapper 대신 node dist/main.js를 직접 실행해야 합니다. npm은 SIGTERM을 자식 프로세스에 전달하지 않습니다.',
      docRef: DOC
    })
    score -= penaltyFor('high')
  }

  // devDependencies 제외한 프로덕션 설치
  if (!/npm\s+ci\s+--omit=dev|npm\s+install\s+--production|npm\s+ci\s+--only=production/m.test(content)) {
    failures.push({
      ruleId: 'dockerfile.prod-deps-only',
      severity: 'medium',
      message: 'Dockerfile production 스테이지에서 npm ci --omit=dev로 devDependencies를 제외해야 합니다.',
      docRef: DOC
    })
    score -= penaltyFor('medium')
  }

  // non-root 사용자로 실행 — 마지막 스테이지에 USER 지시문이 있는지 확인한다.
  // 스테이지가 여러 개면 각 스테이지 블록 중 마지막 것만 봐야 한다(Build 스테이지에는
  // USER가 없는 게 정상이라 전체 content에서 찾으면 오탐 없이 통과해버릴 위험이 없어
  // 단순 검사로 충분하다 — production 스테이지 자체가 USER 없이 CMD/ENTRYPOINT로
  // 끝나면 지금 이 파일처럼 놓치기 쉽다).
  if (!/^\s*USER\s+\S+/m.test(content)) {
    failures.push({
      ruleId: 'dockerfile.non-root-user-missing',
      severity: 'high',
      message: 'Dockerfile에 USER 지시문이 없습니다 — 컨테이너가 root로 실행됩니다. non-root 사용자로 전환해야 합니다(node:alpine은 USER node로 기본 제공되는 사용자를 바로 쓸 수 있습니다).',
      docRef: DOC
    })
    score -= penaltyFor('high')
  }

  // .dockerignore 존재
  if (!fs.existsSync(path.join(root, '.dockerignore'))) {
    failures.push({
      ruleId: 'dockerfile.dockerignore-missing',
      severity: 'medium',
      message: '.dockerignore 파일이 없습니다. node_modules, dist, .env* 등을 제외해야 합니다.',
      docRef: DOC
    })
    score -= penaltyFor('medium')
  }

  // HEALTHCHECK — container.md는 "필수는 아니다"(오케스트레이터가 liveness/readiness를
  // 이미 담당하는 배포 환경)라고 명시하므로 medium(권장)으로만 잡는다.
  if (!/^\s*HEALTHCHECK\b/m.test(content)) {
    failures.push({
      ruleId: 'dockerfile.healthcheck-missing',
      severity: 'medium',
      message: 'HEALTHCHECK 지시문이 없습니다. 단독 docker run 환경에서 컨테이너 헬스 상태를 바로 확인하려면 필요합니다(오케스트레이터가 liveness/readiness probe를 이미 담당한다면 생략 가능).',
      docRef: `${DOC}#원칙`
    })
    score -= penaltyFor('medium')
  }

  return {
    name: 'dockerfile',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
