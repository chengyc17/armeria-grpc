package com.armeria.grpc.registry.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多中心实例合并：按 ip:port 去重（保留先出现的权重/元数据）。
 *
 * <p>⚠️ 不要在端点层（CompositeEndpointGroup / DynamicEndpointGroup）去重——
 * 同 ip:port 出现两次会被当成两个端点参与加权，等于权重翻倍。必须在合并列表时去重。
 */
public final class InstanceMerger {

    private InstanceMerger() {
    }

    /**
     * 合并多个中心的实例列表。
     *
     * @param perCenter 每个中心一次 lookup 的结果；null 中心自动跳过
     * @return 去重后的实例列表（保持第一个出现的中心顺序）
     */
    public static List<RegistryInstance> merge(List<List<RegistryInstance>> perCenter) {
        Map<String, RegistryInstance> byAddress = new LinkedHashMap<>();
        if (perCenter != null) {
            for (List<RegistryInstance> center : perCenter) {
                if (center == null) {
                    continue;
                }
                for (RegistryInstance instance : center) {
                    if (instance == null) {
                        continue;
                    }
                    byAddress.putIfAbsent(instance.address(), instance);
                }
            }
        }
        return new ArrayList<>(byAddress.values());
    }

    /** 便捷重载：合并两个中心。 */
    public static List<RegistryInstance> merge(List<RegistryInstance> a, List<RegistryInstance> b) {
        return merge(List.of(a, b));
    }
}
