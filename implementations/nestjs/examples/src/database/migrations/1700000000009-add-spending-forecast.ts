import { MigrationInterface, QueryRunner } from 'typeorm'

// The read-model table account.forecast-spending's ETL writes to, one row per (accountId,
// forecastMonth) — the unique index is the idempotency backstop, the same role as
// spending_analysis's (accountId, analysisMonth) constraint.
export class AddSpendingForecast1700000000009 implements MigrationInterface {
  name = 'AddSpendingForecast1700000000009'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "spending_forecast" (
        "forecastId" char(32) NOT NULL,
        "accountId" char(32) NOT NULL,
        "forecastMonth" char(7) NOT NULL,
        "predictedAmount" integer NOT NULL,
        "confidence" character varying NOT NULL,
        "historyMonthsUsed" integer NOT NULL,
        "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
        "updatedAt" TIMESTAMP NOT NULL DEFAULT now(),
        "deletedAt" TIMESTAMP,
        CONSTRAINT "PK_spending_forecast_forecastId" PRIMARY KEY ("forecastId")
      )
    `)
    await queryRunner.query(`
      CREATE UNIQUE INDEX "IDX_spending_forecast_accountId_forecastMonth" ON "spending_forecast" ("accountId", "forecastMonth")
    `)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP INDEX "IDX_spending_forecast_accountId_forecastMonth"`)
    await queryRunner.query(`DROP TABLE "spending_forecast"`)
  }
}
