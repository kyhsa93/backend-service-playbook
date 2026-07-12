import { MigrationInterface, QueryRunner } from 'typeorm'

// Auth 모듈 실제 비밀번호 인증을 위한 credential 테이블 추가.
// userId는 Account.ownerId와 동일한 값 공간을 쓰는 외부 참조이며, 여기서도 FK 제약은 두지 않는다
// (Account BC와 마찬가지로 독립적으로 배포·변경될 수 있어야 하므로).
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
