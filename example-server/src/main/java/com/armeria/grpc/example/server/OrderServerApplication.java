package com.armeria.grpc.example.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * example-server 启动类。
 *
 * <p>依赖 armeria-server 的自动配置：检测到 {@code @GrpcService} 实现
 * （{@link OrderServiceImpl}）后自动启动内嵌 Armeria gRPC 服务端（rpc.port），
 * 以应用身份 {@code appid=100004337}（来自 {@code META-INF/app.config}，
 * 必须与本进程 proto 的 {@code app_id} 一致）把本实例注册到 Nacos。
 */
@SpringBootApplication
public class OrderServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServerApplication.class, args);
    }
}
