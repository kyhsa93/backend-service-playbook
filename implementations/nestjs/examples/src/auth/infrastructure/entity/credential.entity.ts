import { Column, Entity, Index, PrimaryColumn } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'

@Entity('credential')
export class CredentialEntity extends BaseEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  credentialId: string

  @Index({ unique: true })
  @Column()
  userId: string

  @Column()
  passwordHash: string
}
