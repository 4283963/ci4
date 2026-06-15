package com.coldchain.collector.service;

import com.coldchain.common.constant.AlarmConstants;
import com.coldchain.common.dto.AlarmRequestDTO;
import com.coldchain.common.dto.AlarmResponseDTO;
import com.coldchain.common.dto.TemperatureDataDTO;
import com.coldchain.common.result.Result;
import com.coldchain.common.util.SnowflakeIdGenerator;
import com.coldchain.collector.feign.AlarmFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
public class TemperatureAlarmService {

    @Value("${coldchain.alarm.retry-times:3}")
    private int retryTimes;

    @Resource
    private AlarmFeignClient alarmFeignClient;

    @Async("alarmExecutor")
    public void checkAndTriggerAlarm(TemperatureDataDTO data) {
        if (data == null || data.getTemperature() == null) {
            return;
        }
        try {
            BigDecimal temp = data.getTemperature();
            BigDecimal upper = AlarmConstants.TEMP_UPPER_THRESHOLD;
            BigDecimal lower = AlarmConstants.TEMP_LOWER_THRESHOLD;

            if (temp.compareTo(upper) > 0) {
                triggerAlarm(data, AlarmConstants.ALARM_TYPE_HIGH_TEMP, upper);
            } else if (temp.compareTo(lower) < 0) {
                triggerAlarm(data, AlarmConstants.ALARM_TYPE_LOW_TEMP, lower);
            }
        } catch (Exception e) {
            log.error("[AlarmCheck] 温度检测异常, deviceId:{}, 原因:{}", data.getDeviceId(), e.getMessage(), e);
        }
    }

    private void triggerAlarm(TemperatureDataDTO data, int alarmType, BigDecimal threshold) {
        String alarmId = SnowflakeIdGenerator.getInstance().nextIdStr();
        String alarmMsg = buildAlarmMessage(alarmType, data.getTemperature(), threshold);
        int alarmLevel = calculateAlarmLevel(alarmType, data.getTemperature(), threshold);

        AlarmRequestDTO request = AlarmRequestDTO.builder()
                .alarmId(alarmId)
                .deviceId(data.getDeviceId())
                .vehicleNo(data.getVehicleNo())
                .alarmType(alarmType)
                .alarmMessage(alarmMsg)
                .currentValue(data.getTemperature())
                .threshold(threshold)
                .longitude(data.getLongitude())
                .latitude(data.getLatitude())
                .alarmTime(data.getReportTime() != null ? data.getReportTime() : LocalDateTime.now())
                .build();

        log.warn("[AlarmTrigger] 触发温度报警 | alarmId:{} | deviceId:{} | vehicleNo:{} | type:{} | 温度:{} | 阈值:{} | 级别:{}",
                alarmId, data.getDeviceId(), data.getVehicleNo(), alarmType,
                data.getTemperature(), threshold, alarmLevel);

        int attempts = 0;
        while (attempts < retryTimes) {
            attempts++;
            try {
                Result<AlarmResponseDTO> result = alarmFeignClient.receiveAlarm(request);
                if (result != null && result.getCode() != null && result.getCode() == 200) {
                    log.info("[AlarmCall] 报警服务调用成功 | alarmId:{} | 尝试次数:{}", alarmId, attempts);
                    return;
                } else {
                    log.warn("[AlarmCall] 报警服务返回失败 | alarmId:{} | 第{}次 | result:{}", alarmId, attempts, result);
                }
            } catch (Exception e) {
                log.error("[AlarmCall] 报警服务调用异常 | alarmId:{} | 第{}次 | 原因:{}",
                        alarmId, attempts, e.getMessage());
            }
            if (attempts < retryTimes) {
                try {
                    Thread.sleep(100L * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("[AlarmCall] 报警服务调用最终失败 | alarmId:{} | 总尝试:{}", alarmId, retryTimes);
    }

    private String buildAlarmMessage(int alarmType, BigDecimal current, BigDecimal threshold) {
        if (alarmType == AlarmConstants.ALARM_TYPE_HIGH_TEMP) {
            return String.format("温度超高报警: 当前温度%s℃, 超过阈值%s℃", current.toPlainString(), threshold.toPlainString());
        } else if (alarmType == AlarmConstants.ALARM_TYPE_LOW_TEMP) {
            return String.format("温度过低报警: 当前温度%s℃, 低于阈值%s℃", current.toPlainString(), threshold.toPlainString());
        }
        return "温度异常报警";
    }

    private int calculateAlarmLevel(int alarmType, BigDecimal current, BigDecimal threshold) {
        BigDecimal diff = current.subtract(threshold).abs();
        if (diff.compareTo(new BigDecimal("5")) >= 0) {
            return AlarmConstants.ALARM_LEVEL_HIGH;
        } else if (diff.compareTo(new BigDecimal("2")) >= 0) {
            return AlarmConstants.ALARM_LEVEL_MEDIUM;
        }
        return AlarmConstants.ALARM_LEVEL_LOW;
    }
}
