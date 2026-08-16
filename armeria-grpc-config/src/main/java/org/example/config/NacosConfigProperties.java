package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * armeria-grpc-config 模块的配置，前缀 {@code rpc.config}。
 */
@ConfigurationProperties("rpc.config")
public class NacosConfigProperties {

    private boolean enabled = true;
    /** Nacos 配置中心地址，必须配置 */
    private String serverAddr;
    /** 命名空间；为空时取本机 ops 目录 env.properties 中的 env 值 */
    private String namespace = "";
    /** group；为空时默认取当前服务唯一的 appId */
    private String group = "";
    /** dataId，必须显式配置（如 soa.json） */
    private String dataId;
    /** 内容格式；auto 按 dataId 后缀识别（.yaml/.yml 为 YAML，.json 为 JSON，其余按 properties） */
    private Format format = Format.AUTO;
    /** 启动拉取配置的超时时间（毫秒） */
    private long timeoutMs = 3000;
    /** 订阅失败后的重试间隔（毫秒） */
    private long retryIntervalMs = 10000;

    public enum Format {
        AUTO,
        PROPERTIES,
        YAML,
        JSON
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public Format getFormat() {
        return format;
    }

    public void setFormat(Format format) {
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
