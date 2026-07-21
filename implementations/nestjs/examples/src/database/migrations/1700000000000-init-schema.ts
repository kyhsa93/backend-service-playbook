import { MigrationInterface, QueryRunner } from 'typeorm'

// TypeORM의 자동 스키마 동기화 옵션에 의존하지 않고 전체 스키마를 명시적으로 생성하는
// baseline 마이그레이션이다 — Entity를 수정할 때마다 새 마이그레이션 파일을 추가한다
// (docs/architecture/persistence.md 참고).
export class InitSchema1700000000000 implements MigrationInterface {
  name = 'InitSchema1700000000000'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "account" (
        "accountId" char(32) NOT NULL,
        "ownerId" character varying NOT NULL,
        "email" character varying NOT NULL,
        "amount" integer NOT NULL,
        "currency" char(3) NOT NULL,
        "status" character varying NOT NULL,
        "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
        "updatedAt" TIMESTAMP NOT NULL DEFAULT now(),
        "deletedAt" TIMESTAMP,
        CONSTRAINT "PK_account_accountId" PRIMARY KEY ("accountId")
      )
    `)

    await queryRunner.query(`
      CREATE TABLE "transaction" (
        "transactionId" char(32) NOT NULL,
        "accountId" char(32) NOT NULL,
        "type" character varying NOT NULL,
        "amount" integer NOT NULL,
        "currency" char(3) NOT NULL,
        "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
        CONSTRAINT "PK_transaction_transactionId" PRIMARY KEY ("transactionId")
      )
    `)

    await queryRunner.query(`
      CREATE TABLE "outbox" (
        "eventId" char(32) NOT NULL,
        "eventType" character varying NOT NULL,
        "payload" text NOT NULL,
        "processed" boolean NOT NULL DEFAULT false,
        "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
        CONSTRAINT "PK_outbox_eventId" PRIMARY KEY ("eventId")
      )
    `)

    await queryRunner.query(`
      CREATE TABLE "sent_email" (
        "sentEmailId" char(32) NOT NULL,
        "accountId" character varying NOT NULL,
        "eventType" character varying NOT NULL,
        "recipient" character varying NOT NULL,
        "subject" character varying NOT NULL,
        "sesMessageId" character varying NOT NULL,
        "sentAt" TIMESTAMP NOT NULL DEFAULT now(),
        CONSTRAINT "PK_sent_email_sentEmailId" PRIMARY KEY ("sentEmailId")
      )
    `)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP TABLE "sent_email"`)
    await queryRunner.query(`DROP TABLE "outbox"`)
    await queryRunner.query(`DROP TABLE "transaction"`)
    await queryRunner.query(`DROP TABLE "account"`)
  }
}
