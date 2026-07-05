import * as fs from 'node:fs'
import * as path from 'node:path'

export function walkTsFiles(dir: string, files: string[] = []): string[] {
  if (!fs.existsSync(dir)) return files
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walkTsFiles(full, files)
    else if (full.endsWith('.ts')) files.push(full)
  }
  return files
}

/** Normalize path separators to forward slashes for consistent matching. */
export function normPath(p: string): string {
  return p.replace(/\\/g, '/')
}
