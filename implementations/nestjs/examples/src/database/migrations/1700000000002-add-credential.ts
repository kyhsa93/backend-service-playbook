import { MigrationInterface, QueryRunner } from 'typeorm'

// Adds the credential table for the Auth module's actual password authentication.
// userId is an external reference sharing the same value space as Account.ownerId, and here
// too, no FK constraint is added (since it must be independently deployable/changeable, just like Account BC).
export class AddCredential1700000000002 implements MigrationInterface {
  name = 'AddCredential1700000000002'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "credential" (
        "credentialId" char(32) NOT NULL,
        "userId" character varying NOT NULL,
        "passwordHash" character varying NOT NULL,
        "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
        "updatedAt" TIMESTAMP NOT NULL DEFAULT now(),
        "deletedAt" TIMESTAMP,
        CONSTRAINT "PK_credential_credentialId" PRIMARY KEY ("credentialId")
      )
    `)
    await queryRunner.query(`CREATE UNIQUE INDEX "IDX_credential_userId" ON "credential" ("userId")`)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP INDEX "IDX_credential_userId"`)
    await queryRunner.query(`DROP TABLE "credential"`)
  }
}
