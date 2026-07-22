import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorResult, EvaluatorFailure } from '../shared/types'

const DOC = 'docs/architecture/api-response.md#machine-readable-api-documentation-openapi'

// Matches an HTTP-route decorator: @Get(...), @Post(...), @Put(...), @Patch(...), @Delete(...)
const ROUTE_DECORATOR = /@(Get|Post|Put|Patch|Delete)\s*\(/g

// A non-2xx response decorator — either a named @Api<Status>Response shorthand (any status
// EXCEPT the four 2xx-named ones, rather than an exhaustive allowlist of every 4xx/5xx name —
// @nestjs/swagger ships one shorthand per HTTP status, and a hand-picked list silently misses
// ones like @ApiServiceUnavailableResponse), or the generic @ApiResponse({ status: 4xx/5xx }).
// This intentionally does not care WHICH status is used; the point is that *some* failure path
// is documented, and error-handling.evaluator.ts separately verifies every thrown error maps
// to a real status.
const TWO_XX_RESPONSE_DECORATORS = /^Api(Ok|Created|Accepted|NoContent)Response$/
const ANY_API_RESPONSE_SHORTHAND = /@(Api\w+Response)\s*\(/g
const GENERIC_NON_2XX_RESPONSE = /@ApiResponse\s*\(\s*\{[^}]*status:\s*[45]\d\d/

function hasNon2xxResponse(block: string): boolean {
  if (GENERIC_NON_2XX_RESPONSE.test(block)) return true
  return [...block.matchAll(ANY_API_RESPONSE_SHORTHAND)]
    .some(([, name]) => name !== 'ApiResponse' && !TWO_XX_RESPONSE_DECORATORS.test(name))
}

function walkTsFiles(root: string): string[] {
  const out: string[] = []
  if (!fs.existsSync(root)) return out
  for (const entry of fs.readdirSync(root)) {
    if (['node_modules', 'dist', 'coverage', '.git'].includes(entry)) continue
    const full = path.join(root, entry)
    if (fs.statSync(full).isDirectory()) { out.push(...walkTsFiles(full)); continue }
    if (full.endsWith('.ts') && !full.endsWith('.spec.ts') && !full.endsWith('.d.ts')) out.push(full)
  }
  return out
}

/** Splits a REST controller file into per-endpoint text blocks, each running from one route
 * decorator up to (but not including) the next one — this captures every decorator/comment
 * that applies to that specific handler, regardless of decorator ordering. */
function splitIntoEndpointBlocks(content: string): { routeStart: number; block: string }[] {
  const matches = [...content.matchAll(ROUTE_DECORATOR)]
  return matches.map((m, i) => {
    const start = m.index ?? 0
    const end = i + 1 < matches.length ? matches[i + 1].index ?? content.length : content.length
    return { routeStart: start, block: content.slice(start, end) }
  })
}

function lineNumberAt(content: string, index: number): number {
  return content.slice(0, index).split('\n').length
}

export function evaluateApiDocumentation(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 30

  const files = walkTsFiles(path.join(root, 'src'))
    .filter((f) => f.endsWith('-controller.ts'))
    // Task Queue consumers and Integration Event consumers are @Injectable(), not
    // @Controller() — they have no REST surface, so OpenAPI documentation doesn't apply.
    .filter((f) => fs.readFileSync(f, 'utf-8').includes('@Controller('))

  for (const file of files) {
    const content = fs.readFileSync(file, 'utf-8')
    const rel = path.relative(root, file)

    // Decorators above `export class` apply to every endpoint in the controller (e.g. a
    // class-level @ApiUnauthorizedResponse covering an AuthGuard applied to the whole class).
    const classHeaderEnd = content.search(/export\s+class/)
    const classLevelBlock = classHeaderEnd === -1 ? '' : content.slice(0, classHeaderEnd)
    const classHasNon2xx = hasNon2xxResponse(classLevelBlock)

    const endpoints = splitIntoEndpointBlocks(content)
    for (const { routeStart, block } of endpoints) {
      const line = lineNumberAt(content, routeStart)
      const routeMatch = block.match(/@(Get|Post|Put|Patch|Delete)\s*\(\s*(['"`][^'"`]*['"`])?/)
      const routeLabel = routeMatch ? `${routeMatch[1]} ${routeMatch[2] ?? ''}`.trim() : 'route'

      const hasOperation = /@ApiOperation\s*\(\s*\{/.test(block)
      const opBlockMatch = block.match(/@ApiOperation\s*\(\s*\{([^]*?)\}\s*\)/)
      const opBody = opBlockMatch ? opBlockMatch[1] : ''
      const hasSummary = /summary\s*:/.test(opBody)
      const hasDescription = /description\s*:/.test(opBody)

      if (!hasOperation || !hasSummary || !hasDescription) {
        failures.push({
          ruleId: 'api-documentation.operation-incomplete',
          severity: 'medium',
          message: `${rel}:${line} — ${routeLabel} is missing @ApiOperation with both summary and description (an operationId alone is not sufficient)`,
          docRef: DOC
        })
        score -= 3
      }

      if (!classHasNon2xx && !hasNon2xxResponse(block)) {
        failures.push({
          ruleId: 'api-documentation.error-response-undocumented',
          severity: 'medium',
          message: `${rel}:${line} — ${routeLabel} documents only the success response; no non-2xx response (e.g. @ApiNotFoundResponse, @ApiBadRequestResponse) is declared`,
          docRef: DOC
        })
        score -= 3
      }
    }
  }

  return {
    name: 'api-documentation',
    score: Math.max(score, 0),
    maxScore: 30,
    failures
  }
}
