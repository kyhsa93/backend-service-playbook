import { Controller, Get } from '@nestjs/common'
import { ApiOperation } from '@nestjs/swagger'

@Controller('accounts')
export class AccountController {
  @ApiOperation({ summary: 'List accounts' })
  @Get()
  public findAccounts(): string[] {
    return []
  }
}
