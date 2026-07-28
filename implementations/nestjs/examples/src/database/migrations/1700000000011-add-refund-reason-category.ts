import { MigrationInterface, QueryRunner } from 'typeorm'

// Nullable: filled in asynchronously by ClassifyRefundReasonHandler, some time after the
// refund row itself is created.
export class AddRefundReasonCategory1700000000011 implements MigrationInterface {
  name = 'AddRefundReasonCategory1700000000011'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "refund" ADD "reasonCategory" character varying`)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "refund" DROP COLUMN "reasonCategory"`)
  }
}
