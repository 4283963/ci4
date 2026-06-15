package com.coldchain.alarm.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.coldchain.alarm.mapper.AlarmRecordMapper;
import com.coldchain.common.constant.AlarmConstants;
import com.coldchain.common.constant.VehicleStatusConstants;
import com.coldchain.common.dto.AlarmRequestDTO;
import com.coldchain.common.dto.AlarmResponseDTO;
import com.coldchain.common.entity.AlarmRecord;
import com.coldchain.common.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AlarmService {

    @Value("${coldchain.alarm.rest-notify-enabled:false}")
    private boolean restNotifyEnabled;

    @Resource
    private AlarmRecordMapper alarmRecordMapper;

    @Resource
    private NotificationService notificationService;

    private final AtomicInteger totalRestAlarms = new AtomicInteger(0);
    private final AtomicInteger totalDrivingAlarms = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        log.info("[AlarmService] 报警服务初始化 | 休息状态报警通知:{}", restNotifyEnabled ? "开启" : "关闭");
    }

    public AlarmResponseDTO processAlarm(AlarmRequestDTO request) {
        long start = System.currentTimeMillis();
        String alarmId = request.getAlarmId();
        AlarmResponseDTO response = new AlarmResponseDTO();
        Integer vehicleStatus = request.getVehicleStatus();

        try {
            if (isDuplicateAlarm(alarmId, request.getDeviceId(), request.getAlarmTime())) {
                log.warn("[Alarm] 重复报警, 已跳过 | alarmId:{} | deviceId:{}", alarmId, request.getDeviceId());
                response.setSuccess(true);
                response.setMessage("重复报警已忽略");
                response.setAlarmRecordId(alarmId);
                response.setProcessTime(LocalDateTime.now());
                return response;
            }

            int alarmLevel = calculateLevel(request.getAlarmType(), request.getCurrentValue(), request.getThreshold(), vehicleStatus);
            AlarmRecord record = buildRecord(request, alarmLevel);
            alarmRecordMapper.insert(record);

            if (vehicleStatus != null && vehicleStatus == VehicleStatusConstants.VEHICLE_STATUS_RESTING) {
                totalRestAlarms.incrementAndGet();
                if (restNotifyEnabled && alarmLevel >= AlarmConstants.ALARM_LEVEL_MEDIUM) {
                    notificationService.notifyManagers(alarmId, request.getAlarmMessage(), request.getVehicleNo(), alarmLevel);
                    log.info("[Alarm] 休息状态报警已通知 | alarmId:{} | 级别:{}", alarmId, alarmLevel);
                } else {
                    log.info("[Alarm] 休息状态报警已降权, 仅记录不通知 | alarmId:{} | 级别:{} | 车辆:{}",
                            alarmId, alarmLevel, request.getVehicleNo());
                }
            } else {
                totalDrivingAlarms.incrementAndGet();
                notificationService.notifyManagers(alarmId, request.getAlarmMessage(), request.getVehicleNo(), alarmLevel);
            }

            long cost = System.currentTimeMillis() - start;
            log.info("[Alarm] 报警处理完成 | alarmId:{} | 类型:{} | 级别:{} | 设备:{} | 车辆:{} | 车辆状态:{} | 当前值:{} | 阈值:{} | 耗时:{}ms",
                    alarmId, request.getAlarmType(), alarmLevel, request.getDeviceId(),
                    request.getVehicleNo(), getStatusName(vehicleStatus),
                    request.getCurrentValue(), request.getThreshold(), cost);

            response.setSuccess(true);
            response.setMessage("报警处理成功");
            response.setAlarmRecordId(String.valueOf(record.getId()));
            response.setProcessTime(LocalDateTime.now());

        } catch (Exception e) {
            log.error("[Alarm] 报警处理异常 | alarmId:{} | 原因:{}", alarmId, e.getMessage(), e);
            response.setSuccess(false);
            response.setMessage("报警处理失败: " + e.getMessage());
            response.setProcessTime(LocalDateTime.now());
        }

        return response;
    }

    private boolean isDuplicateAlarm(String alarmId, String deviceId, LocalDateTime alarmTime) {
        try {
            QueryWrapper<AlarmRecord> wrapper = new QueryWrapper<>();
            wrapper.eq("alarm_id", alarmId);
            wrapper.or(w -> w.eq("device_id", deviceId).eq("alarm_time", alarmTime));
            wrapper.last("LIMIT 1");
            return alarmRecordMapper.selectCount(wrapper) > 0;
        } catch (Exception e) {
            log.warn("[Alarm] 重复报警检查失败, 继续处理 | alarmId:{}", alarmId);
            return false;
        }
    }

    private int calculateLevel(Integer alarmType, BigDecimal current, BigDecimal threshold, Integer vehicleStatus) {
        if (current == null || threshold == null) {
            return AlarmConstants.ALARM_LEVEL_LOW;
        }
        BigDecimal diff = current.subtract(threshold).abs();
        int level;

        if (diff.compareTo(new BigDecimal("5")) >= 0) {
            level = AlarmConstants.ALARM_LEVEL_HIGH;
        } else if (diff.compareTo(new BigDecimal("2")) >= 0) {
            level = AlarmConstants.ALARM_LEVEL_MEDIUM;
        } else {
            level = AlarmConstants.ALARM_LEVEL_LOW;
        }

        if (vehicleStatus != null && vehicleStatus == VehicleStatusConstants.VEHICLE_STATUS_RESTING
                && level > AlarmConstants.ALARM_LEVEL_LOW) {
            level = level - 1;
            log.debug("[Alarm] 休息状态下调报警级别");
        }

        return level;
    }

    private AlarmRecord buildRecord(AlarmRequestDTO req, int level) {
        LocalDateTime now = LocalDateTime.now();
        return AlarmRecord.builder()
                .id(SnowflakeIdGenerator.getInstance().nextId())
                .alarmId(req.getAlarmId())
                .deviceId(req.getDeviceId())
                .vehicleNo(req.getVehicleNo())
                .alarmType(req.getAlarmType())
                .alarmMessage(req.getAlarmMessage())
                .currentValue(req.getCurrentValue())
                .threshold(req.getThreshold())
                .longitude(req.getLongitude())
                .latitude(req.getLatitude())
                .vehicleStatus(req.getVehicleStatus())
                .alarmLevel(level)
                .processStatus(AlarmConstants.PROCESS_STATUS_PENDING)
                .alarmTime(req.getAlarmTime() != null ? req.getAlarmTime() : now)
                .processTime(now)
                .createTime(now)
                .build();
    }

    private String getStatusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case 1:
                return "行驶中";
            case 2:
                return "休息中";
            default:
                return "未知";
        }
    }

    public int getTotalRestAlarms() {
        return totalRestAlarms.get();
    }

    public int getTotalDrivingAlarms() {
        return totalDrivingAlarms.get();
    }
}
