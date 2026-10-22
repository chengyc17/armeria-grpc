package com.armeria.grpc.registry.api;

import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Optional;

/**
 * 注册中心配置解析器（server / gateway 共享）。
 *
 * <p>解析规则：优先 {@code rpc.registry.registries[]}；为空且旧配置
 * {@code rpc.nacos.server-addr} 非空时，自动合成单条 nacos 配置（兼容旧版本）。
 */
public final class RegistryResolvers {

    private RegistryResolvers() {
    }

    /**
     * 解析注册中心配置列表。
     *
     * @throws IllegalStateException 既没配 registries[] 也没有旧 nacos 地址时
     */
    public static List<RegistryProperties.Item> resolve(RegistryProperties properties,
                                                        Environment environment) {
        List<RegistryProperties.Item> registries = properties.getRegistries();
        if (registries != null && !registries.isEmpty()) {
            return registries;
        }
        String legacyAddr = environment.getProperty("rpc.nacos.server-addr");
        if (legacyAddr == null || legacyAddr.isBlank()) {
            throw new IllegalStateException(
                    "rpc.registry.registries[] 未配置且 rpc.nacos.server-addr 为空，"
                            + "无法确定注册中心。请配置 rpc.registry.registries 或 rpc.nacos.server-addr");
        }
        RegistryProperties.Item legacy = new RegistryProperties.Item();
        legacy.setName("nacos-legacy");
        legacy.setType("nacos");
        legacy.setServerAddr(legacyAddr);
        legacy.setNamespace(Optional.ofNullable(environment.getProperty("env")).orElse(""));
        legacy.setWeight(environment.getProperty("rpc.nacos.weight", Double.class, 1.0));
        return List.of(legacy);
    }
}
