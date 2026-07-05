import { Type } from 'class-transformer'
import { IsInt, Min } from 'class-validator'

export class GetOrdersQuery {
  @Type(() => Number)
  @IsInt()
  @Min(0)
  public readonly page: number = 0

  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly take: number = 20
}
