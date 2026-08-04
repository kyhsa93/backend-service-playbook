import { IsString } from 'class-validator'

export class CreateAccountRequestBody {
  @IsString()
  readonly name!: string
}
