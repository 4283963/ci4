package com.coldchain.common.constant;

import java.math.BigDecimal;

public interface AlarmConstants {

    BigDecimal TEMP_UPPER_THRESHOLD = new BigDecimal("8.0");

    BigDecimal TEMP_LOWER_THRESHOLD = new BigDecimal("-18.0");

    int ALARM_TYPE_HIGH_TEMP = 1;

    int ALARM_TYPE_LOW_TEMP = 2;

    int ALARM_LEVEL_LOW = 1;

    int ALARM_LEVEL_MEDIUM = 2;

    int ALARM_LEVEL_HIGH = 3;

    int PROCESS_STATUS_PENDING = 0;

    int PROCESS_STATUS_PROCESSED = 1;
}
