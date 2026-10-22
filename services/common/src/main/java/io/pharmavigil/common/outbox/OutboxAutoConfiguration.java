// Starts the outbox relay in every service that has both a database and Kafka. Properties:
// pv.outbox.enabled (default true), pv.outbox.poll-interval (500 ms), pv.outbox.batch-size (100).
package io.pharmavigil.common.outbox;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

@AutoConfiguration(afterName = {"org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "io.pharmavigil.common.security.SecurityAutoConfiguration"})
@ConditionalOnClass({KafkaTemplate.class, JdbcTemplate.class})
@ConditionalOnProperty(name = "pv.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({KafkaTemplate.class, JdbcTemplate.class})
    OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, Clock clock,
            @Value("${pv.outbox.poll-interval:PT0.5S}") Duration interval,
            @Value("${pv.outbox.batch-size:100}") int batchSize,
            @Value("${pv.outbox.send-timeout:PT10S}") Duration sendTimeout) {
        return new OutboxRelay(jdbc, kafka, clock, interval, batchSize, sendTimeout);
    }
}
