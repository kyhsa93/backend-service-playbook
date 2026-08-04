import { Controller, Get } from '@nestjs/common'

@Controller('accounts')
export class AccountController {
  @Get()
  public findAccounts(): string[] {
    return []
  }
}
