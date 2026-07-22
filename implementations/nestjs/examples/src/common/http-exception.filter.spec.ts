import { ArgumentsHost, BadRequestException, HttpException, HttpStatus } from '@nestjs/common'

import { HttpExceptionFilter } from '@/common/http-exception.filter'

describe('HttpExceptionFilter', () => {
  const createHost = (): { host: ArgumentsHost; json: jest.Mock; status: jest.Mock } => {
    const json = jest.fn()
    const status = jest.fn().mockReturnValue({ json })
    const host = {
      switchToHttp: () => ({
        getResponse: () => ({ status }),
        getRequest: () => ({})
      })
    } as unknown as ArgumentsHost
    return { host, json, status }
  }

  it('HttpException_when_object_response_then_serializes_as_is', () => {
    const filter = new HttpExceptionFilter()
    const { host, json, status } = createHost()
    const exception = new BadRequestException({ statusCode: 400, code: 'VALIDATION_FAILED', message: ['msg'], error: 'Bad Request' })

    filter.catch(exception, host)

    expect(status).toHaveBeenCalledWith(400)
    expect(json).toHaveBeenCalledWith({ statusCode: 400, code: 'VALIDATION_FAILED', message: ['msg'], error: 'Bad Request' })
  })

  it('HttpException_when_string_response_then_converts_to_the_standard_format', () => {
    const filter = new HttpExceptionFilter()
    const { host, json, status } = createHost()
    const exception = new HttpException('Simple message', HttpStatus.BAD_REQUEST)

    filter.catch(exception, host)

    expect(status).toHaveBeenCalledWith(400)
    expect(json).toHaveBeenCalledWith({ statusCode: 400, code: 'HTTP_EXCEPTION', message: 'Simple message', error: 'HttpException' })
  })

  it('non_HttpException_when_unhandled_error_then_converts_to_the_standard_500_error_response', () => {
    const filter = new HttpExceptionFilter()
    const { host, json, status } = createHost()

    filter.catch(new Error('Unexpected error'), host)

    expect(status).toHaveBeenCalledWith(500)
    expect(json).toHaveBeenCalledWith({
      statusCode: 500,
      code: 'INTERNAL_ERROR',
      message: 'Unexpected error',
      error: 'Internal Server Error'
    })
  })

  it('non_Error_when_unknown_exception_then_responds_with_the_default_500_message', () => {
    const filter = new HttpExceptionFilter()
    const { host, json, status } = createHost()

    filter.catch('string throw', host)

    expect(status).toHaveBeenCalledWith(500)
    expect(json).toHaveBeenCalledWith({
      statusCode: 500,
      code: 'INTERNAL_ERROR',
      message: 'Internal server error',
      error: 'Internal Server Error'
    })
  })
})
