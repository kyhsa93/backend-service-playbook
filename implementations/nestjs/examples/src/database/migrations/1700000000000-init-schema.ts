import { MigrationInterface, QueryRunner } from 'typeorm'

// A baseline migration that explicitly creates the entire schema, rather than relying on
// TypeORM's automatic schema-sync option — add a new migration file whenever an Entity is
// modified (see docs/architecture/persistence.md).
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
