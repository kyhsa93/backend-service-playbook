import { IsInt, Min } from 'class-validator'

export class FindAccountsQuery {
  @IsInt()
  @Min(1)
  readonly page!: number
}
