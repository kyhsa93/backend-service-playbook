import { MigrationInterface, QueryRunner } from 'typeorm'

// Both columns nullable: merchantName is only ever set for a withdrawal the requester chose to
// attach one to, and category is filled in later, asynchronously, by CategorizeTransactionHandler.
export class AddTransactionMerchantAndCategory1700000000010 implements MigrationInterface {
  name = 'AddTransactionMerchantAndCategory1700000000010'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "transaction" ADD "merchantName" character varying`)
    await queryRunner.query(`ALTER TABLE "transaction" ADD "category" character varying`)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "transaction" DROP COLUMN "category"`)
    await queryRunner.query(`ALTER TABLE "transaction" DROP COLUMN "merchantName"`)
  }
}
