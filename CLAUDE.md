# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build all modules (generate proto stubs, compile, package, install to local repo)
mvn clean install

# Compile only (without packaging/installing)
mvn clean compile

# Run the gRPC server (after building)
mvn exec:java -pl armeria-server
```

## Architecture

Multi-module Maven project that uses Armeria to host a gRPC service.

| Module | Purpose |
|---|---|
| `armeria-grpc-api` | Contains the `.proto` definition; `protobuf-maven-plugin` generates Java stubs and gRPC service classes from it |
| `armeria-server` | Depends on `armeria-grpc-api`; starts an Armeria server on port 8080 serving the gRPC `HelloService` |

The proto file is at `armeria-grpc-api/src/main/proto/hello.proto`. Generated sources end up in two directories under `target/generated-sources/protobuf/`:
- `java/` — message classes (`HelloRequest`, `HelloReply`, etc.)
- `grpc-java/` — gRPC stub (`HelloServiceGrpc`)

The server enables unframed requests, so the endpoint is also accessible via HTTP/JSON at `http://127.0.0.1:8080/com.example.api.HelloService/SayHello`.

## Key Dependencies

- **Armeria 1.27.0** — microservice framework
- **gRPC 1.59.0** — RPC framework
- **protobuf 3.25.1** — serialization
- Versions are managed via `armeria-bom` and `grpc-bom` in the parent POM, with `protobuf.version` and `grpc.version` as properties.

## Notes

- The `exec-maven-plugin` in `armeria-server` has `mainClass` set to `com.example.server.HelloServer`; the actual class is `org.example.HelloServer`. Run via `mvn exec:java -pl armeria-server -Dexec.mainClass=org.example.HelloServer` or fix the POM.
- No tests, no resource config files, no logback.xml (uses Logback defaults).
- `armeria-grpc-api/src/main/java/org/example/Main.java` is an IntelliJ scaffold and unrelated to the gRPC functionality.
