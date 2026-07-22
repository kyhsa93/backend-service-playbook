import { MigrationInterface, QueryRunner } from 'typeorm'

// The task-queue/ shared module's task_outbox table — shaped like the outbox table (for
// Domain Events), but since it's a separate concept (a Task: "perform X" vs. a Domain Event:
// "X happened"), the table is kept separate too (see docs/architecture/scheduling.md, the Task vs Domain Event section).
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
