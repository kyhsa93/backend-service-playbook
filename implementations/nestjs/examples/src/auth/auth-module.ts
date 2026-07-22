import { Module } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { JwtModule } from '@nestjs/jwt'
import { TypeOrmModule } from '@nestjs/typeorm'

import { AuthService } from '@/auth/auth-service'
import { AuthGuard } from '@/auth/auth.guard'
import { SignInCommandHandler } from '@/auth/application/command/sign-in-command-handler'
import { SignUpCommandHandler } from '@/auth/application/command/sign-up-command-handler'
import { PasswordHasher } from '@/auth/application/service/password-hasher'
import { CredentialRepository } from '@/auth/domain/credential-repository'
import { CredentialEntity } from '@/auth/infrastructure/entity/credential.entity'
import { BcryptPasswordHasher } from '@/auth/infrastructure/bcrypt-password-hasher'
import { CredentialRepositoryImpl } from '@/auth/infrastructure/credential-repository-impl'
import { AuthController } from '@/auth/interface/auth-controller'

@Module({
  imports: [CqrsModule, JwtModule.register({}), TypeOrmModule.forFeature([CredentialEntity])],
  controllers: [AuthController],
  providers: [
    AuthService,
    AuthGuard,
    // Command Handlers
    SignUpCommandHandler,
    SignInCommandHandler,
    // Repository
    { provide: CredentialRepository, useClass: CredentialRepositoryImpl },
    // A Technical Service — password hashing
    { provide: PasswordHasher, useClass: BcryptPasswordHasher }
  ],
  exports: [AuthService, AuthGuard]
})
export class AuthModule {}
