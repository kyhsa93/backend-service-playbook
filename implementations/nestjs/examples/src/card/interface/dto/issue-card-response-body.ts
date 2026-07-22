import { ApiProperty } from '@nestjs/swagger'

export class IssueCardResponseBody {
  @ApiProperty({ description: 'A 32-character hex string uniquely identifying the card.' })
  public readonly cardId: string

  @ApiProperty({ description: 'The account this card is linked to.' })
  public readonly accountId: string

  @ApiProperty({ description: 'The ID of the user who owns this card.' })
  public readonly ownerId: string

  @ApiProperty({ description: 'The card brand.', example: 'VISA' })
  public readonly brand: string

  @ApiProperty({ description: 'The card status.', enum: ['ACTIVE', 'SUSPENDED', 'CANCELLED'] })
  public readonly status: string

  @ApiProperty({ description: 'When the card was issued.' })
  public readonly createdAt: Date
}
