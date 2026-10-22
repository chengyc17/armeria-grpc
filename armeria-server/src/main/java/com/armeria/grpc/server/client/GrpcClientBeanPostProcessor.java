package com.armeria.grpc.server.client;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.SessionProtocol;
import io.grpc.ServiceDescriptor;
import io.grpc.stub.AbstractStub;
import com.armeria.grpc.common.discovery.MultiRegistryEndpointGroup;
import com.armeria.grpc.registry.api.ServiceDiscovery;
import com.armeria.grpc.server.context.ClientContext;
import com.armeria.grpc.server.interceptor.ClientContextClientInterceptor;
import com.armeria.grpc.common.server.AppIdReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 按 AbstractStub 字段类型自动注入 gRPC 客户端（无注解）。
 * 目标服务 appId 从 stub 内层类的 getServiceDescriptor 反推；
 * 端点来自多个注册中心的合并发现（MultiRegistryEndpointGroup），
 * 任一中心实例变更都会自动刷新，无需重建 stub。
 */
public class GrpcClientBeanPostProcessor implements BeanPostProcessor, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GrpcClientBeanPostProcessor.class);

    private final List<ServiceDiscovery> discoveries;
    private final ClientContext clientContext;
    private final List<StubCache> caches = new CopyOnWriteArrayList<>();

    public GrpcClientBeanPostProcessor(List<ServiceDiscovery> discoveries, ClientContext clientContext) {
        this.discoveries = discoveries;
        this.clientContext = clientContext;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        for (Field field : bean.getClass().getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            if (!AbstractStub.class.isAssignableFrom(fieldType)) {
                continue;
            }

            try {
                StubCache cache = createCache(fieldType);
                Object stub = cache.getStub();
                field.setAccessible(true);
                field.set(bean, stub);
                log.info("Injected gRPC client stub {} into {}.{}", fieldType.getSimpleName(),
                        bean.getClass().getSimpleName(), field.getName());
            } catch (Exception e) {
                throw new RuntimeException("Failed to create gRPC client for " + fieldType.getName(), e);
            }
        }
        return bean;
    }

    private StubCache createCache(Class<?> stubClass) throws Exception {
        Class<?> outerClass = stubClass.getEnclosingClass();
        if (outerClass == null) {
            throw new IllegalArgumentException(
                    stubClass.getName() + " is not an inner class of a generated gRPC stub");
        }

        ServiceDescriptor sd = (ServiceDescriptor) outerClass
                .getMethod("getServiceDescriptor")
                .invoke(null);

        String serviceName = AppIdReader.getAppId(sd);
        StubCache cache = new StubCache(discoveries, serviceName, stubClass, clientContext);
        caches.add(cache);
        return cache;
    }

    @Override
    public void destroy() {
        caches.forEach(StubCache::close);
        caches.clear();
    }

    /**
     * One cache per target service. A single stub is built over a multi-registry
     * dynamic endpoint group, so instance changes are picked up without rebuilding the stub.
     */
    private static class StubCache {

        private final MultiRegistryEndpointGroup endpointGroup;
        private final Object stub;

        StubCache(List<ServiceDiscovery> discoveries, String serviceName,
                  Class<?> stubClass,
                  ClientContext clientContext) {
            // 多中心合并端点组：构造时即做首次查询 + 订阅，无健康实例时内部 3s/5s 重试
            this.endpointGroup = new MultiRegistryEndpointGroup(
                    discoveries, serviceName, clientContext.getIdc());

            this.stub = GrpcClients.builder(SessionProtocol.HTTP, endpointGroup)
                    .pathPrefix("/" + serviceName + "/api")
                    .intercept(new ClientContextClientInterceptor(clientContext))
                    .build(stubClass);
        }

        Object getStub() {
            return stub;
        }

        void close() {
            endpointGroup.closeGroup();
        }
    }
}
