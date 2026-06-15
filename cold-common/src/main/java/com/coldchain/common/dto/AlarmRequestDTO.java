package com.coldchain.common.dto;

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
public class AlarmRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String alarmId;

    private String deviceId;

    private String vehicleNo;

    private Integer alarmType;

    private String alarmMessage;

    private BigDecimal currentValue;

    private BigDecimal threshold;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private Integer vehicleStatus;

    private LocalDateTime alarmTime;
}
