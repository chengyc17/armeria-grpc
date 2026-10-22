package com.armeria.grpc.gateway.config;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.armeria.grpc.gateway.service.GatewayService;
import com.armeria.grpc.gateway.service.GatewayStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 装配并启动 Armeria 网关 Server。
 *
 * <p>与 armeria-server 模块一致：Spring Boot 只负责 Bean 容器与配置绑定，
 * HTTP 服务由原生 Armeria {@link Server} 提供（非阻塞，HTTP/1.1 + HTTP/2）。</p>
 */
@Configuration
public class GatewayServerConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayServerConfig.class);

    @Bean(destroyMethod = "stop")
    public Server armeriaServer(GatewayProperties properties,
                                GatewayService gatewayService,
                                GatewayStatusService statusService) throws Exception {
        Server server = Server.builder()
                .http(properties.getPort())
                // 高并发网关默认不输出访问日志，避免每请求一条 INFO 日志的开销
                .accessLogWriter(AccessLogWriter.disabled(), false)
                .maxRequestLength(properties.getProxy().getMaxRequestLength())
                .gracefulShutdownTimeout(Duration.ofSeconds(10), Duration.ofSeconds(30))
                .service("/{appId}/api/{method}", gatewayService)
                .service("/gateway/services/{appId}/instances", (ctx, req) -> statusService.instances(ctx, req))
                .service("/gateway/status", (ctx, req) -> statusService.status(ctx, req))
                .build();
        server.start().join();
        log.info("Armeria gateway started on port {}", properties.getPort());
        return server;
    }
}