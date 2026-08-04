import { Controller, Post } from '@nestjs/common'

@Controller('create-account')
export class AccountController {
  @Post()
  public createAccount(): void {}
}
