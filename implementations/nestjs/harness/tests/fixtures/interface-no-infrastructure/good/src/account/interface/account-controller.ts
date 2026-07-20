import { CreateAccountCommandHandler } from '@/account/application/command/create-account-command-handler'

export class AccountController {
  constructor(private readonly handler: CreateAccountCommandHandler) {}
}
