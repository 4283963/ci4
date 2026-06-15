package com.coldchain.common.constant;

import java.math.BigDecimal;

public interface VehicleStatusConstants {

    int VEHICLE_STATUS_DRIVING = 1;

    int VEHICLE_STATUS_RESTING = 2;

    int VEHICLE_STATUS_UNKNOWN = 0;

    BigDecimal REST_SPEED_THRESHOLD = new BigDecimal("1.0");

    int REST_CONFIRM_COUNT = 3;

    int REST_CONFIRM_SECONDS = 300;

    BigDecimal REST_TEMP_UPPER_THRESHOLD = new BigDecimal("12.0");

    BigDecimal REST_TEMP_LOWER_THRESHOLD = new BigDecimal("-22.0");
}
