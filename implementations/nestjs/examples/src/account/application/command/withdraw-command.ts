import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, Min } from 'class-validator'

export class WithdrawCommand {
  public readonly accountId: string
  public readonly requesterId: string

  @ApiProperty({ minimum: 1 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number

  constructor(command: WithdrawCommand) {
    Object.assign(this, command)
  }
}
