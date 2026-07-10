import { Module } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'
import { TypeOrmModule } from '@nestjs/typeorm'

import { AccountModule } from '@/account/account-module'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { AuthModule } from '@/auth/auth-module'
import { jwtConfig } from '@/config/jwt.config'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { OutboxModule } from '@/outbox/outbox-module'
import { SentEmailEntity } from '@/notification/sent-email.entity'

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig] }),
    TypeOrmModule.forRoot({
      type: 'postgres',
      url: process.env.DATABASE_URL,
      entities: [AccountEntity, TransactionEntity, OutboxEntity, SentEmailEntity],
      synchronize: true
    }),
    OutboxModule,
    AuthModule,
    AccountModule
  ]
})
export class AppModule {}
