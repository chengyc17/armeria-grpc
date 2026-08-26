# example-client

示例：gRPC 服务消费方（订单客户端）。基于 `armeria-server` 基础设施：

- 自身也提供 `ProductService`（proto `option (app_id) = "100004336"`），启动后注册到 Nacos；
- 作为 gRPC 消费方，通过 Nacos 注册中心发现并调用 example-server；
- `OrderClientService` 中的 `OrderServiceBlockingStub` 由基础设施按 appId（100004337）
  自动从 Nacos 查询健康实例并注入（`gproto+http://ip:port/100004337/api`）；
- 实例上线/下线时自动重建 stub 并刷新注入字段，无需重启；
- 启动时自动演示一次“客户端调用服务端”，并提供 REST 接口方便手动调用。

## 运行

前置条件与 example-server 相同，且 example-server 已启动并注册到 Nacos：

```bash
mvn -pl example-client spring-boot:run
```

启动成功后日志会输出：

```text
[demo] 通过 Nacos 调用 createOrder 成功: orderId=1, success=true
[demo] 通过 Nacos 调用 getOrder 成功: ...
```

本进程的 gRPC 端口为 18083，注册中心服务名为 `100004336`。

## 手动调用

```bash
# 创建订单（客户端 -> 订单服务端，经 Nacos 发现）
curl -X POST http://127.0.0.1:18082/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": 10001, "productIds": [1, 2, 3]}'

# 查询订单
curl http://127.0.0.1:18082/api/orders/1
```

对应服务端日志会显示收到带调用方上下文（机房/分组）的 gRPC 请求。
