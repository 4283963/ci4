package com.coldchain.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class ColdGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ColdGatewayApplication.class, args);
        System.out.println("==========================================");
        System.out.println("  冷链监控网关 cold-gateway 启动成功!");
        System.out.println("  端口: 8080");
        System.out.println("==========================================");
    }
}
