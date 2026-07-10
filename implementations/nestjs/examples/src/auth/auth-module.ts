import { Module } from '@nestjs/common'
import { JwtModule } from '@nestjs/jwt'

import { AuthService } from '@/auth/auth-service'
import { AuthGuard } from '@/auth/auth.guard'
import { AuthController } from '@/auth/interface/auth-controller'

@Module({
  imports: [JwtModule.register({})],
  controllers: [AuthController],
  providers: [AuthService, AuthGuard],
  exports: [AuthService, AuthGuard]
})
export class AuthModule {}
