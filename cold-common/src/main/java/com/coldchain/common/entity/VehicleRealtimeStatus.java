package com.coldchain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleRealtimeStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceId;

    private String vehicleNo;

    private Integer vehicleStatus;

    private String iconColor;

    private BigDecimal speed;

    private BigDecimal temperature;

    private BigDecimal humidity;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private LocalDateTime lastReportTime;

    private LocalDateTime statusChangeTime;

    public String getVehicleStatusDesc() {
        if (vehicleStatus == null) {
            return "未知";
        }
        switch (vehicleStatus) {
            case 1:
                return "行驶中";
            case 2:
                return "休息中";
            default:
                return "未知";
        }
    }
}
