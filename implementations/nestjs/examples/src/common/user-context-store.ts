import { AsyncLocalStorage } from 'async_hooks'

export interface UserContext {
  userId: string
}

const storage = new AsyncLocalStorage<UserContext>()

export const UserContextStore = {
  run: (user: UserContext, fn: () => void) => storage.run(user, fn),

  getUser: (): UserContext | undefined => storage.getStore(),

  // The common case — every authenticated Controller method needs only the requester's ID.
  // Throws rather than returning undefined: a Controller method gated by @Authenticated() should
  // never reach this with no user set, so a thrown error surfaces a real wiring bug immediately
  // instead of silently propagating an empty/undefined requesterId into a Command/Query.
  getRequesterId: (): string => {
    const user = storage.getStore()
    if (!user) throw new Error('UserContextStore.getRequesterId() called outside an authenticated request context.')
    return user.userId
  }
}
