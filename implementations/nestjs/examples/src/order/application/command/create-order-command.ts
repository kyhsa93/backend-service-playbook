import { ApiProperty } from '@nestjs/swagger'
import { IsArray, IsString, MinLength } from 'class-validator'

export class CreateOrderCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly userId: string

  @ApiProperty()
  @IsArray()
  public readonly items: { itemId: number; name: string; price: number; quantity: number }[]

  constructor(command: CreateOrderCommand) {
    Object.assign(this, command)
  }
}
