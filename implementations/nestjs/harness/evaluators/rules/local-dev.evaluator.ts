import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { walkTsFiles } from '../shared/ast-utils'

const DOC = 'docs/architecture/local-dev.md'

// Database engines that need their own docker-compose service. The playbook's
// examples use Postgres, but docs/architecture/local-dev.md only asks for "an
// RDBMS (Postgres/MySQL, etc.)" — the engine is the project's choice, not an
// architectural rule. So the rule looks for the engine the project actually uses.
type Engine = 'postgres' | 'mysql'

const COMPOSE_PATTERN: Record<Engine, RegExp> = {
  postgres: /postgres/i,
  mysql: /mysql|mariadb/i
}

const DRIVER_PACKAGES: Record<string, Engine> = {
  pg: 'postgres',
  postgres: 'postgres',
  'pg-promise': 'postgres',
  mysql: 'mysql',
  mysql2: 'mysql',
  mariadb: 'mysql'
}

const ORM_TYPE_PATTERN = /\btype\s*:\s*['"](postgres|mysql|mariadb)['"]/g
const PRISMA_PROVIDER_PATTERN = /provider\s*=\s*"(postgresql|mysql)"/g

function toEngine(name: string): Engine {
  return name.startsWith('postgres') ? 'postgres' : 'mysql'
}

function enginesFromPackageJson(root: string): Set<Engine> {
  const engines = new Set<Engine>()
  const pkgPath = path.join(root, 'package.json')
  if (!fs.existsSync(pkgPath)) return engines
  try {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8')) as {
      dependencies?: Record<string, string>
      devDependencies?: Record<string, string>
    }
    const deps = { ...pkg.dependencies, ...pkg.devDependencies }
    for (const [name, engine] of Object.entries(DRIVER_PACKAGES)) {
      if (name in deps) engines.add(engine)
    }
  } catch {
    // An unreadable package.json is not this rule's concern.
  }
  return engines
}

function enginesFromOrmConfig(root: string): Set<Engine> {
  const engines = new Set<Engine>()
  const files = [...walkTsFiles(path.join(root, 'src')), ...walkTsFiles(path.join(root, 'libs'))]
  for (const file of files) {
    for (const match of fs.readFileSync(file, 'utf-8').matchAll(ORM_TYPE_PATTERN)) {
      engines.add(toEngine(match[1]))
    }
  }
  const prismaSchema = path.join(root, 'prisma', 'schema.prisma')
  if (fs.existsSync(prismaSchema)) {
    for (const match of fs.readFileSync(prismaSchema, 'utf-8').matchAll(PRISMA_PROVIDER_PATTERN)) {
      engines.add(toEngine(match[1]))
    }
  }
  return engines
}

// Driver packages first; the ORM config is the fallback when package.json names none.
// An empty set means the engine cannot be determined (or needs no server, e.g. SQLite),
// and the database-service check does not apply.
export function detectDatabaseEngines(root: string): Set<Engine> {
  const fromPackages = enginesFromPackageJson(root)
  return fromPackages.size > 0 ? fromPackages : enginesFromOrmConfig(root)
}

export function evaluateLocalDev(root: string): EvaluatorResult {
  const composePath = path.join(root, 'docker-compose.yml')
  const composeYmlPath = path.join(root, 'docker-compose.yaml')
  const composefile = fs.existsSync(composePath)
    ? composePath
    : fs.existsSync(composeYmlPath)
      ? composeYmlPath
      : null

  if (!composefile) {
    return { name: 'local-dev', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const content = fs.readFileSync(composefile, 'utf-8')

  // A service for the database the project actually uses
  const engines = [...detectDatabaseEngines(root)]
  if (engines.length > 0 && !engines.some((engine) => COMPOSE_PATTERN[engine].test(content))) {
    failures.push({
      ruleId: 'local-dev.database-service-missing',
      severity: 'high',
      message: `docker-compose.yml has no ${engines.join(' or ')} service, but the project's database driver is ${engines.join(' / ')}.`,
      docRef: DOC
    })
    score -= penaltyFor('high')
  }

  // A healthcheck definition
  if (!/healthcheck/i.test(content)) {
    failures.push({
      ruleId: 'local-dev.healthcheck-missing',
      severity: 'medium',
      message: 'The docker-compose.yml service has no healthcheck. It is needed for depends_on condition: service_healthy.',
      docRef: DOC
    })
    score -= penaltyFor('medium')
  }

  // .env.development or .env.example exists
  const hasEnvFile =
    fs.existsSync(path.join(root, '.env.development')) ||
    fs.existsSync(path.join(root, '.env.example')) ||
    fs.existsSync(path.join(root, '.env'))
  if (!hasEnvFile) {
    failures.push({
      ruleId: 'local-dev.env-file-missing',
      severity: 'low',
      message: 'The .env.development or .env.example file is missing.',
      docRef: DOC
    })
    score -= penaltyFor('low')
  }

  return {
    name: 'local-dev',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
