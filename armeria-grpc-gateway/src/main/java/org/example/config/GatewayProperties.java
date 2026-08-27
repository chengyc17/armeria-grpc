package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关配置，对应 application.yml 中的 gateway 前缀。
 */
@ConfigurationProperties("gateway")
public class GatewayProperties {

    /** Armeria 网关监听端口。 */
    private int port = 9082;

    private final Nacos nacos = new Nacos();
    private final Proxy proxy = new Proxy();

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Nacos getNacos() {
        return nacos;
    }

    public Proxy getProxy() {
        return proxy;
    }

    public static class Nacos {

        private String serverAddr = "127.0.0.1:8848";

        private String group = "DEFAULT_GROUP";

        private String idc = "";

        /**
         * 兜底刷新间隔（毫秒）：订阅推送偶发丢失时，保证缓存最终与 Nacos 一致。
         */
        private long refreshIntervalMs = 60_000;

        public String getServerAddr() {
            return serverAddr;
        }

        public void setServerAddr(String serverAddr) {
            this.serverAddr = serverAddr;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        /** 网关自身机房（就近访问）；为空时取 ops/env.properties 的 idc。 */
        public String getIdc() {
            return idc;
        }

        public void setIdc(String idc) {
            this.idc = idc;
        }

        public long getRefreshIntervalMs() {
            return refreshIntervalMs;
        }

        public void setRefreshIntervalMs(long refreshIntervalMs) {
            this.refreshIntervalMs = refreshIntervalMs;
        }
    }

    public static class Proxy {

        /** 到后端实例的建连超时（毫秒）。 */
        private int connectTimeoutMs = 3_000;

        /** 等待后端响应的总超时（毫秒）。 */
        private int responseTimeoutMs = 60_000;

        /** 客户端空闲连接保活时间（毫秒）。 */
        private int idleTimeoutMs = 60_000;

        /** HTTP/1.1 下每个后端端点的 event loop 数（决定并发连接上限）；HTTP/2 多路复用，几乎不受限。 */
        private int maxNumEventLoopsPerEndpoint = 8;

        /** 请求体大小上限（字节）。 */
        private long maxRequestLength = 10 * 1024 * 1024;

        /** 响应体大小上限（字节）。 */
        private long maxResponseLength = 10 * 1024 * 1024;

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getResponseTimeoutMs() {
            return responseTimeoutMs;
        }

        public void setResponseTimeoutMs(int responseTimeoutMs) {
            this.responseTimeoutMs = responseTimeoutMs;
        }

        public int getIdleTimeoutMs() {
            return idleTimeoutMs;
        }

        public void setIdleTimeoutMs(int idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
        }

        public int getMaxNumEventLoopsPerEndpoint() {
            return maxNumEventLoopsPerEndpoint;
        }

        public void setMaxNumEventLoopsPerEndpoint(int maxNumEventLoopsPerEndpoint) {
            this.maxNumEventLoopsPerEndpoint = maxNumEventLoopsPerEndpoint;
        }

        public long getMaxRequestLength() {
            return maxRequestLength;
        }

        public void setMaxRequestLength(long maxRequestLength) {
            this.maxRequestLength = maxRequestLength;
        }

        public long getMaxResponseLength() {
            return maxResponseLength;
        }

        public void setMaxResponseLength(long maxResponseLength) {
            this.maxResponseLength = maxResponseLength;
        }
    }
}
