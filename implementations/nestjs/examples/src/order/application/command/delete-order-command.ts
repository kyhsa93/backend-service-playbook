import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class DeleteOrderCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly orderId: string

  constructor(command: DeleteOrderCommand) {
    Object.assign(this, command)
  }
}
