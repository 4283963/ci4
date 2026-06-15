package com.coldchain.collector.controller;

import com.coldchain.collector.consumer.TemperatureKafkaConsumer;
import com.coldchain.collector.service.ClickHouseWriterService;
import com.coldchain.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/monitor")
public class MonitorController {

    @Resource
    private ClickHouseWriterService clickHouseWriterService;

    @Resource
    private TemperatureKafkaConsumer temperatureKafkaConsumer;

    @GetMapping("/status")
    public Result<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        DecimalFormat df = new DecimalFormat("0.00%");

        long ckWritten = clickHouseWriterService.getTotalWritten();
        long ckFailed = clickHouseWriterService.getTotalFailed();
        long ckDropped = clickHouseWriterService.getTotalDropped();
        long ckFlush = clickHouseWriterService.getFlushCount();
        int queueSize = clickHouseWriterService.getQueueSize();
        double queueUsage = clickHouseWriterService.getQueueUsage();
        boolean underBackPressure = clickHouseWriterService.isUnderBackPressure();
        String lastError = clickHouseWriterService.getLastErrorMessage();

        long kafkaConsumed = temperatureKafkaConsumer.getTotalConsumed();
        long backPressureCount = temperatureKafkaConsumer.getBackPressureCount();
        boolean kafkaUnderBackPressure = temperatureKafkaConsumer.isUnderBackPressure();

        status.put("timestamp", LocalDateTime.now().toString());
        status.put("clickhouse", Map.of(
                "totalWritten", ckWritten,
                "totalFailed", ckFailed,
                "totalDropped", ckDropped,
                "flushCount", ckFlush,
                "queueSize", queueSize,
                "queueUsage", df.format(queueUsage),
                "underBackPressure", underBackPressure,
                "lastErrorMessage", lastError != null ? lastError : ""
        ));
        status.put("kafka", Map.of(
                "totalConsumed", kafkaConsumed,
                "backPressureTriggered", backPressureCount,
                "underBackPressure", kafkaUnderBackPressure
        ));
        status.put("health", Map.of(
                "status", (ckFailed == 0 || ckDropped == 0) ? "OK" : "WARNING",
                "ckWritable", !underBackPressure
        ));

        return Result.success(status);
    }

    @GetMapping("/ck/health")
    public Result<String> ckHealth() {
        int queueSize = clickHouseWriterService.getQueueSize();
        double usage = clickHouseWriterService.getQueueUsage();
        String lastError = clickHouseWriterService.getLastErrorMessage();

        if (lastError != null && !lastError.isEmpty()) {
            return Result.fail("UNHEALTHY", "ClickHouse写入异常: " + lastError);
        }
        if (usage > 0.9) {
            return Result.fail("WARNING", "队列使用率超过90%, 当前:" + queueSize);
        }
        return Result.success("正常, 队列:" + queueSize + ", 使用率:" + new DecimalFormat("0.0%").format(usage));
    }
}
