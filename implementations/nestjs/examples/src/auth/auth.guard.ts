import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common'

import { AuthService } from '@/auth/auth-service'

@Injectable()
export class AuthGuard implements CanActivate {
  constructor(private readonly authService: AuthService) {}

  public async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest()
    const authorization = request.headers.authorization
    if (!authorization?.startsWith('Bearer ')) throw new UnauthorizedException()

    const token = authorization.replace('Bearer ', '')
    const user = await this.authService.verify(token)
    if (!user) throw new UnauthorizedException()

    request.user = user
    return true
  }
}
