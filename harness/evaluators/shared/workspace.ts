import * as fs from 'node:fs'
import * as path from 'node:path'

import { walkTsFiles } from './ast-utils'

interface WorkspaceCache {
  tsFiles: string[] | null
  contents: Map<string, string>
}

export interface Workspace {
  root: string
  srcDir: string
  listTsFiles(): string[]
  read(filePath: string): string
  resetCache(): void
}

const CACHE_BY_ROOT = new Map<string, WorkspaceCache>()

function getCache(root: string): WorkspaceCache {
  let c = CACHE_BY_ROOT.get(root)
  if (!c) {
    c = { tsFiles: null, contents: new Map() }
    CACHE_BY_ROOT.set(root, c)
  }
  return c
}

export function getWorkspace(root: string): Workspace {
  const srcDir = path.join(root, 'src')
  return {
    root,
    srcDir,
    listTsFiles(): string[] {
      const c = getCache(root)
      if (!c.tsFiles) c.tsFiles = walkTsFiles(srcDir)
      return c.tsFiles
    },
    read(filePath: string): string {
      const c = getCache(root)
      let content = c.contents.get(filePath)
      if (content === undefined) {
        content = fs.readFileSync(filePath, 'utf-8')
        c.contents.set(filePath, content)
      }
      return content
    },
    resetCache(): void {
      CACHE_BY_ROOT.delete(root)
    }
  }
}

export function clearAllWorkspaces(): void {
  CACHE_BY_ROOT.clear()
}
