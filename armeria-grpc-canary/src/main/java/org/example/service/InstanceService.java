package org.example.service;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.example.config.TestConsoleProperties;
import org.example.dto.InstanceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 Nacos 查询 appid 对应的全部实例。
 * 服务端注册时使用的服务名即 proto 中的 app_id，因此 appid 就是 Nacos 服务名。
 */
@Service
public class InstanceService {

    private static final Logger log = LoggerFactory.getLogger(InstanceService.class);

    private final NamingService namingService;
    private final TestConsoleProperties properties;

    public InstanceService(NamingService namingService, TestConsoleProperties properties) {
        this.namingService = namingService;
        this.properties = properties;
    }

    public List<InstanceInfo> listInstances(String appId) {
        String group = properties.getNacos().getGroup();
        if (group == null || group.isBlank()) {
            group = "DEFAULT_GROUP";
        }
        try {
            List<Instance> instances = namingService.getAllInstances(appId, group, true);
            return instances.stream()
                    .sorted(Comparator.comparing(Instance::getIp).thenComparingInt(Instance::getPort))
                    .map(instance -> {
                        Map<String, String> metadata = instance.getMetadata() == null
                                ? Map.of()
                                : new LinkedHashMap<>(instance.getMetadata());
                        return new InstanceInfo(
                                instance.getIp(),
                                instance.getPort(),
                                address(instance.getIp(), instance.getPort()),
                                instance.getClusterName(),
                                instance.isHealthy(),
                                metadata);
                    })
                    .toList();
        } catch (NacosException e) {
            log.warn("Failed to list Nacos instances for appId={}, group={}", appId, group, e);
            throw new IllegalStateException("从 Nacos 查询实例失败: " + e.getErrMsg(), e);
        }
    }

    private String address(String ip, int port) {
        return ip.contains(":") ? "[" + ip + "]:" + port : ip + ":" + port;
    }
}
