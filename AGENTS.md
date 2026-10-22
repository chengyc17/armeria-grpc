# Repository Guidelines

This is a Java 17 multi-module Maven project: a Spring Boot web app that builds and deploys gRPC stubs from uploaded `.proto` files, plus a reusable Armeria/gRPC infrastructure library.

## Project Structure & Module Organization

注册中心 / 配置中心抽象层按域收在两个分组目录下（parent 均为根 `pom.xml`，relativePath `../../pom.xml`）：

- `armeria-grpc-registry/` — 注册中心抽象（`armeria-grpc-registry-api` SPI + `registry-nacos` / `registry-eureka` / `registry-k8s` 实现，按需引入依赖即可切换/多中心并存）。
- `armeria-grpc-config/` — 配置中心抽象（`armeria-grpc-config-api` SPI + 解析器 + `config-nacos` / `config-apollo` 实现，变更发 `AppConfigChangedEvent`）。
- `armeria-grpc-common/` — shared annotations, application identity loading (`META-INF/app.config` + proto `app_id`, with consistency check), the ops `env.properties` `EnvironmentPostProcessor`, and multi-registry merged endpoint groups.
- `armeria-server/` — gRPC infrastructure library: `@GrpcService` annotation, auto-configuration (`RpcAutoConfiguration` assembles registry/config SPI beans), client-context interceptors, governance (rate limit / circuit breaker / allow-deny lists), and a JSON schema endpoint under `src/main/java/org/example/rpc/`.
- `armeria-grpc-api/` — Spring Boot web app (port 9080). Controllers live in `src/main/java/org/example/controller/`, services in `src/main/java/org/example/service/`. It accepts `.proto` uploads, generates stubs via embedded Maven, and deploys them to a local Nexus. Resource templates and configuration are under `src/main/resources/templates/` and `src/main/resources/application.yml`.
- `armeria-grpc-gateway/` — non-blocking Armeria HTTP gateway (`/{appId}/api/{method}` -> registry-discovered instances; only discovers, never registers).
- `armeria-grpc-canary/` — gRPC test console (port 9081) for browsing namespaces/instances and invoking methods via HTTP/JSON.
- `example-server/` / `example-client/` — sample gRPC provider/consumer depending on `com.abc.order:order-server` and `com.abc.product:product-server`.

## Build, Test, and Development Commands

- `mvn clean install` — compile and package all modules, then install to the local Maven repository. The example modules require `com.abc.order:order-server` and `com.abc.product:product-server` (resolved from the local repository/Nexus).
- `mvn clean compile` — compile all modules without packaging or installing.
- `mvn spring-boot:run -pl armeria-grpc-api` — run the API application locally (port 9080).
- `mvn test` — run unit tests via the default Surefire configuration.

## Coding Style & Naming Conventions

- Use Java 17 features, UTF-8 encoding, and 4-space indentation.
- Package under `com.armeria.grpc.<module>` where `<module>` is the module short name (e.g. `com.armeria.grpc.server`, `com.armeria.grpc.gateway`, `com.armeria.grpc.canary`, `com.armeria.grpc.config.nacos`, `com.armeria.grpc.registry.k8s`, `com.armeria.grpc.common`, `com.armeria.grpc.example.server`); classes use PascalCase, methods and fields camelCase, constants `UPPER_SNAKE_CASE`. Maven `groupId` is `com.armeria.grpc`.
- No formatter or linter configuration is checked in. Match the surrounding code; existing files order `org.*` imports before `java.*`.

## Testing Guidelines

- No test sources or coverage tooling are configured yet; `src/test` directories exist per module.
- Add JUnit 5 tests under `src/test/java` mirroring the main package structure, named `*Test` (e.g., `ProtoBuildServiceTest`), and run them with `mvn test`.

## Commit & Pull Request Guidelines

- Git history currently contains only `init` commits; follow Conventional Commits going forward (`feat:`, `fix:`, `chore:`, `docs:`, etc.).
- Keep each commit focused on a single logical change.
- For pull requests: describe what changed and why, link related issues, include screenshots for UI or endpoint changes, and confirm `mvn clean install` passes before requesting review.
