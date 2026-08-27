package org.example.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * example-client 启动类（同时提供 ProductService 并作为 gRPC 消费方）。
 *
 * <p>本进程通过 {@link ProductImpl} 提供商品服务（{@code @GrpcService}），
 * armeria-server 自动配置检测到后以应用身份 {@code appid=100004336}
 * （来自 {@code META-INF/app.config}，与 ProductService proto 的 {@code app_id} 一致）
 * 启动内嵌 gRPC 服务端并注册到 Nacos；
 * 该 appId 同时作为调用方标识，随每次 gRPC 调用的 {@code x-client-app-id}
 * 请求头传递给服务端，供服务治理的黑白名单匹配使用。
 *
 * <p>作为消费方，{@link OrderClientService} 中的 {@code OrderServiceBlockingStub}
 * 字段会由 armeria-server 的 {@code GrpcClientBeanPostProcessor} 根据目标服务的
 * proto app_id（100004337）自动从 Nacos 查询实例并注入。
 */
@SpringBootApplication
public class OrderClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderClientApplication.class, args);
    }
}
