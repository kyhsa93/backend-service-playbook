import { MigrationInterface, QueryRunner } from 'typeorm'

// A column storing the account.apply-daily-interest Task's Level 1 idempotency state (whether
// interest was already paid today) (see applyInterest() in account/domain/account.ts). It uses
// the value 'INTEREST' for transaction.type, but since the column is character varying, no
// schema change is needed for that.
export class AddAccountInterest1700000000005 implements MigrationInterface {
  name = 'AddAccountInterest1700000000005'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "account" ADD COLUMN "lastInterestPaidAt" TIMESTAMP`)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "account" DROP COLUMN "lastInterestPaidAt"`)
  }
}
