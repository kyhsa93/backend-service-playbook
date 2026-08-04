import { Controller, Get } from '@nestjs/common'
import { ApiOperation } from '@nestjs/swagger'

@Controller('accounts')
export class AccountController {
  @Get('accounts')
  @ApiOperation({ deprecated: true })
  public findAccounts(): string[] {
    return []
  }
}
