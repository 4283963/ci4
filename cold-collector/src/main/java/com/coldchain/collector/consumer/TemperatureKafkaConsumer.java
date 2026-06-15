package com.coldchain.collector.consumer;

import com.coldchain.common.constant.KafkaConstants;
import com.coldchain.common.dto.TemperatureDataDTO;
import com.coldchain.common.entity.TemperatureRecord;
import com.coldchain.common.util.SnowflakeIdGenerator;
import com.coldchain.collector.service.ClickHouseWriterService;
import com.coldchain.collector.service.TemperatureAlarmService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class TemperatureKafkaConsumer {

    @Resource
    private ClickHouseWriterService clickHouseWriterService;

    @Resource
    private TemperatureAlarmService temperatureAlarmService;

    @KafkaListener(
            topics = {KafkaConstants.TOPIC_TEMPERATURE},
            groupId = KafkaConstants.GROUP_ID_COLLECTOR,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeBatch(List<ConsumerRecord<String, TemperatureDataDTO>> records, Acknowledgment ack) {
        if (records == null || records.isEmpty()) {
            return;
        }

        int size = records.size();
        long start = System.currentTimeMillis();
        List<TemperatureRecord> recordList = new ArrayList<>(size);
        LocalDateTime now = LocalDateTime.now();
        SnowflakeIdGenerator idGen = SnowflakeIdGenerator.getInstance();
        int alarmCount = 0;

        try {
            for (ConsumerRecord<String, TemperatureDataDTO> cr : records) {
                TemperatureDataDTO dto = cr.value();
                if (dto == null) {
                    continue;
                }

                TemperatureRecord record = TemperatureRecord.builder()
                        .id(idGen.nextId())
                        .deviceId(dto.getDeviceId())
                        .vehicleNo(dto.getVehicleNo())
                        .temperature(dto.getTemperature())
                        .humidity(dto.getHumidity())
                        .longitude(dto.getLongitude())
                        .latitude(dto.getLatitude())
                        .status(dto.getStatus() != null ? dto.getStatus() : 1)
                        .reportTime(dto.getReportTime() != null ? dto.getReportTime() : now)
                        .createTime(now)
                        .build();
                recordList.add(record);

                try {
                    temperatureAlarmService.checkAndTriggerAlarm(dto);
                    alarmCount++;
                } catch (Exception ae) {
                    log.warn("[Consumer] 报警检测异常, deviceId:{}", dto.getDeviceId());
                }
            }

            if (!recordList.isEmpty()) {
                clickHouseWriterService.addRecords(recordList);
            }

            ack.acknowledge();

            long cost = System.currentTimeMillis() - start;
            log.info("[Consumer] 消费Kafka批次成功 | 条数:{} | 写入CK:{} | 报警检测:{} | 耗时:{}ms | partition范围:{}-{}",
                    size, recordList.size(), alarmCount, cost,
                    records.get(0).partition(),
                    records.get(records.size() - 1).partition());

        } catch (Exception e) {
            log.error("[Consumer] 消费Kafka批次异常, 条数:{}, 原因:{}", size, e.getMessage(), e);
            throw e;
        }
    }
}
