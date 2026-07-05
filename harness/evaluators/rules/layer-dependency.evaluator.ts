import * as path from 'node:path'

import { EvaluatorResult, EvaluatorFailure } from '../shared/types'
import { getWorkspace } from '../shared/workspace'
import { normPath } from '../shared/ast-utils'

// Matches relative imports that cross into a specific layer
function hasRelativeImportTo(content: string, layer: string): boolean {
  const pattern = new RegExp(`from\\s+['"]\\.\\.?[^'"]*\\/${layer}\\/`)
  return pattern.test(content)
}

export function evaluateLayerDependency(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 25

  const ws = getWorkspace(root)
  const files = ws.listTsFiles()

  for (const filePath of files) {
    const rel = path.relative(root, filePath)
    const np = normPath(filePath)
    const content = ws.read(filePath)

    // domain/ must not import from application/, interface/, or infrastructure/
    if (np.includes('/domain/')) {
      if (hasRelativeImportTo(content, 'application')) {
        failures.push({
          ruleId: 'layer.domain.no-application-import',
          severity: 'high',
          message: `domain 레이어에서 application 레이어 import 금지: ${rel}`,
          docRef: 'docs/architecture/layer-architecture.md'
        })
        score -= 5
      }
      if (hasRelativeImportTo(content, 'interface')) {
        failures.push({
          ruleId: 'layer.domain.no-interface-import',
          severity: 'high',
          message: `domain 레이어에서 interface 레이어 import 금지: ${rel}`,
          docRef: 'docs/architecture/layer-architecture.md'
        })
        score -= 5
      }
      if (hasRelativeImportTo(content, 'infrastructure')) {
        failures.push({
          ruleId: 'layer.domain.no-infrastructure-import',
          severity: 'critical',
          message: `domain 레이어에서 infrastructure 레이어 import 금지: ${rel}`,
          docRef: 'docs/architecture/layer-architecture.md'
        })
        score -= 7
      }
    }

    // application/ must not import from interface/ (upward dependency)
    if (np.includes('/application/')) {
      if (hasRelativeImportTo(content, 'interface')) {
        failures.push({
          ruleId: 'layer.application.no-interface-import',
          severity: 'high',
          message: `application 레이어에서 interface 레이어 import 금지: ${rel}`,
          docRef: 'docs/architecture/layer-architecture.md'
        })
        score -= 5
      }
      // application/ must not directly use infrastructure/ (should go through abstract class)
      if (hasRelativeImportTo(content, 'infrastructure')) {
        failures.push({
          ruleId: 'layer.application.no-infrastructure-import',
          severity: 'high',
          message: `application 레이어에서 infrastructure 레이어 직접 import 금지 (abstract class를 통해야 함): ${rel}`,
          docRef: 'docs/architecture/layer-architecture.md'
        })
        score -= 5
      }
    }
  }

  return { name: 'layer-dependency', score: Math.max(score, 0), maxScore: 25, failures }
}
