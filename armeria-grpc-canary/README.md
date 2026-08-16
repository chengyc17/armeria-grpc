# gRPC 接口测试控制台（armeria-grpc-canary）

独立于 `armeria-server` 的 Spring Boot Web 模块，提供 gRPC 接口的在线测试页面：

1. 输入 appid（即 proto 中 `option (app_id)` 的值，也是 Nacos 服务名）；
2. 后端从 Nacos 读取该 appid 的全部健康实例，展示在下拉框中；
3. 选中实例后，从该实例拉取接口列表（方法名）到第二个下拉框；
4. 选中接口后自动填充该接口的请求 JSON 模板；
5. 修改请求 JSON 后点击发送，响应以 JSON 形式展示在右侧。

## 工作原理

完全复用 `armeria-server` 已提供的能力，不需要本模块持有任何 protobuf stub：

- **实例发现**：`NamingService.getAllInstances(appId, group, true)`；
- **接口枚举与请求模板**：`GET http://{host}:{port}/rpc/schema`，返回
  `{"appId/api/方法名": 请求模板}`，按 appId 前缀过滤即得接口列表；
- **调用**：`POST http://{host}:{port}/{appId}/api/{方法名}`，
  `Content-Type: application/json`（Armeria 的 unframed HTTP/JSON 通道）。

## 配置（application.yml）

```yaml
server:
  port: 9081

test-console:
  nacos:
    server-addr: 127.0.0.1:8848
    namespace: "${env}"
    group: DEFAULT_GROUP
```

与 `test`/`test2` 模块一致。环境值不写死，从本机 ops 目录的 `env.properties` 读取
（Windows：`C:\ops\env.properties`；Linux：`~/ops/env.properties`），`${env}` 即其中的 `env` 值。

## 运行

```bash
mvn spring-boot:run -pl armeria-grpc-canary
```

或先打包再运行 fat jar：

```bash
mvn package -pl armeria-grpc-canary
java -jar armeria-grpc-canary/target/armeria-grpc-canary-1.0-SNAPSHOT.jar
```

访问 <http://localhost:9081/>。

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/test/instances?appId=xxx` | 按 appid 查询 Nacos 健康实例 |
| GET | `/api/test/methods?appId=xxx&host=...&port=...` | 拉取选中实例的接口列表与请求模板 |
| POST | `/api/test/invoke` | 发起调用，body 为 `{appId, host, port, method, request}` |
