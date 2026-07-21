import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsString, Min } from 'class-validator'

export class TransferCommand {
  public readonly sourceAccountId: string
  public readonly requesterId: string

  @ApiProperty()
  @IsString()
  public readonly targetAccountId: string

  @ApiProperty({ minimum: 1 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number

  constructor(command: TransferCommand) {
    Object.assign(this, command)
  }
}
