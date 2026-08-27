# Repository Guidelines

This is a Java 17 multi-module Maven project: a Spring Boot web app that builds and deploys gRPC stubs from uploaded `.proto` files, plus a reusable Armeria/gRPC infrastructure library.

## Project Structure & Module Organization

- `armeria-grpc-api/` — Spring Boot web app (port 9080). Controllers live in `src/main/java/org/example/controller/`, services in `src/main/java/org/example/service/`. It accepts `.proto` uploads, generates stubs via embedded Maven, and deploys them to a local Nexus. Resource templates and configuration are under `src/main/resources/templates/` and `src/main/resources/application.yml`.
- `armeria-server/` — gRPC infrastructure library: `@GrpcService` annotation, auto-configuration, client-context interceptors, governance (rate limit / circuit breaker / allow-deny lists), and a JSON schema endpoint under `src/main/java/org/example/rpc/`.
- `armeria-grpc-common/` — shared annotations, application identity loading (`META-INF/app.config` + proto `app_id`, with consistency check), and the ops `env.properties` `EnvironmentPostProcessor`.
- `armeria-grpc-config/` — Nacos config-center client (`data-ids` pull + subscription, hot updates).
- `armeria-grpc-gateway/` — non-blocking Armeria HTTP gateway (`/{appId}/api/{method}` -> Nacos-discovered instances).
- `armeria-grpc-canary/` — gRPC test console (port 9081) for browsing namespaces/instances and invoking methods via HTTP/JSON.
- `example-server/` / `example-client/` — sample gRPC provider/consumer depending on `com.abc.order:order-server` and `com.abc.product:product-server`.

## Build, Test, and Development Commands

- `mvn clean install` — compile and package all modules, then install to the local Maven repository. The example modules require `com.abc.order:order-server` and `com.abc.product:product-server` (resolved from the local repository/Nexus).
- `mvn clean compile` — compile all modules without packaging or installing.
- `mvn spring-boot:run -pl armeria-grpc-api` — run the API application locally (port 9080).
- `mvn test` — run unit tests via the default Surefire configuration.

## Coding Style & Naming Conventions

- Use Java 17 features, UTF-8 encoding, and 4-space indentation.
- Package under `org.example.*`; classes use PascalCase, methods and fields camelCase, constants `UPPER_SNAKE_CASE`.
- No formatter or linter configuration is checked in. Match the surrounding code; existing files order `org.*` imports before `java.*`.

## Testing Guidelines

- No test sources or coverage tooling are configured yet; `src/test` directories exist per module.
- Add JUnit 5 tests under `src/test/java` mirroring the main package structure, named `*Test` (e.g., `ProtoBuildServiceTest`), and run them with `mvn test`.

## Commit & Pull Request Guidelines

- Git history currently contains only `init` commits; follow Conventional Commits going forward (`feat:`, `fix:`, `chore:`, `docs:`, etc.).
- Keep each commit focused on a single logical change.
- For pull requests: describe what changed and why, link related issues, include screenshots for UI or endpoint changes, and confirm `mvn clean install` passes before requesting review.
