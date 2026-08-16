package org.example.rpc.governance;

import com.linecorp.armeria.server.ServiceRequestContext;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.net.InetSocketAddress;

/**
 * gRPC 服务端治理拦截器：黑白名单 -> 限流（全局 QPS + 指定接口 QPS） -> 熔断。
 */
public class GovernanceServerInterceptor implements ServerInterceptor {

    private final GovernanceConfigManager configManager;

    public GovernanceServerInterceptor(GovernanceConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String ip = remoteIp();
        if (ip != null && !configManager.isIpAllowed(ip)) {
            call.close(Status.PERMISSION_DENIED.withDescription("IP not allowed: " + ip), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        String fullMethod = call.getMethodDescriptor().getFullMethodName();
        String bareMethod = call.getMethodDescriptor().getBareMethodName();
        if (!configManager.tryAcquire(fullMethod, bareMethod)) {
            call.close(Status.RESOURCE_EXHAUSTED.withDescription("Rate limited: " + fullMethod), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        if (!configManager.tryAcquireCircuitBreaker()) {
            call.close(Status.UNAVAILABLE.withDescription("Circuit breaker is open"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        ServerCall.Listener<ReqT> delegate = next.startCall(new ReportingServerCall<>(call, configManager), headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {};
    }

    private static String remoteIp() {
        ServiceRequestContext ctx = ServiceRequestContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        InetSocketAddress remote = ctx.remoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return null;
        }
        return remote.getAddress().getHostAddress();
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
    }
}
