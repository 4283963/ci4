package com.coldchain.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class RequestLogFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String method = request.getMethodValue();
        String remoteAddr = request.getRemoteAddress() != null ?
                request.getRemoteAddress().getAddress().getHostAddress() : "unknown";

        long startTime = System.currentTimeMillis();
        log.info("[Gateway] 请求开始 | IP:{} | Method:{} | Path:{}", remoteAddr, method, path);

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            HttpStatus status = exchange.getResponse().getStatusCode();
            long cost = System.currentTimeMillis() - startTime;
            log.info("[Gateway] 请求结束 | IP:{} | Method:{} | Path:{} | Status:{} | 耗时:{}ms",
                    remoteAddr, method, path, status, cost);
        }));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
