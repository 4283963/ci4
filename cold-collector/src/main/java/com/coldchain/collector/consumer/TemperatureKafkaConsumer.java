package com.coldchain.collector.consumer;

import com.coldchain.common.constant.KafkaConstants;
import com.coldchain.common.dto.TemperatureDataDTO;
import com.coldchain.common.entity.TemperatureRecord;
import com.coldchain.common.util.SnowflakeIdGenerator;
import com.coldchain.collector.service.ClickHouseWriterService;
import com.coldchain.collector.service.TemperatureAlarmService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class TemperatureKafkaConsumer {

    @Resource
    private ClickHouseWriterService clickHouseWriterService;

    @Resource
    private TemperatureAlarmService temperatureAlarmService;

    @Value("${coldchain.clickhouse.back-pressure-threshold:0.8}")
    private double backPressureThreshold;

    @Value("${coldchain.kafka.back-pressure-enabled:true}")
    private boolean backPressureEnabled;

    private final AtomicLong totalConsumed = new AtomicLong(0);
    private final AtomicLong backPressureCount = new AtomicLong(0);
    private final AtomicBoolean underBackPressure = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        log.info("[Consumer] 温度Kafka消费者初始化 | 背压启用:{} | 背压阈值:{}%",
                backPressureEnabled, (int) (backPressureThreshold * 100));
    }

    @Override
    public void registerSeekCallback(ConsumerSeekCallback callback) {
        this.seekCallback = callback;
    }

    @KafkaListener(
            topics = {KafkaConstants.TOPIC_TEMPERATURE},
            groupId = KafkaConstants.GROUP_ID_COLLECTOR,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeBatch(List<ConsumerRecord<String, TemperatureDataDTO>> records, Acknowledgment ack) {
        if (records == null || records.isEmpty()) {
            return;
        }

        if (backPressureEnabled && checkBackPressure()) {
            handleBackPressure(records, ack);
            return;
        }

        int size = records.size();
        long start = System.currentTimeMillis();
        List<TemperatureRecord> recordList = new ArrayList<>(size);
        LocalDateTime now = LocalDateTime.now();
        SnowflakeIdGenerator idGen = SnowflakeIdGenerator.getInstance();

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
                } catch (Exception ae) {
                    log.warn("[Consumer] 报警检测异常, deviceId:{}", dto.getDeviceId());
                }
            }

            if (!recordList.isEmpty()) {
                boolean success = clickHouseWriterService.addRecords(recordList);
                if (!success) {
                    log.warn("[Consumer] ClickHouse队列已满, 部分数据可能被丢弃");
                }
            }

            ack.acknowledge();

            long total = totalConsumed.addAndGet(size);
            long cost = System.currentTimeMillis() - start;

            if (total % 5000 == 0) {
                log.info("[Consumer] 消费进度 | 累计消费:{} | 本批次:{} | 耗时:{}ms | CK队列:{} ({:.1%}) | 报警检测:异步执行",
                        total, size, cost,
                        clickHouseWriterService.getQueueSize(),
                        clickHouseWriterService.getQueueUsage());
            }

        } catch (Exception e) {
            log.error("[Consumer] 消费Kafka批次异常, 条数:{}, 原因:{}", size, e.getMessage(), e);
            throw e;
        }
    }

    private boolean checkBackPressure() {
        if (!backPressureEnabled) {
            return false;
        }
        double usage = clickHouseWriterService.getQueueUsage();
        boolean isUnderPressure = usage >= backPressureThreshold;

        if (isUnderPressure && !underBackPressure.get()) {
            underBackPressure.set(true);
            long count = backPressureCount.incrementAndGet();
            log.warn("[Consumer] ⚠️ 触发背压! CK队列使用率:{:.1%}, 第{}次进入背压状态", usage, count);
        } else if (!isUnderPressure && underBackPressure.get()) {
            underBackPressure.set(false);
            log.info("[Consumer] ✅ 背压解除, CK队列使用率:{:.1%}", usage);
        }

        applyRateLimiting(usage);
        return usage >= 0.95;
    }

    private void applyRateLimiting(double usage) {
        long sleepMs = 0;
        if (usage >= 0.95) {
            sleepMs = 1000;
        } else if (usage >= 0.90) {
            sleepMs = 500;
        } else if (usage >= 0.80) {
            sleepMs = 200;
        } else if (usage >= 0.60) {
            sleepMs = 50;
        }
        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handleBackPressure(List<ConsumerRecord<String, TemperatureDataDTO>> records, Acknowledgment ack) {
        int size = records.size();

        log.warn("[Consumer] CK队列使用率超95%, 丢弃本批次 {} 条消息以保护系统 | 队列:{}",
                size, clickHouseWriterService.getQueueSize());

        ack.acknowledge();
    }

    public long getTotalConsumed() {
        return totalConsumed.get();
    }

    public long getBackPressureCount() {
        return backPressureCount.get();
    }

    public boolean isUnderBackPressure() {
        return underBackPressure.get();
    }
}
