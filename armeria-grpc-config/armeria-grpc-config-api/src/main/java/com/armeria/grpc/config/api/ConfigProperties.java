package com.armeria.grpc.config.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置中心中立配置模型，前缀 {@code rpc.config}。
 *
 * <p>通过 {@code type} 切换配置中心（nacos / apollo）；其余字段按需使用：
 * nacos 用 {@code server-addr}，apollo 用 {@code meta-server} + {@code app-id}。
 * 旧配置 {@code rpc.config.server-addr} 自动兼容（type 缺省即 nacos）。
 */
@ConfigurationProperties("rpc.config")
public class ConfigProperties {

    /** 配置中心类型：nacos / apollo；默认 nacos */
    private String type = "nacos";

    /** Nacos 配置中心地址（type=nacos 时必填），如 127.0.0.1:8848 */
    private String serverAddr;

    /** Apollo meta server 地址（type=apollo 时必填），如 http://127.0.0.1:8080 */
    private String metaServer;

    /** Apollo 应用 appId（type=apollo 时使用）；缺省读 META-INF/app.config 的 appid */
    private String appId = "";

    /** 需要拉取的配置文件列表（dataId / apollo namespace），默认为 soa.json */
    private List<String> dataIds = new ArrayList<>(List.of(ConfigSource.SOA_CONFIG_DATA_ID));

    /** 内容格式；auto 按每个 dataId 后缀分别识别（.yaml/.yml 为 YAML，.json 为 JSON，其余按 properties） */
    private ConfigParser.Format format = ConfigParser.Format.AUTO;

    /** 启动拉取配置的超时时间（毫秒） */
    private long timeoutMs = 3000;

    /** 订阅/拉取失败后的重试间隔（毫秒） */
    private long retryIntervalMs = 10000;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getMetaServer() {
        return metaServer;
    }

    public void setMetaServer(String metaServer) {
        this.metaServer = metaServer;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public List<String> getDataIds() {
        return dataIds;
    }

    public void setDataIds(List<String> dataIds) {
        this.dataIds = dataIds;
    }

    public ConfigParser.Format getFormat() {
        return format;
    }

    public void setFormat(ConfigParser.Format format) {
        this.format = format;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }
}
