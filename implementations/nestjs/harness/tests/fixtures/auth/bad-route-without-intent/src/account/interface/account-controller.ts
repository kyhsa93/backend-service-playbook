import { Controller, Get } from '@nestjs/common'
import { Public } from '@/auth/public-decorator'

@Controller('accounts')
export class AccountController {
  @Get()
  public findAccounts(): string[] {
    return []
  }

  @Public()
  @Get('health')
  public health(): string {
    return 'ok'
  }
}
