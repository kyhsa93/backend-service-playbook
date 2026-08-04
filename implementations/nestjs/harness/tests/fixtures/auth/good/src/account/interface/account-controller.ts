import { Controller, Get } from '@nestjs/common'
import { Authenticated } from '@/auth/authenticated-decorator'

@Authenticated()
@Controller('accounts')
export class AccountController {
  @Get()
  public findAccounts(): string[] {
    return []
  }
}
