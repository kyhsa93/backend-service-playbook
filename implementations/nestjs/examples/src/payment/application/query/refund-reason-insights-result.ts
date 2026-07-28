import { ApiProperty } from '@nestjs/swagger'

export class RefundReasonCategoryCount {
  @ApiProperty({ description: 'A refund-reason category.', enum: ['DEFECTIVE_PRODUCT', 'WRONG_ITEM', 'NOT_AS_DESCRIBED', 'CHANGED_MIND', 'LATE_DELIVERY', 'DUPLICATE_CHARGE', 'OTHER'] })
  public readonly category: string

  @ApiProperty({ description: 'How many classified refunds fall into this category, in the requested range.' })
  public readonly count: number
}

export class RefundReasonInsightsResult {
  @ApiProperty({ description: 'A count per category, for refunds that have been classified so far — omits categories with 0 refunds.', type: [RefundReasonCategoryCount] })
  public readonly counts: RefundReasonCategoryCount[]

  @ApiProperty({ description: 'The total number of classified refunds across all categories in the requested range.' })
  public readonly totalClassified: number
}
