import { MigrationInterface, QueryRunner } from 'typeorm'

// Adds the Payment BC tables — the third Bounded Context after Account, Card
// (the two Aggregates Payment/Refund). cardId/accountId are external references to the
// Card/Account Aggregates by ID only, with no FK constraint crossing the BC boundary
// (since each BC must be independently deployable/changeable) — the same principle as when the Card tables were added.
//
// transaction.referenceId is the idempotency-check key ensuring Account BC's reaction to
// Payment BC's Integration Events (payment.completed.v1, etc.) never creates the same
// transaction twice even under at-least-once re-receipt (a Level 2 Ledger, see docs/architecture/domain-events.md).
export class AddPayment1700000000003 implements MigrationInterface {
  name = 'AddPayment1700000000003'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "payment" (
        "paymentId" char(32) NOT NULL,
        "cardId" char(32) NOT NULL,
        "accountId" char(32) NOT NULL,
        "ownerId" character varying NOT NULL,
        "amount" integer NOT NULL,
        "status" character varying NOT NULL,
        "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
        "updatedAt" TIMESTAMP NOT NULL DEFAULT now(),
        "deletedAt" TIMESTAMP,
        CONSTRAINT "PK_payment_paymentId" PRIMARY KEY ("paymentId")
      )
    `)
    await queryRunner.query(`CREATE INDEX "IDX_payment_ownerId" ON "payment" ("ownerId")`)
    await queryRunner.query(`CREATE INDEX "IDX_payment_cardId" ON "payment" ("cardId")`)
    await queryRunner.query(`CREATE INDEX "IDX_payment_accountId" ON "payment" ("accountId")`)

    await queryRunner.query(`
      CREATE TABLE "refund" (
        "refundId" char(32) NOT NULL,
        "paymentId" char(32) NOT NULL,
        "amount" integer NOT NULL,
        "reason" character varying NOT NULL,
        "status" character varying NOT NULL,
        "decisionNote" character varying,
        "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
        "updatedAt" TIMESTAMP NOT NULL DEFAULT now(),
        "deletedAt" TIMESTAMP,
        CONSTRAINT "PK_refund_refundId" PRIMARY KEY ("refundId")
      )
    `)
    await queryRunner.query(`CREATE INDEX "IDX_refund_paymentId" ON "refund" ("paymentId")`)

    await queryRunner.query(`ALTER TABLE "transaction" ADD COLUMN "referenceId" character varying`)
    // Unique only for the (referenceId, type) combination — since a completed payment
    // (WITHDRAWAL) and its cancellation's compensating credit (DEPOSIT) are different
    // transactions that share the same paymentId as referenceId, a unique constraint on
    // referenceId alone would block inserting the compensating credit itself. This adds a
    // second DB-level defense against the extremely brief race where a duplicate Integration
    // Event arriving concurrently slips past the application's check (hasTransactionWithReference)
    // — doubling up the Level 2 Ledger's defense line.
    await queryRunner.query(`
      CREATE UNIQUE INDEX "IDX_transaction_referenceId_type" ON "transaction" ("referenceId", "type") WHERE "referenceId" IS NOT NULL
    `)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP INDEX "IDX_transaction_referenceId_type"`)
    await queryRunner.query(`ALTER TABLE "transaction" DROP COLUMN "referenceId"`)
    await queryRunner.query(`DROP INDEX "IDX_refund_paymentId"`)
    await queryRunner.query(`DROP TABLE "refund"`)
    await queryRunner.query(`DROP INDEX "IDX_payment_accountId"`)
    await queryRunner.query(`DROP INDEX "IDX_payment_cardId"`)
    await queryRunner.query(`DROP INDEX "IDX_payment_ownerId"`)
    await queryRunner.query(`DROP TABLE "payment"`)
  }
}
