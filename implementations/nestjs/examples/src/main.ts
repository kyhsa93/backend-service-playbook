import { BadRequestException, ValidationPipe } from '@nestjs/common'
import { NestFactory } from '@nestjs/core'

import { AppModule } from '@/app-module'
import { LoggingInterceptor } from '@/common/logging.interceptor'
import { getPort, isProduction } from '@/config/app.config'

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule, {
    logger: isProduction()
      ? ['error', 'warn', 'log']
      : ['error', 'warn', 'log', 'debug', 'verbose']
  })
  app.useGlobalPipes(new ValidationPipe({
    whitelist: true,
    transform: true,
    exceptionFactory: (errors) => {
      const message = errors.flatMap((error) => Object.values(error.constraints ?? {}))
      return new BadRequestException({ statusCode: 400, code: 'VALIDATION_FAILED', message, error: 'Bad Request' })
    }
  }))
  app.useGlobalInterceptors(new LoggingInterceptor())
  await app.listen(getPort())
}

bootstrap()
