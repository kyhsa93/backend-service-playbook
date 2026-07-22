import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common'
import { Reflector } from '@nestjs/core'

import { AuthService } from '@/auth/auth-service'
import { IS_PUBLIC_KEY } from '@/auth/public.decorator'

@Injectable()
export class AuthGuard implements CanActivate {
  constructor(
    private readonly authService: AuthService,
    private readonly reflector: Reflector
  ) {}

  public async canActivate(context: ExecutionContext): Promise<boolean> {
    const isPublic = this.reflector.getAllAndOverride<boolean>(IS_PUBLIC_KEY, [context.getHandler(), context.getClass()])
    if (isPublic) return true

    const request = context.switchToHttp().getRequest()
    const authorization = request.headers.authorization
    if (!authorization?.startsWith('Bearer ')) throw new UnauthorizedException()

    const token = authorization.replace('Bearer ', '')
    const user = await this.authService.verify(token)
    if (!user) throw new UnauthorizedException()

    // A Guard has no callback to wrap the rest of the pipeline (unlike Middleware/Interceptors),
    // so it cannot itself open the AsyncLocalStorage-based UserContextStore. This field is an
    // internal-only handoff to UserContextInterceptor (always applied together via
    // @Authenticated(), see authenticated.decorator.ts) — Controllers must never read it
    // directly; they read UserContextStore.getRequesterId() instead.
    request.__verifiedUser = user
    return true
  }
}
