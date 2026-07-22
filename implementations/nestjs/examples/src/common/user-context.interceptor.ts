import { CallHandler, ExecutionContext, Injectable, NestInterceptor } from '@nestjs/common'
import { Observable } from 'rxjs'

import { UserContextStore } from '@/common/user-context-store'

// Bridges the user AuthGuard verified onto the request object (request.__verifiedUser — an
// internal handoff, never read by a Controller) into the request-scoped UserContextStore.
// This has to be an Interceptor, not part of AuthGuard itself: a Guard's canActivate() only
// returns true/false with no callback to wrap the rest of the pipeline, so it cannot open an
// AsyncLocalStorage.run() scope that the eventual Controller method eventually executes inside
// of — only Middleware and Interceptors get a "continue processing" callback (next()/
// next.handle()) to wrap. See the @Authenticated() decorator, which always applies this
// together with AuthGuard.
@Injectable()
export class UserContextInterceptor implements NestInterceptor {
  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    const request = context.switchToHttp().getRequest()
    const user = request.__verifiedUser

    if (!user) return next.handle()

    return new Observable((subscriber) => {
      UserContextStore.run(user, () => {
        next.handle().subscribe(subscriber)
      })
    })
  }
}
