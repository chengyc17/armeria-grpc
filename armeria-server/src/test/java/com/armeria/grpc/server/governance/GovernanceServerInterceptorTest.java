package com.armeria.grpc.server.governance;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import com.armeria.grpc.config.api.ConfigSource;
import com.armeria.grpc.server.context.ClientContextServerInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static io.grpc.MethodDescriptor.MethodType.UNARY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 端到端验证 {@link GovernanceServerInterceptor} 的黑白名单按<b>调用方 appId</b>生效：
 * 启动真实 Armeria Server，按 RpcAutoConfiguration 的注册顺序挂载
 * ClientContextServerInterceptor + GovernanceServerInterceptor，
 * 用原生 gRPC 客户端携带 x-client-app-id 头发起调用。
 */
class GovernanceServerInterceptorTest {

    private static final Metadata.Key<String> CLIENT_APP_ID_KEY =
            Metadata.Key.of("x-client-app-id", Metadata.ASCII_STRING_MARSHALLER);

    private static final MethodDescriptor<String, String> ECHO =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(UNARY)
                    .setFullMethodName("test.GovernanceService/echo")
                    .setRequestMarshaller(StringMarshaller.INSTANCE)
                    .setResponseMarshaller(StringMarshaller.INSTANCE)
                    .build();

    /** 可变 soa.json 内容（用于在测试中切换黑/白名单配置并热更新） */
    private static final String[] soaContent = {"{\"blacklist\":[\"bad-app\"]}"};

    private static final GovernanceConfigManager manager;
    private static final Server server;
    private static final ManagedChannel channel;

    static {
        manager = new GovernanceConfigManager(new StubProvider(), "server-app");
        manager.reload();

        ServerServiceDefinition service = ServerServiceDefinition.builder("test.GovernanceService")
                .addMethod(ECHO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    responseObserver.onNext("ok:" + request);
                    responseObserver.onCompleted();
                }))
                .build();

        // 与 RpcAutoConfiguration 相同的注册顺序：ClientContext 拦截器先注册，治理拦截器后注册
        Server s = Server.builder()
                .http(0)
                .service(GrpcService.builder()
                        .intercept(new ClientContextServerInterceptor())
                        .intercept(new GovernanceServerInterceptor(manager))
                        .addService(service)
                        .build())
                .build();
        s.start().join();
        server = s;

        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.activeLocalPort())
                .usePlaintext()
                .build();
    }

    @Test
    void blacklistedAppIdIsRejected() {
        StatusRuntimeException e = expectRejected("bad-app");
        assertEquals(Status.Code.PERMISSION_DENIED, e.getStatus().getCode());
        assertTrue(e.getStatus().getDescription().contains("bad-app"));
    }

    @Test
    void allowedAppIdPasses() {
        assertEquals("ok:ping", call("good-app"));
    }

    @Test
    void whitelistDeniesCallersWithoutAppId() {
        try {
            // 热更新为白名单模式：只允许 good-app 调用
            soaContent[0] = "{\"whitelist\":[\"good-app\"]}";
            manager.reload();

            // 未携带 appId 的调用方不在白名单内，拒绝
            StatusRuntimeException e = expectRejected(null);
            assertEquals(Status.Code.PERMISSION_DENIED, e.getStatus().getCode());

            // 白名单内的调用方放行
            assertEquals("ok:ping", call("good-app"));

            // 白名单外的调用方拒绝
            assertEquals(Status.Code.PERMISSION_DENIED,
                    expectRejected("stranger-app").getStatus().getCode());
        } finally {
            soaContent[0] = "{\"blacklist\":[\"bad-app\"]}";
            manager.reload();
        }
    }

    @AfterAll
    static void tearDown() {
        channel.shutdownNow();
        server.stop().join();
    }

    /** 携带指定 appId 发起调用，断言被治理拦截器拒绝并返回异常 */
    private static StatusRuntimeException expectRejected(String appId) {
        try {
            call(appId);
        } catch (StatusRuntimeException e) {
            return e;
        }
        fail("Expected PERMISSION_DENIED for appId: " + appId);
        return null; // unreachable
    }

    private static String call(String appId) {
        Metadata headers = new Metadata();
        if (appId != null) {
            headers.put(CLIENT_APP_ID_KEY, appId);
        }
        Channel ch = ClientInterceptors.intercept(
                channel, MetadataUtils.newAttachHeadersInterceptor(headers));
        return ClientCalls.blockingUnaryCall(ch, ECHO, CallOptions.DEFAULT, "ping");
    }

    /** 返回可变 soa.json 内容的 ConfigSource 桩（config-api 抽象层，避免依赖具体 nacos 类） */
    private static final class StubConfigSource implements ConfigSource {
        @Override
        public String type() {
            return "test";
        }

        @Override
        public String getRawConfig(String dataId) {
            return ConfigSource.SOA_CONFIG_DATA_ID.equals(dataId) ? soaContent[0] : null;
        }

        @Override
        public boolean isLoaded(String dataId) {
            return true;
        }

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public java.util.Map<String, Object> getConfig(String dataId) {
            return java.util.Map.of();
        }

        @Override
        public java.util.Map<String, String> getProperties(String dataId) {
            return java.util.Map.of();
        }

        @Override
        public java.util.Map<String, String> getProperties() {
            return java.util.Map.of();
        }

        @Override
        public String getProperty(String dataId, String key, String defaultValue) {
            return defaultValue;
        }
    }

    private static final class StubProvider implements ObjectProvider<ConfigSource> {
        @Override
        public ConfigSource getObject() {
            return new StubConfigSource();
        }

        @Override
        public ConfigSource getObject(Object... args) {
            return new StubConfigSource();
        }

        @Override
        public ConfigSource getIfAvailable() {
            return new StubConfigSource();
        }

        @Override
        public ConfigSource getIfUnique() {
            return new StubConfigSource();
        }
    }

    private enum StringMarshaller implements MethodDescriptor.Marshaller<String> {
        INSTANCE;

        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try (InputStream in = stream) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse message", e);
            }
        }
    }
}
