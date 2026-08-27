package org.example.rpc.context;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC {@link ServerInterceptor} that reads client context metadata from incoming
 * RPC calls and stores it as an attribute of the current Armeria
 * {@link com.linecorp.armeria.common.RequestContext} via {@link ClientContextHolder}.
 *
 * <p>The context is tied to the request lifecycle and automatically reclaimed when
 * the request completes — no manual cleanup needed.
 */
public class ClientContextServerInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ClientContextServerInterceptor.class);

    private static final Metadata.Key<String> CLIENT_APP_ID_KEY =
            Metadata.Key.of("x-client-app-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_IP_KEY =
            Metadata.Key.of("x-client-ip", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_HOSTNAME_KEY =
            Metadata.Key.of("x-client-hostname", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_IDC_KEY =
            Metadata.Key.of("x-client-idc", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        ClientContext ctx = new ClientContext(
                headers.get(CLIENT_APP_ID_KEY),
                headers.get(CLIENT_IP_KEY),
                headers.get(CLIENT_HOSTNAME_KEY),
                headers.get(CLIENT_IDC_KEY));

        if (ClientContextHolder.set(ctx)) {
            log.debug("Set ClientContext: {}", ctx);
        } else {
            // 理论上不会发生：Armeria 在处理 gRPC 请求时 RequestContext 一定是 current 的。
            // 真出现说明调用不在 Armeria 请求范围内（如嵌入到非 Armeria 的 gRPC server），上下文将不可用。
            log.warn("No Armeria RequestContext for call {}; client context is not available",
                    call.getMethodDescriptor().getFullMethodName());
        }

        return next.startCall(call, headers);
    }
}
