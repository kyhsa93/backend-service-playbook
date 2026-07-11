import { ApiProperty } from '@nestjs/swagger'

export class GetCardResponseBody {
  @ApiProperty()
  public readonly cardId: string

  @ApiProperty()
  public readonly accountId: string

  @ApiProperty()
  public readonly ownerId: string

  @ApiProperty()
  public readonly brand: string

  @ApiProperty()
  public readonly status: string

  @ApiProperty()
  public readonly createdAt: Date
}
