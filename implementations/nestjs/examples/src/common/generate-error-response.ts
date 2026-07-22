import { STATUS_CODES } from 'http'

import { HttpException, InternalServerErrorException } from '@nestjs/common'

type ExceptionCtor = new (response: string | object) => HttpException

export function generateErrorResponse(
  message: string,
  mappings: [string, ExceptionCtor, string][]
): HttpException {
  const matched = mappings.find(([msg]) => msg === message)
  const [, ExceptionClass, code] = matched ?? [null, InternalServerErrorException, 'INTERNAL_ERROR']
  const probe = new ExceptionClass(message)
  const statusCode = probe.getStatus()
  const error = STATUS_CODES[statusCode] ?? probe.name
  return new ExceptionClass({ statusCode, code, message, error })
}
