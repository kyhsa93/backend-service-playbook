import { AsyncLocalStorage } from 'async_hooks'

const storage = new AsyncLocalStorage<string>()

export const CorrelationIdStore = {
  run: (id: string, fn: () => void) => storage.run(id, fn),
  getId: () => storage.getStore()
}
