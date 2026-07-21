import { MigrationInterface, QueryRunner } from 'typeorm'

// payment.send-card-statements Task의 발송 기록 테이블 — sent_email과 같은 모양이다.
// (cardId, statementMonth) 유니크 제약이 같은 달 중복 발송을 막는 최종 방어선이다
// (payment/infrastructure/notification/sent-card-statement.entity.ts 참고).
export class AddCardStatement1700000000006 implements MigrationInterface {
  name = 'AddCardStatement1700000000006'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "sent_card_statement" (
        "sentCardStatementId" char(32) NOT NULL,
        "cardId" char(32) NOT NULL,
        "accountId" char(32) NOT NULL,
        "statementMonth" char(7) NOT NULL,
        "paymentCount" integer NOT NULL,
        "totalAmount" integer NOT NULL,
        "currency" char(3) NOT NULL,
        "recipient" character varying NOT NULL,
        "sesMessageId" character varying NOT NULL,
        "sentAt" TIMESTAMP NOT NULL DEFAULT now(),
        CONSTRAINT "PK_sent_card_statement_sentCardStatementId" PRIMARY KEY ("sentCardStatementId")
      )
    `)
    await queryRunner.query(`
      CREATE UNIQUE INDEX "IDX_sent_card_statement_cardId_statementMonth" ON "sent_card_statement" ("cardId", "statementMonth")
    `)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP INDEX "IDX_sent_card_statement_cardId_statementMonth"`)
    await queryRunner.query(`DROP TABLE "sent_card_statement"`)
  }
}
