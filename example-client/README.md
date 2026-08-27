# example-client

示例：gRPC 服务消费方（订单客户端）。基于 `armeria-server` 基础设施：

- 自身也提供 `ProductService`（proto `option (app_id) = "100004336"`），启动后注册到 Nacos；
- 应用身份从 `src/main/resources/META-INF/app.config` 读取（`appid=100004336`），必须与 proto 的 `app_id` 一致，不一致则启动失败；
- 作为 gRPC 消费方，通过 Nacos 注册中心发现并调用 example-server；
- `OrderClientService` 中的 `OrderServiceBlockingStub` 由基础设施按 appId（100004337）
  自动从 Nacos 查询健康实例并注入（`gproto+http://ip:port/100004337/api`）；
- 实例上线/下线时自动更新目标实例列表（客户端侧负载均衡），无需重启；
- 提供 `OrderClientService` Spring Bean 供调用；当前未内置启动演示或 REST 接口。

## 运行

前置条件与 example-server 相同，且 example-server 已启动并注册到 Nacos：

```bash
mvn -pl example-client spring-boot:run
```

本进程的 gRPC 端口为 18083，注册中心服务名为 `100004336`。

## 手动调用

当前没有对外 REST 接口，可在代码中注入 `OrderClientService` 调用，或使用
[armeria-grpc-canary](../armeria-grpc-canary/README.md) 测试控制台直接向服务实例发起调用。
对应服务端日志会显示收到带调用方上下文（机房/分组）的 gRPC 请求。
