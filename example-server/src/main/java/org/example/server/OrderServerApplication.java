package org.example.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * example-server 启动类。
 *
 * <p>依赖 armeria-server 的自动配置：启动内嵌 Armeria gRPC 服务端（rpc.port），
 * 从 {@code @GrpcService} 实现读取 proto 中的 app_id（100004337），
 * 并把本实例注册到 Nacos 注册中心。
 */
@SpringBootApplication
public class OrderServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServerApplication.class, args);
    }
}
