package com.example.accountservice.support;

import java.net.URI;
import java.util.Map;
import org.testcontainers.containers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * E2E 테스트용 LocalStack SQS에 {@code OutboxPoller}/{@code OutboxConsumer}가 쓸 domain-events 큐 + DLQ를
 * 만든다. {@code localstack/init-sqs.sh}(docker-compose 로컬 개발용)와 동일한 구성(DLQ 우선 생성 → RedrivePolicy로 연결,
 * maxReceiveCount=3)을 SDK 호출로 재현한다 — Testcontainers {@code LocalStackContainer}는 로컬 init 스크립트를
 * 마운트하지 않으므로 테스트 코드에서 직접 만든다.
 */
public final class SqsTestQueue {

    private SqsTestQueue() {}

    public static String createDomainEventQueue(LocalStackContainer localstack) {
        try (SqsClient client =
                SqsClient.builder()
                        .region(Region.of(localstack.getRegion()))
                        .endpointOverride(
                                URI.create(
                                        localstack
                                                .getEndpointOverride(
                                                        LocalStackContainer.Service.SQS)
                                                .toString()))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                localstack.getAccessKey(),
                                                localstack.getSecretKey())))
                        .build()) {
            CreateQueueResponse dlq =
                    client.createQueue(
                            CreateQueueRequest.builder().queueName("domain-events-dlq").build());
            GetQueueAttributesResponse dlqAttributes =
                    client.getQueueAttributes(
                            GetQueueAttributesRequest.builder()
                                    .queueUrl(dlq.queueUrl())
                                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                                    .build());
            String dlqArn = dlqAttributes.attributes().get(QueueAttributeName.QUEUE_ARN);

            CreateQueueResponse mainQueue =
                    client.createQueue(
                            CreateQueueRequest.builder()
                                    .queueName("domain-events")
                                    .attributes(
                                            Map.of(
                                                    QueueAttributeName.REDRIVE_POLICY,
                                                    "{\"deadLetterTargetArn\":\""
                                                            + dlqArn
                                                            + "\",\"maxReceiveCount\":\"3\"}"))
                                    .build());
            return mainQueue.queueUrl();
        }
    }

    /**
     * E2E 테스트용 LocalStack SQS에 {@code TaskOutboxPoller}/{@code TaskConsumer}가 쓸 Task Queue(FIFO) +
     * DLQ를 만든다 — {@code localstack/init-sqs.sh}의 task-queue.fifo 구성을 SDK 호출로 재현한다
     * (createDomainEventQueue와 동일한 이유). Domain Event 큐와 달리 FIFO 큐라 {@code FifoQueue} 속성이 필요하다.
     */
    public static String createTaskQueue(LocalStackContainer localstack) {
        try (SqsClient client =
                SqsClient.builder()
                        .region(Region.of(localstack.getRegion()))
                        .endpointOverride(
                                URI.create(
                                        localstack
                                                .getEndpointOverride(
                                                        LocalStackContainer.Service.SQS)
                                                .toString()))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                localstack.getAccessKey(),
                                                localstack.getSecretKey())))
                        .build()) {
            CreateQueueResponse dlq =
                    client.createQueue(
                            CreateQueueRequest.builder()
                                    .queueName("task-queue-dlq.fifo")
                                    .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))
                                    .build());
            GetQueueAttributesResponse dlqAttributes =
                    client.getQueueAttributes(
                            GetQueueAttributesRequest.builder()
                                    .queueUrl(dlq.queueUrl())
                                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                                    .build());
            String dlqArn = dlqAttributes.attributes().get(QueueAttributeName.QUEUE_ARN);

            CreateQueueResponse mainQueue =
                    client.createQueue(
                            CreateQueueRequest.builder()
                                    .queueName("task-queue.fifo")
                                    .attributes(
                                            Map.of(
                                                    QueueAttributeName.FIFO_QUEUE,
                                                    "true",
                                                    QueueAttributeName.REDRIVE_POLICY,
                                                    "{\"deadLetterTargetArn\":\""
                                                            + dlqArn
                                                            + "\",\"maxReceiveCount\":\"3\"}"))
                                    .build());
            return mainQueue.queueUrl();
        }
    }
}
