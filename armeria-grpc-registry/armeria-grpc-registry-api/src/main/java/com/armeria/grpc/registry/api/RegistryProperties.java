package com.armeria.grpc.registry.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 注册中心中立配置模型，前缀 {@code rpc.registry}。
 *
 * <p>server / gateway 复用同一套配置；切换/多配中心只需改 {@code registries[]} 数组。
 * 旧配置 {@code rpc.nacos.server-addr} 自动合成单条 nacos 配置（兼容，见装配方逻辑）。
 */
@ConfigurationProperties("rpc.registry")
public class RegistryProperties {

    /** 单中心注册失败是否 fail-fast；默认 false（容忍，WARN，实例随心跳自愈） */
    private boolean failFast = false;

    /** 多中心列表 */
    private List<Item> registries = new ArrayList<>();

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public List<Item> getRegistries() {
        return registries;
    }

    public void setRegistries(List<Item> registries) {
        this.registries = registries;
    }

    public static class Item {

        /** 中心标识，必填且唯一，如 nacos-main */
        private String name;

        /** 适配器类型，必填：nacos / eureka / k8s */
        private String type;

        /** 通用地址：nacos 为 host:port，eureka 为 eureka server URL，k8s 可省略 */
        private String serverAddr;

        /** 命名空间/环境；nacos 默认取 ops env，k8s 表示 service 所在 namespace */
        private String namespace = "";

        /** 分组；默认 DEFAULT_GROUP */
        private String group = "DEFAULT_GROUP";

        /** 注册权重（可每中心不同，如备份中心调低） */
        private double weight = 1.0;

        /** false = 只发现不注册 */
        private boolean registerEnabled = true;

        /** 各中心自定义参数（eureka 的 zone、k8s 的 mode/port 等），避免配置类膨胀 */
        private Map<String, String> options = new HashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

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

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        public boolean isRegisterEnabled() {
            return registerEnabled;
        }

        public void setRegisterEnabled(boolean registerEnabled) {
            this.registerEnabled = registerEnabled;
        }

        public Map<String, String> getOptions() {
            return options;
        }

        public void setOptions(Map<String, String> options) {
            this.options = options;
        }
    }
}
