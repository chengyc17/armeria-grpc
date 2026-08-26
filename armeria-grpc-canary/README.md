# gRPC 接口测试控制台（armeria-grpc-canary）

独立于 `armeria-server` 的 Spring Boot Web 模块，提供 gRPC 接口的在线测试页面：

1. 页面加载时从 Nacos 拉取全部命名空间，顶部并排展示为可点击的胶囊按钮；
2. 默认选中 `test-console.nacos.namespace` 配置项，为空时取本机 ops 目录
   `env.properties` 中的 `env` 值，仍无则选 `public`；
3. 输入 appid（即 proto 中 `option (app_id)` 的值，也是 Nacos 服务名），
   查询所选命名空间下该 appid 的全部实例，切换命名空间时自动刷新；
4. 选中实例后，从该实例拉取接口列表（方法名）到第二个下拉框；
5. 选中接口后自动填充该接口的请求 JSON 模板；
6. 修改请求 JSON 后点击发送，响应以 JSON 形式展示在右侧。

## 工作原理

完全复用 `armeria-server` 已提供的能力，不需要本模块持有任何 protobuf stub：

- **命名空间列表**：Nacos 客户端 SDK 未提供该能力，直接调用 Nacos 服务端 HTTP
  Open API，依次尝试 v3 admin（`/nacos/v3/admin/core/namespace/list`）→
  v1 console → v2 console，返回字段名做跨版本兼容解析；
- **实例发现**：按命名空间懒创建并缓存 `NamingService`（`NamingServiceManager`），
  `getAllInstances(appId, group, true)`；
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
    group: DEFAULT_GROUP
    # 页面初始选中的命名空间；为空时取本机 ops 目录 env.properties 的 env 值
    # namespace: dev
    # Nacos 开启鉴权时填写用户名密码
    # username: nacos
    # password: nacos
    # Nacos 3.x 的 admin 接口即使 auth.enabled=false 也强制鉴权
    # （nacos.core.auth.admin.enabled 默认 true），需携带服务端 identity 头，
    # 取值与 Nacos 服务端 nacos.core.auth.server.identity.key/value 一致
    identity-key: server.identity
    identity-value: a1b2c3d4e5f67890123456789abcdef0
```

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
| GET | `/api/test/namespaces` | 列出 Nacos 全部命名空间及页面默认选中项 |
| GET | `/api/test/instances?appId=xxx&namespace=yyy` | 按命名空间查询 appid 的 Nacos 健康实例，namespace 缺省时用默认命名空间 |
| GET | `/api/test/methods?appId=xxx&host=...&port=...` | 拉取选中实例的接口列表与请求模板 |
| POST | `/api/test/invoke` | 发起调用，body 为 `{appId, host, port, method, request}` |
