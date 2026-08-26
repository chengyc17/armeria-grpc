package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * gRPC 接口测试控制台：独立 Spring Boot Web 应用。
 * 从 Nacos 拉取全部命名空间供页面选择，读取指定命名空间下 appid 的实例，
 * 通过实例自带的 /rpc/schema 端点枚举接口，并以 unframed HTTP/JSON 方式向选中实例发起调用。
 */
@SpringBootApplication
public class TestConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestConsoleApplication.class, args);
    }
}
