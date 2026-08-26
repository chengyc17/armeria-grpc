# example-server

示例：gRPC 服务提供方（订单服务）。基于 `armeria-server` 基础设施：

- 内嵌 Armeria gRPC 服务端，监听 `rpc.port`（默认 18081）；
- 从 `@GrpcService` 实现对应的 proto `option (app_id) = "100004337"` 读取服务标识；
- 启动时将 `ip:port` 注册到 Nacos 注册中心，退出时自动注销；
- 通过 `rpc.config` 使用经典 Nacos 数组配置方式（`data-ids`）拉取全部配置的配置文件并订阅变更；
  group 不配置时默认取当前服务 appId（`100004337`）；
- 默认拉取 `soa.json`（限流/熔断/黑白名单），未配置或拉取不到时不影响启动（治理使用本地兜底配置）；
- 可选治理配置样例见 `src/main/resources/soa.json`，可发布到 Nacos（namespace=`fat`，group=`100004337`，dataId=`soa.json`）。

## 运行

前置条件：

1. 已构建并安装本项目（`mvn install`，或至少安装 `armeria-grpc-common/armeria-grpc-config/armeria-server`）；
2. Nacos 运行在 `127.0.0.1:8848`；
3. 本机 `ops` 目录存在 `env.properties` 且包含 `env=fat`（Windows: `C:\ops\env.properties`）。

```bash
mvn -pl example-server spring-boot:run
```

启动成功后日志会输出：

```text
Armeria server started on port 18081 with appId=100004337
Registered service 100004337 -> <ip>:18081 to Nacos
```

## 验证

- gRPC 服务：`gproto+http://127.0.0.1:18081/100004337/api`；
- HTTP/JSON 接口（Armeria 启用 unframed 请求）：`POST http://127.0.0.1:18081/100004337/api/orderservice.OrderService/CreateOrder`；
- 请求模板：`GET http://127.0.0.1:18081/rpc/schema`；
- 在 Nacos 控制台的服务列表（namespace `fat`）中可看到服务 `100004337` 下的实例。

然后启动 [example-client](../example-client/README.md)，由客户端通过注册中心发现并调用本服务。
