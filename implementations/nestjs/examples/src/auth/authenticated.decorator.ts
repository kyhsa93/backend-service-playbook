import { applyDecorators, UseGuards, UseInterceptors } from '@nestjs/common'

import { AuthGuard } from '@/auth/auth.guard'
import { UserContextInterceptor } from '@/common/user-context.interceptor'

// Applies AuthGuard (verifies the Bearer token, throws 401 on failure) and UserContextInterceptor
// (bridges the verified user into UserContextStore) together — the two must never be applied
// separately, since UserContextInterceptor depends on AuthGuard having run first. Controllers
// read the authenticated user via UserContextStore.getRequesterId(), never via @Req().
export const Authenticated = (): ReturnType<typeof applyDecorators> => applyDecorators(
  UseGuards(AuthGuard),
  UseInterceptors(UserContextInterceptor)
)
