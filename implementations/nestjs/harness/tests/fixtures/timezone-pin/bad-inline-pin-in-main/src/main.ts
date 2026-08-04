import { NestFactory } from '@nestjs/core'

import { AppModule } from './app-module'

// Hoisted below every import above, so this runs too late.
process.env.TZ = 'UTC'

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule)
  await app.listen(3000)
}

void bootstrap()
