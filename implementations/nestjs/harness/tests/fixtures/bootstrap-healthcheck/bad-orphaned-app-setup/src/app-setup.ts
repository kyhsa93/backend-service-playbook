import { INestApplication, ValidationPipe } from '@nestjs/common'

// Present on disk but never imported by main.ts — must NOT satisfy the bootstrap rules.
export function configureApp(app: INestApplication): void {
  app.useGlobalPipes(new ValidationPipe({ whitelist: true }))
  app.enableShutdownHooks()
}
