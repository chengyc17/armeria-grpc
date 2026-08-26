package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * armeria-grpc-config 模块的配置，前缀 {@code rpc.config}。
 *
 * <p>采用经典 Nacos 数组配置方式：{@code data-ids} 可配置多个配置文件，
 * 启动时会全部拉取并订阅变更；group 留空时默认取当前服务唯一 gRPC 服务的 appId。
 */
@ConfigurationProperties("rpc.config")
public class NacosConfigProperties {
    /**
     * Nacos 配置中心地址，必须配置
     */
    private String serverAddr;
    /**
     * 需要拉取的配置文件列表（dataId），默认为 {@code soa.json}（服务治理配置）。
     * 未配置或拉取不到对应文件时不影响启动。
     */
    private List<String> dataIds = List.of(AppConfigService.SOA_CONFIG_DATA_ID);
    /**
     * 内容格式；auto 按每个 dataId 后缀分别识别（.yaml/.yml 为 YAML，.json 为 JSON，其余按 properties）
     */
    private Format format = Format.AUTO;
    /**
     * 启动拉取配置的超时时间（毫秒）
     */
    private long timeoutMs = 3000;
    /**
     * 订阅失败后的重试间隔（毫秒）
     */
    private long retryIntervalMs = 10000;

    public enum Format {
        AUTO,
        PROPERTIES,
        YAML,
        JSON
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public List<String> getDataIds() {
        return dataIds;
    }

    public void setDataIds(List<String> dataIds) {
        this.dataIds = dataIds;
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
