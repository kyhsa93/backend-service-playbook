import { MigrationInterface, QueryRunner } from 'typeorm'

// Payment BC 테이블 추가 — Account, Card에 이어 세 번째 Bounded Context
// (Payment/Refund 두 Aggregate). cardId/accountId는 각각 Card/Account Aggregate를
// ID로만 참조하는 외부 참조이며, BC 경계를 넘는 FK 제약은 두지 않는다
// (각 BC가 독립적으로 배포·변경될 수 있어야 하므로) — Card 테이블 추가 때와 동일한 원칙.
//
// transaction.referenceId는 Payment BC의 Integration Event(payment.completed.v1 등)에
// 대한 Account BC의 반응이 at-least-once 재수신에도 같은 거래를 중복 생성하지 않도록
// 하는 멱등성 판단 키다(Level 2 Ledger, docs/architecture/domain-events.md 참고).
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
    // (referenceId, type) 조합에 한해서만 유니크 — 결제완료(WITHDRAWAL)와 그 결제취소
    // 보상 크레딧(DEPOSIT)은 같은 paymentId를 referenceId로 공유하는 서로 다른 거래이므로
    // referenceId 단독 유니크는 보상 크레딧 삽입 자체를 막아버린다. 동시에 도착한 중복
    // Integration Event가 애플리케이션의 체크(hasTransactionWithReference)를 통과해버리는
    // 극히 짧은 race를 DB 제약으로 한 번 더 막는다(Level 2 Ledger의 방어선을 이중화).
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
