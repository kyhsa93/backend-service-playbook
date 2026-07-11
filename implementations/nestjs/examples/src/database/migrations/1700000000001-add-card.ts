import { MigrationInterface, QueryRunner } from 'typeorm'

// Card BC 테이블 추가 — Account에 이어 두 번째 Bounded Context.
// accountId는 Account Aggregate를 ID로만 참조하는 외부 참조이며, BC 경계를 넘는
// FK 제약은 두지 않는다 (각 BC가 독립적으로 배포·변경될 수 있어야 하므로).
export class AddCard1700000000001 implements MigrationInterface {
  name = 'AddCard1700000000001'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "card" (
        "cardId" char(32) NOT NULL,
        "accountId" char(32) NOT NULL,
        "ownerId" character varying NOT NULL,
        "brand" character varying NOT NULL,
        "status" character varying NOT NULL,
        "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
        "updatedAt" TIMESTAMP NOT NULL DEFAULT now(),
        "deletedAt" TIMESTAMP,
        CONSTRAINT "PK_card_cardId" PRIMARY KEY ("cardId")
      )
    `)
    await queryRunner.query(`CREATE INDEX "IDX_card_accountId" ON "card" ("accountId")`)
    await queryRunner.query(`CREATE INDEX "IDX_card_ownerId" ON "card" ("ownerId")`)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP INDEX "IDX_card_ownerId"`)
    await queryRunner.query(`DROP INDEX "IDX_card_accountId"`)
    await queryRunner.query(`DROP TABLE "card"`)
  }
}
