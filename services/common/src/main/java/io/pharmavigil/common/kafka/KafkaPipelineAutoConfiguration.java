// Kafka pipeline defaults for every service with Kafka: the pipeline topics and their dead-letter twins are
// created at start-up, and listener errors are retried (3 more attempts, 500 ms apart) before the record goes
// to "<topic>.dlq" with the failure in its headers. Poison messages skip the retries.
package io.pharmavigil.common.kafka;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

import io.pharmavigil.common.events.Topics;

@AutoConfiguration(afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(KafkaTemplate.class)
public class KafkaPipelineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    @ConditionalOnBean(KafkaTemplate.class)
    CommonErrorHandler pipelineErrorHandler(KafkaTemplate<Object, Object> template,
            @Value("${pv.kafka.retries:3}") long retries, @Value("${pv.kafka.retry-interval-ms:500}") long interval) {
        DeadLetterPublishingRecoverer dlq = new DeadLetterPublishingRecoverer(template,
                (record, e) -> new TopicPartition(Topics.dlq(record.topic()), -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(dlq, new FixedBackOff(interval, retries));
        handler.addNotRetryableExceptions(PoisonMessageException.class, DeserializationException.class);
        return handler;
    }

    @Bean
    @ConditionalOnBean(KafkaAdmin.class)
    KafkaAdmin.NewTopics pipelineTopics(@Value("${pv.kafka.partitions:3}") int partitions,
            @Value("${pv.kafka.replicas:1}") int replicas) {
        List<NewTopic> topics = new ArrayList<>();
        for (String t : Topics.ALL) {
            topics.add(TopicBuilder.name(t).partitions(partitions).replicas(replicas).build());
            topics.add(TopicBuilder.name(Topics.dlq(t)).partitions(partitions).replicas(replicas).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
}
