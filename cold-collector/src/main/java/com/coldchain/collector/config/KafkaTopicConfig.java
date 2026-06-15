package com.coldchain.collector.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import static com.coldchain.common.constant.KafkaConstants.PARTITION_COUNT;
import static com.coldchain.common.constant.KafkaConstants.REPLICATION_FACTOR;
import static com.coldchain.common.constant.KafkaConstants.TOPIC_TEMPERATURE;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic temperatureTopic() {
        return TopicBuilder.name(TOPIC_TEMPERATURE)
                .partitions(PARTITION_COUNT)
                .replicas(REPLICATION_FACTOR)
                .config("retention.ms", "604800000")
                .config("segment.ms", "3600000")
                .build();
    }
}
