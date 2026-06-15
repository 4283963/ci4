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
public class TemperatureRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String deviceId;

    private String vehicleNo;

    private BigDecimal temperature;

    private BigDecimal humidity;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private BigDecimal speed;

    private Integer vehicleStatus;

    private Integer status;

    private LocalDateTime reportTime;

    private LocalDateTime createTime;
}
