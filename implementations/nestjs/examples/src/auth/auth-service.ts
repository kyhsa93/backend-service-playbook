import { Injectable } from '@nestjs/common'
import { ConfigService } from '@nestjs/config'
import { JwtService, JwtSignOptions } from '@nestjs/jwt'

@Injectable()
export class AuthService {
  constructor(
    private readonly jwtService: JwtService,
    private readonly configService: ConfigService
  ) {}

  public async sign(payload: { userId: string }): Promise<string> {
    return this.jwtService.signAsync(payload, {
      secret: this.configService.get<string>('jwt.secret'),
      expiresIn: this.configService.get<string>('jwt.expiresIn')
    } as unknown as JwtSignOptions)
  }

  public async verify(token: string): Promise<{ userId: string } | null> {
    try {
      return await this.jwtService.verifyAsync<{ userId: string }>(token, {
        secret: this.configService.get<string>('jwt.secret')
      })
    } catch {
      return null
    }
  }
}
