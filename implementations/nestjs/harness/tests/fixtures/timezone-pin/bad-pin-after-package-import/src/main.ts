// @nestjs/core is imported before the pin, so its module graph has already run.
import { NestFactory } from '@nestjs/core'

import './timezone'
import { AppModule } from './app-module'

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule)
  await app.listen(3000)
}

void bootstrap()
