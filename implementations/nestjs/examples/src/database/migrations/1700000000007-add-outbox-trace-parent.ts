import { MigrationInterface, QueryRunner } from 'typeorm'

// Adds the column OutboxWriter/OutboxPoller/OutboxConsumer use to carry the W3C traceparent
// header across the async SQS hop, so an HTTP request and its event processing land in one
// trace (see src/outbox/trace-context.ts, docs/architecture/observability.md).
export class AddOutboxTraceParent1700000000007 implements MigrationInterface {
  name = 'AddOutboxTraceParent1700000000007'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "outbox" ADD "traceParent" character varying`)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "outbox" DROP COLUMN "traceParent"`)
  }
}
