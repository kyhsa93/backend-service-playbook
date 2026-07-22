import { MigrationInterface, QueryRunner } from 'typeorm'

// Adds the Card BC tables — the second Bounded Context after Account.
// accountId is an external reference to the Account Aggregate by ID only, with no FK
// constraint crossing the BC boundary (since each BC must be independently deployable/changeable).
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
