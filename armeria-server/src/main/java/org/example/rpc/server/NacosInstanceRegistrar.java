package org.example.rpc.server;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the local Armeria gRPC server instance with Nacos and explicitly
 * deregisters it when the application shuts down, so the instance is removed
 * immediately instead of waiting for the Nacos heartbeat timeout.
 */
public class NacosInstanceRegistrar {

    private static final Logger log = LoggerFactory.getLogger(NacosInstanceRegistrar.class);

    private final NamingService namingService;

    private volatile String serviceName;
    private volatile String ip;
    private volatile int port;

    public NacosInstanceRegistrar(NamingService namingService) {
        this.namingService = namingService;
    }

    public void register(String serviceName, String ip, int port) throws NacosException {
        this.serviceName = serviceName;
        this.ip = ip;
        this.port = port;
        namingService.registerInstance(serviceName, ip, port);
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
