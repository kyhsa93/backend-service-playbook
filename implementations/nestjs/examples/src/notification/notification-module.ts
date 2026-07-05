import { Module } from '@nestjs/common'
import { TypeOrmModule } from '@nestjs/typeorm'

import { NotificationService } from '@/notification/notification-service'
import { SentEmailEntity } from '@/notification/sent-email.entity'
import { SesClientProvider } from '@/notification/ses-client-provider'

@Module({
  imports: [TypeOrmModule.forFeature([SentEmailEntity])],
  providers: [NotificationService, SesClientProvider],
  exports: [NotificationService]
})
export class NotificationModule {}
