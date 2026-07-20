import { ArgumentsHost, Catch, ExceptionFilter, HttpException, HttpStatus } from '@nestjs/common'
import { Response } from 'express'

@Catch()
export class HttpExceptionFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp()
    const response = ctx.getResponse<Response>()

    const status = HttpStatus.INTERNAL_SERVER_ERROR
    const message = exception instanceof Error ? exception.message : 'Internal server error'

    // stack 필드가 추가되고 error 필드가 빠짐 — 4개 필드(statusCode/code/message/error) 위반
    response.status(status).json({
      statusCode: status,
      code: 'INTERNAL_ERROR',
      message,
      stack: exception instanceof Error ? exception.stack : undefined
    })
  }
}
