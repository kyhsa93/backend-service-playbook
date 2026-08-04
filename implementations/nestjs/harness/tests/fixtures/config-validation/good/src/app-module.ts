import { Module } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'

import { appConfigValidationSchema } from './config/app.config'

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      validationSchema: appConfigValidationSchema
    })
  ]
})
export class AppModule {}
