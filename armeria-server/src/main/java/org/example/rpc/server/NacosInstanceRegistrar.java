package org.example.rpc.server;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.example.common.discovery.IdcPoolSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Registers the local Armeria gRPC server instance with Nacos and explicitly
 * deregisters it when the application shuts down, so the instance is removed
 * immediately instead of waiting for the Nacos heartbeat timeout.
 *
 * <p>Registration carries supplementary metadata (e.g. the {@code idc} data
 * center from ops env) and the instance scheduling weight, so clients can do
 * idc-aware selection and honor Nacos weight settings.
 */
public class NacosInstanceRegistrar {

    private static final Logger log = LoggerFactory.getLogger(NacosInstanceRegistrar.class);

    public static final String DEFAULT_GROUP = "DEFAULT_GROUP";
    public static final String IDC_METADATA_KEY = IdcPoolSelector.IDC_METADATA_KEY;

    private final NamingService namingService;

    private volatile String serviceName;
    private volatile String ip;
    private volatile int port;

    public NacosInstanceRegistrar(NamingService namingService) {
        this.namingService = namingService;
    }

    public void register(String serviceName, String ip, int port, double weight,
                         Map<String, String> metadata) throws NacosException {
        this.serviceName = serviceName;
        this.ip = ip;
        this.port = port;

        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(weight);
        if (metadata != null && !metadata.isEmpty()) {
            instance.setMetadata(metadata);
        }
        namingService.registerInstance(serviceName, DEFAULT_GROUP, instance);
        log.info("Registered service {} -> {}:{} (weight={}, metadata={}) to Nacos",
                serviceName, ip, port, weight, metadata);
    }

    /**
     * Destroy callback: deregisters the registered instance. No-op when nothing
     * has been registered yet; failures are logged so shutdown is not blocked.
     */
    public void deregister() {
        if (serviceName == null || ip == null) {
            return;
        }
        try {
            namingService.deregisterInstance(serviceName, ip, port);
            log.info("Deregistered service {} -> {}:{} from Nacos", serviceName, ip, port);
        } catch (Exception e) {
            log.warn("Failed to deregister service {} from Nacos: {}", serviceName, e.getMessage());
        }
    }
}
