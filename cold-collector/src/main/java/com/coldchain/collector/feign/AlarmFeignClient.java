package com.coldchain.collector.feign;

import com.coldchain.common.dto.AlarmRequestDTO;
import com.coldchain.common.dto.AlarmResponseDTO;
import com.coldchain.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "cold-alarm", fallbackFactory = AlarmFeignFallbackFactory.class)
public interface AlarmFeignClient {

    @PostMapping("/alarm/receive")
    Result<AlarmResponseDTO> receiveAlarm(@RequestBody AlarmRequestDTO request);
}
