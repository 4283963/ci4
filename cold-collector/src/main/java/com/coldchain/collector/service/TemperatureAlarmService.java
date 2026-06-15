package com.coldchain.collector.service;

import com.coldchain.common.constant.AlarmConstants;
import com.coldchain.common.constant.VehicleStatusConstants;
import com.coldchain.common.dto.AlarmRequestDTO;
import com.coldchain.common.dto.AlarmResponseDTO;
import com.coldchain.common.dto.TemperatureDataDTO;
import com.coldchain.common.result.Result;
import com.coldchain.common.util.SnowflakeIdGenerator;
import com.coldchain.collector.feign.AlarmFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class TemperatureAlarmService {

    @Value("${coldchain.alarm.retry-times:3}")
    private int retryTimes;

    @Value("${coldchain.alarm.suppress-seconds:300}")
    private int alarmSuppressSeconds;

    @Resource
    private AlarmFeignClient alarmFeignClient;

    @Resource
    private VehicleStatusService vehicleStatusService;

    private final ConcurrentHashMap<String, AlarmSuppression> suppressionMap = new ConcurrentHashMap<>();

    private final AtomicLong totalAlarms = new AtomicLong(0);
    private final AtomicLong suppressedAlarms = new AtomicLong(0);
    private final AtomicInteger restAlarms = new AtomicInteger(0);
    private final AtomicInteger drivingAlarms = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        log.info("[AlarmService] 温度报警服务已初始化 | 报警抑制时间:{}秒 | 重试次数:{}", alarmSuppressSeconds, retryTimes);
    }

    @Async("alarmExecutor")
    public void checkAndTriggerAlarm(TemperatureDataDTO data) {
        if (data == null || data.getTemperature() == null) {
            return;
        }
        try {
            int vehicleStatus = vehicleStatusService.detectAndGetStatus(data);
            data.setVehicleStatus(vehicleStatus);

            BigDecimal temp = data.getTemperature();
            BigDecimal upper = getUpperThreshold(vehicleStatus);
            BigDecimal lower = getLowerThreshold(vehicleStatus);

            int alarmType = 0;
            BigDecimal threshold = BigDecimal.ZERO;

            if (temp.compareTo(upper) > 0) {
                alarmType = AlarmConstants.ALARM_TYPE_HIGH_TEMP;
                threshold = upper;
            } else if (temp.compareTo(lower) < 0) {
                alarmType = AlarmConstants.ALARM_TYPE_LOW_TEMP;
                threshold = lower;
            }

            if (alarmType == 0) {
                return;
            }

            String deviceId = data.getDeviceId();
            String suppressKey = deviceId + "_" + alarmType;

            if (isSuppressed(suppressKey, vehicleStatus)) {
                suppressedAlarms.incrementAndGet();
                log.debug("[AlarmService] 报警被抑制 | deviceId:{} | 类型:{} | 状态:{}",
                        deviceId, alarmType, getStatusName(vehicleStatus));
                return;
            }

            triggerAlarm(data, alarmType, threshold, vehicleStatus);

        } catch (Exception e) {
            log.error("[AlarmCheck] 温度检测异常, deviceId:{}, 原因:{}", data.getDeviceId(), e.getMessage(), e);
        }
    }

    private BigDecimal getUpperThreshold(int vehicleStatus) {
        if (vehicleStatus == VehicleStatusConstants.VEHICLE_STATUS_RESTING) {
            return VehicleStatusConstants.REST_TEMP_UPPER_THRESHOLD;
        }
        return AlarmConstants.TEMP_UPPER_THRESHOLD;
    }

    private BigDecimal getLowerThreshold(int vehicleStatus) {
        if (vehicleStatus == VehicleStatusConstants.VEHICLE_STATUS_RESTING) {
            return VehicleStatusConstants.REST_TEMP_LOWER_THRESHOLD;
        }
        return AlarmConstants.TEMP_LOWER_THRESHOLD;
    }

    private boolean isSuppressed(String key, int vehicleStatus) {
        AlarmSuppression suppression = suppressionMap.get(key);
        if (suppression == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        long secondsSinceLast = java.time.Duration.between(suppression.lastAlarmTime, now).getSeconds();

        if (vehicleStatus == VehicleStatusConstants.VEHICLE_STATUS_RESTING) {
            return secondsSinceLast < alarmSuppressSeconds * 3;
        }
        return secondsSinceLast < alarmSuppressSeconds;
    }

    private void triggerAlarm(TemperatureDataDTO data, int alarmType, BigDecimal threshold, int vehicleStatus) {
        String alarmId = SnowflakeIdGenerator.getInstance().nextIdStr();
        String alarmMsg = buildAlarmMessage(alarmType, data.getTemperature(), threshold, vehicleStatus);
        int alarmLevel = calculateAlarmLevel(alarmType, data.getTemperature(), threshold, vehicleStatus);

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
                .vehicleStatus(vehicleStatus)
                .alarmTime(data.getReportTime() != null ? data.getReportTime() : LocalDateTime.now())
                .build();

        String suppressKey = data.getDeviceId() + "_" + alarmType;
        suppressionMap.put(suppressKey, new AlarmSuppression(LocalDateTime.now(), alarmLevel));

        totalAlarms.incrementAndGet();
        if (vehicleStatus == VehicleStatusConstants.VEHICLE_STATUS_RESTING) {
            restAlarms.incrementAndGet();
        } else if (vehicleStatus == VehicleStatusConstants.VEHICLE_STATUS_DRIVING) {
            drivingAlarms.incrementAndGet();
        }

        log.warn("[AlarmTrigger] 触发温度报警 | alarmId:{} | deviceId:{} | vehicleNo:{} | 类型:{} | 温度:{} | 阈值:{} | 级别:{} | 车辆状态:{}",
                alarmId, data.getDeviceId(), data.getVehicleNo(), alarmType,
                data.getTemperature(), threshold, alarmLevel, getStatusName(vehicleStatus));

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

    private String buildAlarmMessage(int alarmType, BigDecimal current, BigDecimal threshold, int vehicleStatus) {
        String statusLabel = "";
        if (vehicleStatus == VehicleStatusConstants.VEHICLE_STATUS_RESTING) {
            statusLabel = "(车辆休息中, 已放宽阈值)";
        }

        if (alarmType == AlarmConstants.ALARM_TYPE_HIGH_TEMP) {
            return String.format("温度超高报警: 当前温度%s℃, 超过阈值%s℃ %s",
                    current.toPlainString(), threshold.toPlainString(), statusLabel);
        } else if (alarmType == AlarmConstants.ALARM_TYPE_LOW_TEMP) {
            return String.format("温度过低报警: 当前温度%s℃, 低于阈值%s℃ %s",
                    current.toPlainString(), threshold.toPlainString(), statusLabel);
        }
        return "温度异常报警";
    }

    private int calculateAlarmLevel(int alarmType, BigDecimal current, BigDecimal threshold, int vehicleStatus) {
        BigDecimal diff = current.subtract(threshold).abs();
        int level;

        if (diff.compareTo(new BigDecimal("5")) >= 0) {
            level = AlarmConstants.ALARM_LEVEL_HIGH;
        } else if (diff.compareTo(new BigDecimal("2")) >= 0) {
            level = AlarmConstants.ALARM_LEVEL_MEDIUM;
        } else {
            level = AlarmConstants.ALARM_LEVEL_LOW;
        }

        if (vehicleStatus == VehicleStatusConstants.VEHICLE_STATUS_RESTING && level > AlarmConstants.ALARM_LEVEL_LOW) {
            level = level - 1;
            log.debug("[AlarmService] 休息状态下调报警级别: {} -> {}", level + 1, level);
        }

        return level;
    }

    private String getStatusName(int status) {
        switch (status) {
            case 1:
                return "行驶中";
            case 2:
                return "休息中";
            default:
                return "未知";
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanExpiredSuppression() {
        int before = suppressionMap.size();
        LocalDateTime expireTime = LocalDateTime.now().minusSeconds(alarmSuppressSeconds * 10L);
        suppressionMap.entrySet().removeIf(entry ->
                entry.getValue().lastAlarmTime.isBefore(expireTime)
        );
        int after = suppressionMap.size();
        if (before != after) {
            log.info("[AlarmService] 清理过期报警抑制 | 清理前:{} | 清理后:{}", before, after);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void alarmStats() {
        log.info("[AlarmService] 报警统计 | 总报警:{} | 被抑制:{} | 行驶中报警:{} | 休息中报警:{}",
                totalAlarms.get(), suppressedAlarms.get(),
                drivingAlarms.get(), restAlarms.get());
    }

    public long getTotalAlarms() {
        return totalAlarms.get();
    }

    public long getSuppressedAlarms() {
        return suppressedAlarms.get();
    }

    private static class AlarmSuppression {
        LocalDateTime lastAlarmTime;
        int lastLevel;

        AlarmSuppression(LocalDateTime time, int level) {
            this.lastAlarmTime = time;
            this.lastLevel = level;
        }
    }
}
