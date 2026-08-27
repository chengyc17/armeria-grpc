package org.example.service;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import org.example.common.discovery.EndpointWeights;
import org.example.dto.InstanceInfo;

import java.util.List;

/**
 * 与某个 appId 绑定的动态端点组。
 *
 * <p>Nacos 实例变更时调用 {@link #update(List)} 更新端点，Armeria 客户端会立即感知
 * 新端点并按 {@link Endpoint} 权重做加权轮询，无需重建 WebClient。</p>
 */
public final class NacosEndpointGroup extends DynamicEndpointGroup {

    public NacosEndpointGroup() {
    }

    /**
     * 用最新实例列表整体替换端点。入参实例已按健康/启用过滤。
     */
    public void update(List<InstanceInfo> instances) {
        List<Endpoint> endpoints = instances.stream()
                .map(instance -> Endpoint.of(instance.ip(), instance.port())
                        .withWeight(EndpointWeights.toArmeriaWeight(instance.weight())))
                .toList();
        setEndpoints(endpoints);
    }
}
