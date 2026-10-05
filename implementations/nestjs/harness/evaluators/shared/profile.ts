import * as fs from 'node:fs'
import * as path from 'node:path'

// Profiles decide which evaluators apply before any of them runs.
//
// - benchmark (default): every evaluator applies. A submission that ignores the
//   playbook's layout is supposed to lose points for it — that is the benchmark.
// - adopt: running the harness on an existing NestJS project that was never
//   built on this playbook. Rules that only encode the playbook's own layout or
//   house naming would fail on every such project and drown the architectural
//   findings, so they are reported as not applicable instead of failing.
//
// Not-applicable evaluators leave both the score and the maximum whether they
// would have passed or failed, so adopt and benchmark totals are not comparable.
export type Profile = 'benchmark' | 'adopt'

export const PROFILES: Profile[] = ['benchmark', 'adopt']

export interface NotApplicable {
  evaluator: string
  reason: string
}

// Evaluators whose every check assumes src/<context>/{domain,application,interface,infrastructure}.
const LAYOUT_BOUND = ['structure', 'cqrs-pattern']

// house naming: `<name>-module.ts` instead of Nest's `<name>.module.ts`, no `*.service.ts`.
const HOUSE_NAMING = ['file-naming']

const LAYERS = ['domain', 'application', 'interface', 'infrastructure']

export function usesPlaybookLayout(root: string): boolean {
  const src = path.join(root, 'src')
  if (!fs.existsSync(src)) return false
  return fs
    .readdirSync(src, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .some((entry) => LAYERS.some((layer) => fs.existsSync(path.join(src, entry.name, layer))))
}

export function notApplicableFor(profile: Profile, root: string): NotApplicable[] {
  if (profile === 'benchmark') return []

  const out: NotApplicable[] = HOUSE_NAMING.map((evaluator) => ({
    evaluator,
    reason: 'playbook house naming (<name>-module.ts, no *.service.ts) — not a NestJS convention'
  }))

  if (!usesPlaybookLayout(root)) {
    for (const evaluator of LAYOUT_BOUND) {
      out.push({
        evaluator,
        reason: 'project does not use the playbook layout src/<context>/{domain,application,interface,infrastructure}'
      })
    }
  }

  // Without installed dependencies tsc only reports unresolved modules, which says
  // nothing about the code. Run `npm install` first to include the build check.
  if (!fs.existsSync(path.join(root, 'node_modules', '.bin', 'tsc'))) {
    out.push({ evaluator: 'build', reason: 'dependencies not installed (node_modules/.bin/tsc missing)' })
  }

  return out
}
