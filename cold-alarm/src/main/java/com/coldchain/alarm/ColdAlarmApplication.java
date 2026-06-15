package com.coldchain.alarm;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@EnableDiscoveryClient
@MapperScan("com.coldchain.alarm.mapper")
@SpringBootApplication
public class ColdAlarmApplication {

    public static void main(String[] args) {
        SpringApplication.run(ColdAlarmApplication.class, args);
        System.out.println("==========================================");
        System.out.println("  冷链异常报警服务 cold-alarm 启动成功!");
        System.out.println("  端口: 8082");
        System.out.println("==========================================");
    }
}
