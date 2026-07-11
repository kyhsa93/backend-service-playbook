import { DataSource } from 'typeorm'

import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { SentEmailEntity } from '@/account/infrastructure/notification/sent-email.entity'
import { CardEntity } from '@/card/infrastructure/entity/card.entity'
import { getDatabaseUrl } from '@/config/database.config'
import { OutboxEntity } from '@/outbox/outbox.entity'

// CLI(typeorm migration:*)와 앱(app-module.ts) 모두 이 DataSource를 공유한다 —
// 마이그레이션 대상 스키마와 런타임 연결 설정이 어긋나지 않도록 하기 위함이다.
export const AppDataSource = new DataSource({
  type: 'postgres',
  url: getDatabaseUrl(),
  entities: [AccountEntity, TransactionEntity, CardEntity, OutboxEntity, SentEmailEntity],
  migrations: [__dirname + '/migrations/*.{ts,js}'],
  synchronize: false
})
