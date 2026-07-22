import { ApiProperty } from '@nestjs/swagger'

export class ErrorResponseBody {
  @ApiProperty({ description: 'The HTTP status code.', example: 400 })
  public readonly statusCode: number

  @ApiProperty({
    description: 'A stable, machine-readable error code the client can branch on. Unlike `message`, this never changes wording or gets translated.',
    example: 'VALIDATION_FAILED'
  })
  public readonly code: string

  @ApiProperty({
    description: 'A human-readable description of the error. class-validator failures return an array of one message per failed field; every other error returns a single string.',
    oneOf: [{ type: 'string' }, { type: 'array', items: { type: 'string' } }],
    example: 'Account not found.'
  })
  public readonly message: string | string[]

  @ApiProperty({ description: 'The standard HTTP status text for `statusCode`.', example: 'Bad Request' })
  public readonly error: string
}
