package com.coldchain.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@EnableFeignClients
@EnableDiscoveryClient
@SpringBootApplication
public class ColdCollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ColdCollectorApplication.class, args);
        System.out.println("==========================================");
        System.out.println("  冷链数据接收服务 cold-collector 启动成功!");
        System.out.println("  端口: 8081");
        System.out.println("==========================================");
    }
}
