package org.example.rpc.context;

/**
 * Holds metadata about the calling client, propagated via gRPC headers.
 *
 * <p>On the client side it describes the current process itself (self info);
 * on the server side, after {@link ClientContextServerInterceptor} parses the
 * incoming headers, it describes the caller of the current request.
 */
public class ClientContext {

    private final String appId;
    private final String clientIp;
    private final String hostname;
    private final String idc;

    public ClientContext(String appId, String clientIp, String hostname, String idc) {
        this.appId = appId;
        this.clientIp = clientIp;
        this.hostname = hostname;
        this.idc = idc;
    }

    /** 调用方应用的 appId（服务治理黑白名单的匹配维度） */
    public String getAppId() { return appId; }
    public String getClientIp() { return clientIp; }
    public String getHostname() { return hostname; }
    public String getIdc() { return idc; }

    @Override
    public String toString() {
        return "ClientContext{" +
                "appId='" + appId + '\'' +
                ", clientIp='" + clientIp + '\'' +
                ", hostname='" + hostname + '\'' +
                ", idc='" + idc + '\'' +
                '}';
    }
}
