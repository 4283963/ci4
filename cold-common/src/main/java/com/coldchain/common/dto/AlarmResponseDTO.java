package com.coldchain.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlarmResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Boolean success;

    private String message;

    private String alarmRecordId;

    private LocalDateTime processTime;
}
