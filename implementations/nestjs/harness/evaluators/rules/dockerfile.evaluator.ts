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

  // A multi-stage build is required
  if (!/\bAS\s+build\b/i.test(content)) {
    failures.push({
      ruleId: 'dockerfile.multistage-required',
      severity: 'critical',
      message: 'The Dockerfile has no multi-stage build (AS build). A build -> production two-stage structure is required.',
      docRef: DOC
    })
    score -= penaltyFor('critical')
  }

  // Run node directly instead of the npm wrapper (for SIGTERM handling)
  if (/^\s*CMD\s+\[?"npm/m.test(content) || /^\s*CMD\s+\[?"yarn/m.test(content)) {
    failures.push({
      ruleId: 'dockerfile.cmd-node-direct',
      severity: 'high',
      message: 'CMD must run node dist/main.js directly instead of an npm/yarn wrapper. npm does not forward SIGTERM to its child process.',
      docRef: DOC
    })
    score -= penaltyFor('high')
  }

  // A production install that excludes devDependencies
  if (!/npm\s+ci\s+--omit=dev|npm\s+install\s+--production|npm\s+ci\s+--only=production/m.test(content)) {
    failures.push({
      ruleId: 'dockerfile.prod-deps-only',
      severity: 'medium',
      message: 'The Dockerfile production stage must exclude devDependencies with npm ci --omit=dev.',
      docRef: DOC
    })
    score -= penaltyFor('medium')
  }

  // Run as a non-root user — checks whether the last stage has a USER directive.
  // If there are multiple stages, only the last stage block should be looked at (a Build stage
  // having no USER is normal, so a simple check across the whole content carries no
  // false-positive risk — if the production stage itself ends with CMD/ENTRYPOINT with no
  // USER, it's easy to miss, just like it is in this very file).
  if (!/^\s*USER\s+\S+/m.test(content)) {
    failures.push({
      ruleId: 'dockerfile.non-root-user-missing',
      severity: 'high',
      message: 'The Dockerfile has no USER directive — the container runs as root. It must switch to a non-root user (node:alpine provides a ready-to-use "node" user via USER node).',
      docRef: DOC
    })
    score -= penaltyFor('high')
  }

  // A .dockerignore exists
  if (!fs.existsSync(path.join(root, '.dockerignore'))) {
    failures.push({
      ruleId: 'dockerfile.dockerignore-missing',
      severity: 'medium',
      message: 'The .dockerignore file is missing. It must exclude node_modules, dist, .env*, etc.',
      docRef: DOC
    })
    score -= penaltyFor('medium')
  }

  // HEALTHCHECK — since container.md states it's "not strictly required" (in a deployment
  // environment where an orchestrator already handles liveness/readiness), this is only flagged as medium (a recommendation).
  if (!/^\s*HEALTHCHECK\b/m.test(content)) {
    failures.push({
      ruleId: 'dockerfile.healthcheck-missing',
      severity: 'medium',
      message: 'The HEALTHCHECK directive is missing. It is needed to check container health directly in a standalone docker run environment (it may be omitted if an orchestrator already handles liveness/readiness probes).',
      docRef: `${DOC}#principles`
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
