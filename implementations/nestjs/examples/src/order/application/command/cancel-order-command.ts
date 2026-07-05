import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsOptional, IsString, Min, MinLength } from 'class-validator'

export class CancelOrderCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly orderId: string

  @ApiProperty({ minLength: 1 })
  @IsString()
  @MinLength(1)
  public readonly reason: string

  @ApiPropertyOptional()
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(0)
  public readonly refundAmount?: number

  constructor(command: CancelOrderCommand) {
    Object.assign(this, command)
  }
}
