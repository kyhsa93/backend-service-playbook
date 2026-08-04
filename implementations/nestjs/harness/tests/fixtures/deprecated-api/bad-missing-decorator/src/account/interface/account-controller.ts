import { Controller, Get } from '@nestjs/common'

@Controller('accounts')
export class AccountController {
  @Get('legacy-accounts')
  public findLegacyAccounts(): string[] {
    return []
  }
}
