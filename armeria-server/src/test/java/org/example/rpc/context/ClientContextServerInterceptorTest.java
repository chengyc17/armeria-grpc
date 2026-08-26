package org.example.rpc.context;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.grpc.MethodDescriptor.MethodType.UNARY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 端到端验证 {@link ClientContextServerInterceptor} + {@link ClientContextHolder}：
 * 启动真实 Armeria Server 挂载 gRPC 服务，用原生 gRPC 客户端发起调用，
 * 断言客户端上下文在服务方法内、blockingTaskExecutor 线程内均正确可见，
 * 且连续两次调用之间没有残留（InheritableThreadLocal 方案的经典缺陷）。
 *
 * <p>该类同时提供 {@code main} 入口，便于脱离测试框架直接运行验证。
 */
class ClientContextServerInterceptorTest {

    private static final Metadata.Key<String> CLIENT_APP_ID_KEY =
            Metadata.Key.of("x-client-app-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_IP_KEY =
            Metadata.Key.of("x-client-ip", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_HOSTNAME_KEY =
            Metadata.Key.of("x-client-hostname", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_IDC_KEY =
            Metadata.Key.of("x-client-idc", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_GROUP_NAME_KEY =
            Metadata.Key.of("x-client-group-name", Metadata.ASCII_STRING_MARSHALLER);

    private static final MethodDescriptor<String, String> ECHO =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(UNARY)
                    .setFullMethodName("test.ClientContextService/echo")
                    .setRequestMarshaller(StringMarshaller.INSTANCE)
                    .setResponseMarshaller(StringMarshaller.INSTANCE)
                    .build();

    /** 服务方法内观察到的 ClientContext（每次调用前重置） */
    private static volatile CompletableFuture<ClientContext> observed = new CompletableFuture<>();

    /** 为 true 时把观察动作派发到 blockingTaskExecutor，验证上下文跨线程传播 */
    private static volatile boolean observeInBlockingExecutor = false;

    private static final Server server;
    private static final ManagedChannel channel;

    static {
        ServerServiceDefinition service = ServerServiceDefinition.builder("test.ClientContextService")
                .addMethod(ECHO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    if (observeInBlockingExecutor) {
                        ServiceRequestContext.current().blockingTaskExecutor()
                                .execute(() -> observed.complete(ClientContextHolder.get()));
                    } else {
                        observed.complete(ClientContextHolder.get());
                    }
                    responseObserver.onNext("ok:" + request);
                    responseObserver.onCompleted();
                }))
                .build();

        Server s = Server.builder()
                .http(0)
                .service(GrpcService.builder()
                        .intercept(new ClientContextServerInterceptor())
                        .addService(service)
                        .build())
                .build();
        s.start().join();
        server = s;

        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.activeLocalPort())
                .usePlaintext()
                .build();
    }

    @BeforeEach
    void resetObserved() {
        observed = new CompletableFuture<>();
    }

    @Test
    void contextVisibleInServiceMethod() throws Exception {
        String resp = call("mywork-demo", "10.1.2.3", "host-a", "idc-a", "group-a");
        assertEquals("ok:ping", resp);

        ClientContext ctx = observed.get(5, TimeUnit.SECONDS);
        assertNotNull(ctx);
        assertEquals("mywork-demo", ctx.getAppId());
        assertEquals("10.1.2.3", ctx.getClientIp());
        assertEquals("host-a", ctx.getHostname());
        assertEquals("idc-a", ctx.getIdc());
        assertEquals("group-a", ctx.getGroupName());
    }

    @Test
    void contextPropagatesToBlockingTaskExecutor() throws Exception {
        observeInBlockingExecutor = true;
        try {
            String resp = call("mywork-demo", "10.9.9.9", "host-b", "idc-b", "group-b");
            assertEquals("ok:ping", resp);

            ClientContext ctx = observed.get(5, TimeUnit.SECONDS);
            assertNotNull(ctx);
            assertEquals("mywork-demo", ctx.getAppId());
            assertEquals("10.9.9.9", ctx.getClientIp());
            assertEquals("host-b", ctx.getHostname());
        } finally {
            observeInBlockingExecutor = false;
        }
    }

    @Test
    void noStaleContextBetweenCalls() throws Exception {
        call("app-1", "1.1.1.1", "h1", "i1", "g1");
        ClientContext first = observed.get(5, TimeUnit.SECONDS);
        assertNotNull(first);
        assertEquals("app-1", first.getAppId());
        assertEquals("1.1.1.1", first.getClientIp());

        // 第二次调用不带任何上下文头：不应看到上一次调用的残留
        observed = new CompletableFuture<>();
        String resp = call(null, null, null, null, null);
        assertEquals("ok:ping", resp);

        ClientContext second = observed.get(5, TimeUnit.SECONDS);
        assertNotNull(second);
        assertNull(second.getAppId());
        assertNull(second.getClientIp());
        assertNull(second.getHostname());
        assertNull(second.getIdc());
        assertNull(second.getGroupName());
    }

    @Test
    void returnsNullOutsideRequest() {
        // 不在任何 Armeria 请求范围内时，holder 应安静地返回 null
        assertNull(ClientContextHolder.get());
    }

    @AfterAll
    static void tearDown() {
        channel.shutdownNow();
        server.stop().join();
    }

    private String call(String appId, String ip, String hostname, String idc, String groupName) {
        Metadata headers = new Metadata();
        if (appId != null) {
            headers.put(CLIENT_APP_ID_KEY, appId);
        }
        if (ip != null) {
            headers.put(CLIENT_IP_KEY, ip);
        }
        if (hostname != null) {
            headers.put(CLIENT_HOSTNAME_KEY, hostname);
        }
        if (idc != null) {
            headers.put(CLIENT_IDC_KEY, idc);
        }
        if (groupName != null) {
            headers.put(CLIENT_GROUP_NAME_KEY, groupName);
        }
        Channel ch = ClientInterceptors.intercept(
                channel, MetadataUtils.newAttachHeadersInterceptor(headers));
        return ClientCalls.blockingUnaryCall(ch, ECHO, CallOptions.DEFAULT, "ping");
    }

    /** 脱离测试框架的直接运行入口 */
    public static void main(String[] args) throws Exception {
        ClientContextServerInterceptorTest test = new ClientContextServerInterceptorTest();
        try {
            test.resetObserved();
            test.contextVisibleInServiceMethod();
            System.out.println("[PASS] contextVisibleInServiceMethod");

            test.resetObserved();
            test.contextPropagatesToBlockingTaskExecutor();
            System.out.println("[PASS] contextPropagatesToBlockingTaskExecutor");

            test.resetObserved();
            test.noStaleContextBetweenCalls();
            System.out.println("[PASS] noStaleContextBetweenCalls");

            test.returnsNullOutsideRequest();
            System.out.println("[PASS] returnsNullOutsideRequest");
        } finally {
            tearDown();
        }
        System.out.println("ALL TESTS PASSED");
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
