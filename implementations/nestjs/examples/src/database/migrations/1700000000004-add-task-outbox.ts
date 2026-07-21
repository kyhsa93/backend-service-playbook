import { MigrationInterface, QueryRunner } from 'typeorm'

// task-queue/ 공유 모듈의 task_outbox 테이블 — outbox 테이블(Domain Event용)과 같은
// 모양이지만 별개 개념(Task: "X를 수행하라" vs Domain Event: "X가 일어났다")이라 테이블도
// 분리한다(docs/architecture/scheduling.md#task-vs-domain-event).
export class AddTaskOutbox1700000000004 implements MigrationInterface {
  name = 'AddTaskOutbox1700000000004'

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`
      CREATE TABLE "task_outbox" (
        "taskId" char(32) NOT NULL,
        "taskType" character varying NOT NULL,
        "payload" text NOT NULL,
        "groupId" character varying NOT NULL,
        "deduplicationId" character varying NOT NULL,
        "delaySeconds" integer,
        "processed" boolean NOT NULL DEFAULT false,
        "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
        CONSTRAINT "PK_task_outbox_taskId" PRIMARY KEY ("taskId")
      )
    `)
    await queryRunner.query(`CREATE INDEX "IDX_task_outbox_processed_createdAt" ON "task_outbox" ("processed", "createdAt")`)
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP INDEX "IDX_task_outbox_processed_createdAt"`)
    await queryRunner.query(`DROP TABLE "task_outbox"`)
  }
}
