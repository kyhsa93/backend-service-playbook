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

// CLI(typeorm migration:*)와 앱(app-module.ts) 모두 이 DataSource를 공유한다 —
// 마이그레이션 대상 스키마와 런타임 연결 설정이 어긋나지 않도록 하기 위함이다.
//
// Card BC 추가(PaymentEntity/RefundEntity/CredentialEntity) 이후 이 목록이 갱신되지
// 않아 실제 앱 부팅(autoLoadEntities: false) 시 해당 Repository의 엔티티 메타데이터를
// 찾지 못하는 상태였다 — 이번에 Task Queue 엔티티를 추가하면서 함께 맞춘다.
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
