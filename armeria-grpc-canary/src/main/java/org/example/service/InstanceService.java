package org.example.service;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.example.config.NamingServiceManager;
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
 * 从 Nacos 查询指定命名空间下 appid 对应的全部实例。
 * 服务端注册时使用的服务名即 proto 中的 app_id，因此 appid 就是 Nacos 服务名。
 */
@Service
public class InstanceService {

    private static final Logger log = LoggerFactory.getLogger(InstanceService.class);

    private final NamingServiceManager namingServiceManager;
    private final TestConsoleProperties properties;

    public InstanceService(NamingServiceManager namingServiceManager, TestConsoleProperties properties) {
        this.namingServiceManager = namingServiceManager;
        this.properties = properties;
    }

    /**
     * @param namespace 命名空间；为空时使用默认命名空间（test-console.nacos.namespace > env > public）
     */
    public List<InstanceInfo> listInstances(String appId, String namespace) {
        String ns = namespace == null || namespace.isBlank()
                ? namingServiceManager.defaultNamespace() : namespace.trim();
        String group = properties.getNacos().getGroup();
        if (group == null || group.isBlank()) {
            group = "DEFAULT_GROUP";
        }
        NamingService namingService = namingServiceManager.forNamespace(ns);
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
            log.warn("Failed to list Nacos instances for appId={}, namespace={}, group={}", appId, ns, group, e);
            throw new IllegalStateException("从 Nacos 查询实例失败 (namespace=" + ns + "): " + e.getErrMsg(), e);
        }
    }

    private String address(String ip, int port) {
        return ip.contains(":") ? "[" + ip + "]:" + port : ip + ":" + port;
    }
}
