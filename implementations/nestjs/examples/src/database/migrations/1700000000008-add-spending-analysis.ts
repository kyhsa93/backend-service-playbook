import { MigrationInterface, QueryRunner } from 'typeorm'

// The read-model table account.analyze-monthly-spending's ETL writes to, one row per
// (accountId, analysisMonth) — the unique index is the idempotency backstop, the same role as
// sent_card_statement's (cardId, statementMonth) constraint.
export class AddSpendingAnalysis1700000000008 implements MigrationInterface {
  name = 'AddSpendingAnalysis1700000000008'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "spending_analysis" (
        "analysisId" char(32) NOT NULL,
        "accountId" char(32) NOT NULL,
        "analysisMonth" char(7) NOT NULL,
        "totalAmount" integer NOT NULL,
        "transactionCount" integer NOT NULL,
        "averageAmount" integer NOT NULL,
        "changeFromPreviousMonth" integer NOT NULL,
        "trend" character varying NOT NULL,
        "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
        "updatedAt" TIMESTAMP NOT NULL DEFAULT now(),
        "deletedAt" TIMESTAMP,
        CONSTRAINT "PK_spending_analysis_analysisId" PRIMARY KEY ("analysisId")
      )
    `)
    await queryRunner.query(`
      CREATE UNIQUE INDEX "IDX_spending_analysis_accountId_analysisMonth" ON "spending_analysis" ("accountId", "analysisMonth")
    `)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP INDEX "IDX_spending_analysis_accountId_analysisMonth"`)
    await queryRunner.query(`DROP TABLE "spending_analysis"`)
  }
}
