import { DataSource } from 'typeorm'

import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import { CredentialEntity } from '@/auth/infrastructure/entity/credential.entity'
import { CardEntity } from '@/card/infrastructure/entity/card.entity'
import { getDatabaseUrl } from '@/config/database.config'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { PaymentEntity } from '@/payment/infrastructure/entity/payment.entity'
import { RefundEntity } from '@/payment/infrastructure/entity/refund.entity'
import { SentCardStatementEntity } from '@/payment/infrastructure/notification/sent-card-statement.entity'
import { TaskOutboxEntity } from '@/task-queue/task-outbox.entity'

// Both the CLI (typeorm migration:*) and the app (app-module.ts) share this DataSource — this
// keeps the migration target schema and the runtime connection config from drifting apart.
//
// This list must be kept up to date whenever a new entity is added (autoLoadEntities: false),
// or the app fails to find that Repository's entity metadata at boot — kept in sync here
// alongside the Task Queue entities.
export const AppDataSource = new DataSource({
  type: 'postgres',
  url: getDatabaseUrl(),
  entities: [
    AccountEntity, TransactionEntity, SentEmailEntity,
    CardEntity,
    PaymentEntity, RefundEntity, SentCardStatementEntity,
    CredentialEntity,
    OutboxEntity, TaskOutboxEntity
  ],
  migrations: [__dirname + '/migrations/*.{ts,js}'],
  synchronize: false
})
