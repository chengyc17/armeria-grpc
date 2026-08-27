package org.example.rpc.interceptor;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.example.rpc.context.ClientContext;

/**
 * gRPC {@link ClientInterceptor} that attaches client context metadata
 * (appId, IP, hostname, IDC) to every outbound RPC call.
 */
public class ClientContextClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> CLIENT_APP_ID_KEY =
            Metadata.Key.of("x-client-app-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_IP_KEY =
            Metadata.Key.of("x-client-ip", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_HOSTNAME_KEY =
            Metadata.Key.of("x-client-hostname", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_IDC_KEY =
            Metadata.Key.of("x-client-idc", Metadata.ASCII_STRING_MARSHALLER);

    private final ClientContext clientContext;

    public ClientContextClientInterceptor(ClientContext clientContext) {
        this.clientContext = clientContext;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(
                next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                if (clientContext.getAppId() != null) {
                    headers.put(CLIENT_APP_ID_KEY, clientContext.getAppId());
                }
                if (clientContext.getClientIp() != null) {
                    headers.put(CLIENT_IP_KEY, clientContext.getClientIp());
                }
                if (clientContext.getHostname() != null) {
                    headers.put(CLIENT_HOSTNAME_KEY, clientContext.getHostname());
                }
                if (clientContext.getIdc() != null) {
                    headers.put(CLIENT_IDC_KEY, clientContext.getIdc());
                }
                super.start(responseListener, headers);
            }
        };
    }
}
