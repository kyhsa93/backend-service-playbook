import { Global, Module } from '@nestjs/common'

import { SecretService } from '@/common/application/service/secret-service'
import { SecretServiceImpl } from '@/common/infrastructure/secret-service-impl'

// @Global — the same pattern as outbox/outbox-module.ts. Before this module existed,
// SecretService was registered directly in AppModule's own `providers`, which is unreachable
// from any domain module (a domain module is imported *by* AppModule, never the other way
// around, so it can't resolve a provider that only lives in AppModule's own provider list).
// RefundReasonClassifierImpl (payment/infrastructure/) is this module's first real consumer.
@Global()
@Module({
  providers: [{ provide: SecretService, useClass: SecretServiceImpl }],
  exports: [SecretService]
})
export class CommonModule {}
