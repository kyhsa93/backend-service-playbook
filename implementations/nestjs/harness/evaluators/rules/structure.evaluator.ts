import * as fs from 'node:fs'
import * as path from 'node:path'
import { EvaluatorResult, EvaluatorFailure } from '../shared/types'

function walkTs(dir: string, files: string[] = []): string[] {
  if (!fs.existsSync(dir)) return files
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walkTs(full, files)
    else if (full.endsWith('.ts')) files.push(full)
  }
  return files
}

function anyFileMatches(files: string[], pattern: RegExp): boolean {
  return files.some((f) => pattern.test(fs.readFileSync(f, 'utf-8')))
}

export function evaluateStructure(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 25

  const required = ['domain', 'application', 'interface', 'infrastructure']
  const base = path.join(root, 'src')

  for (const dir of required) {
    const exists = fs.existsSync(base) && fs.readdirSync(base).some(d => {
      const full = path.join(base, d, dir)
      return fs.existsSync(full)
    })

    if (!exists) {
      failures.push({
        ruleId: 'structure.layer.missing',
        severity: 'high',
        message: `missing layer directory: ${dir}`
      })
      score -= 6
    }
  }

  // Conditional: if there's even a single file using the Task Queue pattern, the
  // src/task-queue/ shared module must exist.
  const allFiles = walkTs(base)
  const usesTaskQueue = anyFileMatches(
    allFiles,
    /@TaskConsumer\s*\(|import\s+\{[^}]*TaskQueue[^}]*\}\s+from\s+['"][^'"]*task-queue[^'"]*['"]/
  )
  const taskQueueDir = path.join(base, 'task-queue')
  if (usesTaskQueue && !fs.existsSync(taskQueueDir)) {
    failures.push({
      ruleId: 'structure.task-queue.missing',
      severity: 'high',
      message: `Task Queue is used (@TaskConsumer or a TaskQueue import was detected) but the shared src/task-queue/ module directory is missing`
    })
    score -= 4
  }

  return {
    name: 'structure',
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  }
}
