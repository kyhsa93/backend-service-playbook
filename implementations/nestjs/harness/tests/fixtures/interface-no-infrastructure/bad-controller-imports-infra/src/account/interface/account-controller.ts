import { AccountRepositoryImpl } from '@/account/infrastructure/account-repository-impl'

export class AccountController {
  constructor(private readonly repositoryImpl: AccountRepositoryImpl) {}
}
