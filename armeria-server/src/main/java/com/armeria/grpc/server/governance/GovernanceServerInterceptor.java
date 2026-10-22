package com.armeria.grpc.server.governance;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * gRPC 服务端治理拦截器：黑白名单（按调用方 appId） -> 限流（全局 QPS + 指定接口 QPS） -> 熔断。
 *
 * <p>黑白名单的匹配维度是调用方的 appId（随请求的 {@code x-client-app-id} 头传入，
 * 由 {@code ClientContextClientInterceptor} 在调用方一侧自动携带）。这里直接读请求头
 * 而不是 {@code ClientContextHolder}：拦截器按注册的逆序执行，本拦截器先于
 * ClientContext 拦截器运行，此时上下文尚未填充。
 */
public class GovernanceServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> CLIENT_APP_ID_KEY =
            Metadata.Key.of("x-client-app-id", Metadata.ASCII_STRING_MARSHALLER);

    private final GovernanceConfigManager configManager;

    public GovernanceServerInterceptor(GovernanceConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String appId = headers.get(CLIENT_APP_ID_KEY);
        if (!configManager.isAppIdAllowed(appId)) {
            call.close(Status.PERMISSION_DENIED.withDescription(
                    "App not allowed: " + (appId == null ? "<no app id>" : appId)), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        String fullMethod = call.getMethodDescriptor().getFullMethodName();
        String bareMethod = call.getMethodDescriptor().getBareMethodName();
        if (!configManager.tryAcquire(fullMethod, bareMethod)) {
            call.close(Status.RESOURCE_EXHAUSTED.withDescription("Rate limited: " + fullMethod), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        if (configManager.isCircuitBreakerEnabled()) {
            if (!configManager.tryAcquireCircuitBreaker()) {
                call.close(Status.UNAVAILABLE.withDescription("Circuit breaker is open"), new Metadata());
                return new ServerCall.Listener<>() {};
            }
            // 熔断启用时才需要包装 call 来上报结果；关闭时直接放行，避免每个请求额外分配包装对象
            ReportingServerCall<ReqT, RespT> reportingCall = new ReportingServerCall<>(call, configManager);
            ServerCall.Listener<ReqT> delegate = next.startCall(reportingCall, headers);
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
                @Override
                public void onCancel() {
                    // Client-side cancellation is a failed call from the breaker's perspective;
                    // without this, cancelled calls are never counted.
                    reportingCall.reportFailure();
                    super.onCancel();
                }
            };
        }
        return next.startCall(call, headers);
    }

    private static final class ReportingServerCall<ReqT, RespT> extends ForwardingServerCall<ReqT, RespT> {

        private final ServerCall<ReqT, RespT> delegate;
        private final GovernanceConfigManager configManager;
        private boolean reported;

        ReportingServerCall(ServerCall<ReqT, RespT> delegate, GovernanceConfigManager configManager) {
            this.delegate = delegate;
            this.configManager = configManager;
        }

        @Override
        protected ServerCall<ReqT, RespT> delegate() {
            return delegate;
        }

        @Override
        public void close(Status status, Metadata trailers) {
            report(status);
            delegate.close(status, trailers);
        }

        private void report(Status status) {
            if (reported) {
                return;
            }
            reported = true;
            configManager.reportCircuitBreakerResult(status.isOk());
        }

        void reportFailure() {
            report(Status.CANCELLED);
        }
    }
}
