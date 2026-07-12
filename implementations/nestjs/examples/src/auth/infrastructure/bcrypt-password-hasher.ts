import { Injectable } from '@nestjs/common'
import { compare, hash } from 'bcryptjs'

import { PasswordHasher } from '@/auth/application/service/password-hasher'

const SALT_ROUNDS = 12

@Injectable()
export class BcryptPasswordHasher implements PasswordHasher {
  public async hash(plainPassword: string): Promise<string> {
    return hash(plainPassword, SALT_ROUNDS)
  }

  public async verify(plainPassword: string, passwordHash: string): Promise<boolean> {
    return compare(plainPassword, passwordHash)
  }
}
