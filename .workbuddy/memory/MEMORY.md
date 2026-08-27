# armeria-grpc 项目长期记忆（curated）

## 模块定位
- **armeria-grpc-common**：`@GrpcService` 注解、应用身份（app.config）、proto app_id 解析、单服务约束。
- **armeria-grpc-config**：Nacos 配置中心客户端（data-ids 数组拉取+订阅，默认 soa.json，AUTO 格式识别，变更发 AppConfigChangedEvent）。
- **armeria-server**：核心 starter。Armeria 服务端 + Nacos 注册 + stub 自动注入 + 客户端上下文透传 + 治理（黑白名单→限流→熔断）+ /rpc/schema。
- **gateway / canary / api**：HTTP 网关(9082) / 测试控制台(9081) / proto 制品发布(9080)。
- **example-server / example-client**：订单服务提供方(100004337, 18081) / 消费方兼商品服务(100004336, 18083)。

## 关键约定（重构后 8-27 状态）
- **appId 权威来源 = META-INF/app.config**（key `appid` 或 `app.id`），缺失/冲突启动失败；proto app_id 存在必须一致（AppIdResolver）。所有应用（含纯客户端）都必须带该文件。
- **服务端/治理激活**：`@ConditionalOnBean(BindableService.class)` 驱动 armeriaServer、GovernanceConfigManager、GovernanceServerInterceptor；无 @GrpcService 则无服务端无治理。
  ⚠️ 不要用 `@ConditionalOnBean(annotation=GrpcService.class)`——Spring Boot 3.2 在 @Bean 方法上只给 annotation 时会从方法返回类型推导出必匹配的 type 条件（自引用），条件永远不成立，表现为"启动即退出（exit 0）"。
- **治理只认 Nacos soa.json**：无本地兜底类；未配置=不限流/不熔断/名单空全放行；黑白名单维度=调用方 appId（x-client-app-id 头），大小写不敏感。
- **config 模块激活条件**：`@ConditionalOnProperty(prefix="rpc.config", name="server-addr")`。注意 @ConditionalOnProperty 必须带 name/value，只写 prefix 会启动报错。
- **拦截器顺序**：grpc-java `ServerInterceptors` 列表最后一个最先执行 → 治理拦截器先于 ClientContext 拦截器，治理必须从 header 读 appId（不能读 ClientContextHolder）。
- **客户端 stub 注入**：GrpcClientBeanPostProcessor 按 AbstractStub 字段类型注入（无注解），目标 appId 从 stub 内层类 getServiceDescriptor 反推；无健康实例时 3s/5s 重试。
- **每进程单 @GrpcService**（GrpcServices.requireSingle 强制）。

## 本机验证工具链（无 mvn CLI）
- javac/java 手动组装 classpath，`-cp` 必须用 Windows 风格 `D:/` 路径（MSYS `/d/` 会被破坏）。
- 本地仓库：`D:/dev/maven/repo`（含 com.abc.order/order-server:1.0.0、com.abc.product/product-server:1.0.1）。
- 运行时易漏 jar：armeria-grpc-protocol（gproto 格式 SPI）、protobuf-jackson、proto-google-common-protos、protobuf-java-util、byte-buddy、micrometer-commons/observation、netty-transport-native-unix-common。
- **坑**：target 下临时目录会被 safe-delete 钩子异步删除（trash 失败仍可能删），验证用一次性新目录名（nv2/nv3/nv4...）。
- 端到端测试（GovernanceServerInterceptorTest）已移除 main() 入口，改为纯 JUnit。

## 环境
- 本机 Nacos 3.2.3（console 独立端口 8080，identity a1b2c3d4e5f67890123456789abcdef0），ops 目录 C:/ops/env.properties（env=fat）。
- Java 17（D:/dev/jdk-17.0.1）；Spring Boot 3.2.0 / Armeria 1.27.0 / grpc 1.59.0 / Nacos 3.2.3。
