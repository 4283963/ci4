package com.coldchain.collector.service;

import com.coldchain.common.constant.VehicleStatusConstants;
import com.coldchain.common.dto.TemperatureDataDTO;
import com.coldchain.common.entity.VehicleRealtimeStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class VehicleStatusService {

    @Value("${coldchain.vehicle.rest-speed-threshold:1.0}")
    private BigDecimal restSpeedThreshold;

    @Value("${coldchain.vehicle.rest-confirm-count:3}")
    private int restConfirmCount;

    @Value("${coldchain.vehicle.status-expire-minutes:30}")
    private int statusExpireMinutes;

    private final ConcurrentHashMap<String, VehicleState> vehicleStateMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("[VehicleStatus] 车辆状态检测服务已初始化 | 休息速度阈值:{} km/h | 确认次数:{}次 | 过期时间:{}分钟",
                restSpeedThreshold, restConfirmCount, statusExpireMinutes);
    }

    public int detectAndGetStatus(TemperatureDataDTO data) {
        if (data == null || data.getDeviceId() == null) {
            return VehicleStatusConstants.VEHICLE_STATUS_UNKNOWN;
        }

        if (data.getVehicleStatus() != null && data.getVehicleStatus() > 0) {
            updateVehicleState(data.getDeviceId(), data, data.getVehicleStatus());
            return data.getVehicleStatus();
        }

        return detectBySpeed(data);
    }

    private int detectBySpeed(TemperatureDataDTO data) {
        String deviceId = data.getDeviceId();
        BigDecimal speed = data.getSpeed();

        if (speed == null) {
            return VehicleStatusConstants.VEHICLE_STATUS_UNKNOWN;
        }

        VehicleState state = vehicleStateMap.computeIfAbsent(deviceId, k -> new VehicleState());

        synchronized (state) {
            boolean isResting = speed.compareTo(restSpeedThreshold) <= 0;

            state.lastReportTime = data.getReportTime() != null ? data.getReportTime() : LocalDateTime.now();
            state.lastSpeed = speed;
            state.lastLongitude = data.getLongitude();
            state.lastLatitude = data.getLatitude();
            state.lastTemperature = data.getTemperature();
            state.lastHumidity = data.getHumidity();
            state.vehicleNo = data.getVehicleNo();

            if (isResting) {
                state.restCount++;
                state.drivingCount = 0;

                if (state.currentStatus != VehicleStatusConstants.VEHICLE_STATUS_RESTING
                        && state.restCount >= restConfirmCount) {
                    state.currentStatus = VehicleStatusConstants.VEHICLE_STATUS_RESTING;
                    state.statusChangeTime = LocalDateTime.now();
                    log.info("[VehicleStatus] 车辆进入休息状态 | deviceId:{} | vehicleNo:{} | 速度:{}km/h | 连续:{}次",
                            deviceId, data.getVehicleNo(), speed, state.restCount);
                }
            } else {
                state.drivingCount++;
                state.restCount = 0;

                if (state.currentStatus != VehicleStatusConstants.VEHICLE_STATUS_DRIVING
                        && state.drivingCount >= 1) {
                    state.currentStatus = VehicleStatusConstants.VEHICLE_STATUS_DRIVING;
                    state.statusChangeTime = LocalDateTime.now();
                    log.info("[VehicleStatus] 车辆进入行驶状态 | deviceId:{} | vehicleNo:{} | 速度:{}km/h",
                            deviceId, data.getVehicleNo(), speed);
                }
            }

            return state.currentStatus;
        }
    }

    private void updateVehicleState(String deviceId, TemperatureDataDTO data, int status) {
        VehicleState state = vehicleStateMap.computeIfAbsent(deviceId, k -> new VehicleState());
        synchronized (state) {
            if (state.currentStatus != status) {
                state.currentStatus = status;
                state.statusChangeTime = LocalDateTime.now();
                log.info("[VehicleStatus] 车辆状态变更 | deviceId:{} | vehicleNo:{} | 状态:{}",
                        deviceId, data.getVehicleNo(), status);
            }
            state.lastReportTime = data.getReportTime() != null ? data.getReportTime() : LocalDateTime.now();
            state.lastSpeed = data.getSpeed();
            state.lastLongitude = data.getLongitude();
            state.lastLatitude = data.getLatitude();
            state.lastTemperature = data.getTemperature();
            state.lastHumidity = data.getHumidity();
            state.vehicleNo = data.getVehicleNo();
        }
    }

    public VehicleRealtimeStatus getVehicleStatus(String deviceId) {
        VehicleState state = vehicleStateMap.get(deviceId);
        if (state == null) {
            return null;
        }
        return buildRealtimeStatus(deviceId, state);
    }

    public List<VehicleRealtimeStatus> getAllVehicleStatus() {
        List<VehicleRealtimeStatus> list = new ArrayList<>(vehicleStateMap.size());
        LocalDateTime now = LocalDateTime.now();
        vehicleStateMap.forEach((deviceId, state) -> {
            if (isExpired(state, now)) {
                return;
            }
            list.add(buildRealtimeStatus(deviceId, state));
        });
        return list;
    }

    public List<VehicleRealtimeStatus> getVehicleStatusByStatus(int status) {
        List<VehicleRealtimeStatus> list = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        vehicleStateMap.forEach((deviceId, state) -> {
            if (isExpired(state, now)) {
                return;
            }
            if (state.currentStatus == status) {
                list.add(buildRealtimeStatus(deviceId, state));
            }
        });
        return list;
    }

    private VehicleRealtimeStatus buildRealtimeStatus(String deviceId, VehicleState state) {
        synchronized (state) {
            return VehicleRealtimeStatus.builder()
                    .deviceId(deviceId)
                    .vehicleNo(state.vehicleNo)
                    .vehicleStatus(state.currentStatus)
                    .speed(state.lastSpeed)
                    .temperature(state.lastTemperature)
                    .humidity(state.lastHumidity)
                    .longitude(state.lastLongitude)
                    .latitude(state.lastLatitude)
                    .lastReportTime(state.lastReportTime)
                    .statusChangeTime(state.statusChangeTime)
                    .iconColor(getIconColor(state.currentStatus))
                    .build();
        }
    }

    private String getIconColor(int status) {
        switch (status) {
            case 1:
                return "green";
            case 2:
                return "blue";
            default:
                return "gray";
        }
    }

    private boolean isExpired(VehicleState state, LocalDateTime now) {
        if (state.lastReportTime == null) {
            return true;
        }
        return java.time.Duration.between(state.lastReportTime, now).toMinutes() > statusExpireMinutes;
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanExpiredStatus() {
        int before = vehicleStateMap.size();
        LocalDateTime now = LocalDateTime.now();
        vehicleStateMap.entrySet().removeIf(entry -> {
            VehicleState state = entry.getValue();
            return isExpired(state, now);
        });
        int after = vehicleStateMap.size();
        if (before != after) {
            log.info("[VehicleStatus] 清理过期车辆状态 | 清理前:{} | 清理后:{} | 移除:{}",
                    before, after, before - after);
        }
    }

    public int getVehicleCount() {
        return vehicleStateMap.size();
    }

    private static class VehicleState {
        volatile int currentStatus = VehicleStatusConstants.VEHICLE_STATUS_UNKNOWN;
        volatile String vehicleNo;
        volatile BigDecimal lastSpeed;
        volatile BigDecimal lastTemperature;
        volatile BigDecimal lastHumidity;
        volatile BigDecimal lastLongitude;
        volatile BigDecimal lastLatitude;
        volatile LocalDateTime lastReportTime;
        volatile LocalDateTime statusChangeTime;
        int restCount = 0;
        int drivingCount = 0;
    }
}
