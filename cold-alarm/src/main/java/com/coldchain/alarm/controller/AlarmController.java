package com.coldchain.alarm.controller;

import com.coldchain.alarm.service.AlarmService;
import com.coldchain.common.dto.AlarmRequestDTO;
import com.coldchain.common.dto.AlarmResponseDTO;
import com.coldchain.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/alarm")
public class AlarmController {

    @Resource
    private AlarmService alarmService;

    private final AtomicLong totalReceived = new AtomicLong(0);
    private final AtomicLong totalSuccess = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> deviceCountMap = new ConcurrentHashMap<>();

    @PostMapping("/receive")
    public Result<AlarmResponseDTO> receiveAlarm(@RequestBody @Validated AlarmRequestDTO request) {
        long total = totalReceived.incrementAndGet();
        String deviceId = request.getDeviceId();
        deviceCountMap.computeIfAbsent(deviceId, k -> new AtomicLong(0)).incrementAndGet();

        log.debug("[AlarmAPI] 收到报警请求 | 序号:{} | alarmId:{} | deviceId:{} | vehicleNo:{} | type:{} | 当前:{} | 阈值:{}",
                total, request.getAlarmId(), deviceId, request.getVehicleNo(),
                request.getAlarmType(), request.getCurrentValue(), request.getThreshold());

        AlarmResponseDTO response = alarmService.processAlarm(request);
        if (response.getSuccess() != null && response.getSuccess()) {
            totalSuccess.incrementAndGet();
        } else {
            totalFailed.incrementAndGet();
        }

        return Result.success(response);
    }

    @GetMapping("/stats")
    public Result<Object> getStats() {
        return Result.success(java.util.Map.of(
                "totalReceived", totalReceived.get(),
                "totalSuccess", totalSuccess.get(),
                "totalFailed", totalFailed.get(),
                "uniqueDevices", deviceCountMap.size(),
                "queryTime", LocalDateTime.now().toString()
        ));
    }

    @GetMapping("/health")
    public Result<String> health() {
        return Result.success("cold-alarm is running");
    }
}
