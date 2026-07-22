import { Global, Module } from '@nestjs/common'

import { SecretService } from '@/common/application/service/secret-service'
import { SecretServiceImpl } from '@/common/infrastructure/secret-service-impl'

// @Global — the same pattern as outbox/outbox-module.ts. Registering SecretService here (rather
// than in AppModule's own `providers`) makes it resolvable by any domain module, since a domain
// module is imported *by* AppModule, never the other way around, and so can never reach a
// provider that lives only in AppModule's own provider list.
@Global()
@Module({
  providers: [{ provide: SecretService, useClass: SecretServiceImpl }],
  exports: [SecretService]
})
export class CommonModule {}
