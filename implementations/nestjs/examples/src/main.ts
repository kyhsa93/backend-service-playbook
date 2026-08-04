// Must be the very first import in this file — see src/tracing.ts for why (it patches Node's
// http module in place, and only requests made after it runs get instrumented).
import '@/tracing'

import { NestFactory } from '@nestjs/core'
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger'

import { AppModule } from '@/app-module'
import { configureApp } from '@/app-setup'
import { getPort, isProduction } from '@/config/app.config'

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule, {
    logger: isProduction()
      ? ['error', 'warn', 'log']
      : ['error', 'warn', 'log', 'debug', 'verbose']
  })

  // The shared app-object setup — helmet security headers, the global ValidationPipe, the
  // logging/metrics interceptors, the global exception filter, CORS, and enableShutdownHooks.
  // Lives in src/app-setup.ts so E2E tests apply the identical configuration to the real
  // AppModule instead of re-assembling their own.
  configureApp(app)

  // Swagger
  const swaggerConfig = new DocumentBuilder()
    .setTitle('Account Service API')
    .setDescription('API documentation for the DDD-based Account domain example service')
    .setVersion('0.1.0')
    .addBearerAuth({ type: 'http', scheme: 'bearer', bearerFormat: 'JWT' }, 'token')
    .build()
  const swaggerDocument = SwaggerModule.createDocument(app, swaggerConfig)
  SwaggerModule.setup('docs', app, swaggerDocument)

  await app.listen(getPort())
}

bootstrap()
