package com.coldchain.common.constant;

public interface KafkaConstants {

    String TOPIC_TEMPERATURE = "cold_temperature_data";

    String GROUP_ID_COLLECTOR = "cold-collector-group";

    int PARTITION_COUNT = 8;

    short REPLICATION_FACTOR = 1;
}
