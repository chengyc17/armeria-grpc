package org.example.rpc.server;

import io.grpc.BindableService;
import org.example.rpc.annotation.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 每个服务实例只允许一个 {@code @GrpcService} 实现，对应注册中心里的唯一 appId。
 */
public final class GrpcServices {

    private static final Logger log = LoggerFactory.getLogger(GrpcServices.class);

    private GrpcServices() {}

    /**
     * 从容器中取出唯一的 gRPC 接口实现。
     *
     * @throws IllegalStateException 没有实现，或存在多个实现
     */
    public static BindableService requireSingle(ApplicationContext context) {
        Map<String, Object> services = context.getBeansWithAnnotation(GrpcService.class);
        List<BindableService> grpcServices = findBindableServices(context);

        if (services.isEmpty()) {
            throw new IllegalStateException(
                    "No @GrpcService beans found. Mark your BindableService implementations with @GrpcService.");
        }
        if (grpcServices.isEmpty()) {
            throw new IllegalStateException(
                    "No BindableService found among @GrpcService beans, cannot determine appId.");
        }
        if (grpcServices.size() > 1) {
            String found = services.entrySet().stream()
                    .map(e -> "bean '" + e.getKey() + "' (" + e.getValue().getClass().getName() + ")")
                    .collect(Collectors.joining(", "));
            log.error("Found {} gRPC service implementations, only one is allowed per server instance: {}",
                    grpcServices.size(), found);
            throw new IllegalStateException(
                    "Found " + grpcServices.size() + " gRPC interface implementation classes annotated with "
                            + "@GrpcService, but only one is allowed per server instance because one instance "
                            + "can register only one appId to the registry. Found: " + found
                            + ". Please deploy each gRPC service as a separate instance, or remove the extra "
                            + "@GrpcService beans.");
        }
        return grpcServices.get(0);
    }

    /**
     * 解析唯一 gRPC 实现对应的 appId（来自 proto 的 option (app_id)）。
     */
    public static String resolveAppId(ApplicationContext context) {
        return AppIdReader.getAppId(requireSingle(context).bindService().getServiceDescriptor());
    }

    /**
     * {@link #resolveAppId} 的宽松版本：本进程未提供任何 gRPC 服务（纯客户端进程）时返回
     * {@code null} 而不是抛异常，供客户端上下文等场景兜底使用。
     *
     * @throws IllegalStateException 存在多个 @GrpcService 实现
     */
    public static String findAppId(ApplicationContext context) {
        if (findBindableServices(context).isEmpty()) {
            return null;
        }
        return resolveAppId(context);
    }

    private static List<BindableService> findBindableServices(ApplicationContext context) {
        Map<String, Object> services = context.getBeansWithAnnotation(GrpcService.class);
        return services.values().stream()
                .filter(BindableService.class::isInstance)
                .map(BindableService.class::cast)
                .toList();
    }
}
