package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * armeria-grpc-gateway 网关入口。
 *
 * <p>路由规则：POST /{appId}/api/{method}
 * <ul>
 *   <li>从 URL 提取 appId，到 Nacos 查询服务名为 appId 的全部健康实例；</li>
 *   <li>实例列表本地缓存 + 订阅 Nacos 变更，避免每个请求都查询 Nacos；</li>
 *   <li>按负载均衡策略选中一个实例，把请求原样转发到该实例的 /{appId}/api/{method}。</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
