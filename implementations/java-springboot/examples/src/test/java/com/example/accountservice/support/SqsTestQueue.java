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
 * Creates the domain-events queue + DLQ used by {@code OutboxPoller}/{@code OutboxConsumer} in
 * LocalStack SQS for E2E tests. Reproduces, via SDK calls, the same configuration as {@code
 * localstack/init-sqs.sh} (used for local docker-compose development) — create the DLQ first, then
 * link it via RedrivePolicy, maxReceiveCount=3 — since the Testcontainers {@code
 * LocalStackContainer} does not mount the local init script, we build it directly from test code.
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
     * Creates the Task Queue (FIFO) + DLQ used by {@code TaskOutboxPoller}/{@code TaskConsumer} in
     * LocalStack SQS for E2E tests — reproduces the task-queue.fifo configuration from {@code
     * localstack/init-sqs.sh} via SDK calls (same reason as createDomainEventQueue). Unlike the
     * Domain Event queue, this is a FIFO queue, so it needs the {@code FifoQueue} attribute.
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
