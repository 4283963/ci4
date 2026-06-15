package com.coldchain.collector.controller;

import com.coldchain.common.constant.VehicleStatusConstants;
import com.coldchain.common.entity.VehicleRealtimeStatus;
import com.coldchain.common.result.Result;
import com.coldchain.collector.service.VehicleStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/vehicle")
public class VehicleStatusController {

    @Resource
    private VehicleStatusService vehicleStatusService;

    @GetMapping("/list")
    public Result<List<VehicleRealtimeStatus>> getVehicleList(
            @RequestParam(required = false) Integer status) {
        List<VehicleRealtimeStatus> list;
        if (status != null && status > 0) {
            list = vehicleStatusService.getVehicleStatusByStatus(status);
        } else {
            list = vehicleStatusService.getAllVehicleStatus();
        }
        return Result.success(list);
    }

    @GetMapping("/{deviceId}")
    public Result<VehicleRealtimeStatus> getVehicleStatus(@PathVariable String deviceId) {
        VehicleRealtimeStatus status = vehicleStatusService.getVehicleStatus(deviceId);
        if (status == null) {
            return Result.fail("车辆不存在或已离线");
        }
        return Result.success(status);
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> getVehicleStats() {
        List<VehicleRealtimeStatus> all = vehicleStatusService.getAllVehicleStatus();
        int total = all.size();
        int driving = 0;
        int resting = 0;
        int unknown = 0;

        for (VehicleRealtimeStatus v : all) {
            if (v.getVehicleStatus() == null) {
                unknown++;
            } else if (v.getVehicleStatus() == VehicleStatusConstants.VEHICLE_STATUS_DRIVING) {
                driving++;
            } else if (v.getVehicleStatus() == VehicleStatusConstants.VEHICLE_STATUS_RESTING) {
                resting++;
            } else {
                unknown++;
            }
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("driving", driving);
        stats.put("resting", resting);
        stats.put("unknown", unknown);
        stats.put("drivingIconColor", "green");
        stats.put("restingIconColor", "blue");
        stats.put("unknownIconColor", "gray");

        return Result.success(stats);
    }

    @GetMapping("/map/markers")
    public Result<Map<String, Object>> getMapMarkers() {
        List<VehicleRealtimeStatus> all = vehicleStatusService.getAllVehicleStatus();
        int driving = 0;
        int resting = 0;

        for (VehicleRealtimeStatus v : all) {
            if (v.getVehicleStatus() != null && v.getVehicleStatus() == VehicleStatusConstants.VEHICLE_STATUS_DRIVING) {
                driving++;
            } else if (v.getVehicleStatus() != null && v.getVehicleStatus() == VehicleStatusConstants.VEHICLE_STATUS_RESTING) {
                resting++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("markers", all);
        result.put("total", all.size());
        result.put("drivingCount", driving);
        result.put("restingCount", resting);
        result.put("legend", java.util.List.of(
                Map.of("status", "driving", "name", "行驶中", "color", "green"),
                Map.of("status", "resting", "name", "休息中", "color", "blue"),
                Map.of("status", "unknown", "name", "未知", "color", "gray")
        ));

        return Result.success(result);
    }
}
