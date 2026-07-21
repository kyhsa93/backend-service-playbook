import { MigrationInterface, QueryRunner } from 'typeorm'

// account.apply-daily-interest Task의 Level 1 멱등성 상태(오늘 이미 이자를 지급했는지)를
// 저장하는 컬럼이다(account/domain/account.ts의 applyInterest() 참고). transaction.type에
// 'INTEREST' 값을 쓰지만 컬럼이 character varying이라 스키마 변경은 필요 없다.
export class AddAccountInterest1700000000005 implements MigrationInterface {
  name = 'AddAccountInterest1700000000005'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "account" ADD COLUMN "lastInterestPaidAt" TIMESTAMP`)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "account" DROP COLUMN "lastInterestPaidAt"`)
  }
}
