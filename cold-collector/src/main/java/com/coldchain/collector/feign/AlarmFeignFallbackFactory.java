package com.coldchain.collector.feign;

import com.coldchain.common.dto.AlarmRequestDTO;
import com.coldchain.common.dto.AlarmResponseDTO;
import com.coldchain.common.result.Result;
import feign.hystrix.FallbackFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class AlarmFeignFallbackFactory implements FallbackFactory<AlarmFeignClient> {

    @Override
    public AlarmFeignClient create(Throwable cause) {
        return new AlarmFeignClient() {
            @Override
            public Result<AlarmResponseDTO> receiveAlarm(AlarmRequestDTO request) {
                log.error("[AlarmFeign] 调用报警服务失败, alarmId:{}, deviceId:{}, 原因:{}",
                        request.getAlarmId(), request.getDeviceId(), cause.getMessage(), cause);
                return Result.fail("报警服务暂不可用");
            }
        };
    }
}
