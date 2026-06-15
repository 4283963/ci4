package com.coldchain.collector.controller;

import com.coldchain.common.constant.KafkaConstants;
import com.coldchain.common.dto.TemperatureDataDTO;
import com.coldchain.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/collect")
public class DataCollectController {

    @Resource
    private KafkaTemplate<String, TemperatureDataDTO> kafkaTemplate;

    @PostMapping("/temperature")
    public Result<String> collectTemperature(@RequestBody @Validated TemperatureDataDTO dto) {
        if (dto.getReportTime() == null) {
            dto.setReportTime(LocalDateTime.now());
        }
        String key = dto.getDeviceId();
        ListenableFuture<SendResult<String, TemperatureDataDTO>> future =
                kafkaTemplate.send(KafkaConstants.TOPIC_TEMPERATURE, key, dto);

        future.addCallback(new ListenableFutureCallback<SendResult<String, TemperatureDataDTO>>() {
            @Override
            public void onFailure(Throwable ex) {
                log.error("[Collect] 发送Kafka失败, deviceId:{}, 原因:{}", dto.getDeviceId(), ex.getMessage(), ex);
            }

            @Override
            public void onSuccess(SendResult<String, TemperatureDataDTO> result) {
                log.debug("[Collect] 发送Kafka成功, deviceId:{}, partition:{}, offset:{}",
                        dto.getDeviceId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });

        return Result.success("数据已接收");
    }

    @PostMapping("/temperature/batch")
    public Result<String> collectTemperatureBatch(@RequestBody List<TemperatureDataDTO> dtoList) {
        List<ListenableFuture<SendResult<String, TemperatureDataDTO>>> futures = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (TemperatureDataDTO dto : dtoList) {
            if (dto.getReportTime() == null) {
                dto.setReportTime(now);
            }
            ListenableFuture<SendResult<String, TemperatureDataDTO>> future =
                    kafkaTemplate.send(KafkaConstants.TOPIC_TEMPERATURE, dto.getDeviceId(), dto);
            futures.add(future);
        }

        log.info("[Collect] 批量接收数据 {} 条, 已发送至Kafka", dtoList.size());
        return Result.success("批量数据已接收, 共" + dtoList.size() + "条");
    }
}
