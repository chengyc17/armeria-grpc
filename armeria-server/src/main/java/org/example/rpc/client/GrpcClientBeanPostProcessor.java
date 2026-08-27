package org.example.rpc.client;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.SessionProtocol;
import io.grpc.ServiceDescriptor;
import io.grpc.stub.AbstractStub;
import org.example.common.discovery.EndpointWeights;
import org.example.common.discovery.IdcPoolSelector;
import org.example.rpc.context.ClientContext;
import org.example.rpc.interceptor.ClientContextClientInterceptor;
import org.example.rpc.server.AppIdReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class GrpcClientBeanPostProcessor implements BeanPostProcessor, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GrpcClientBeanPostProcessor.class);

    private final NamingService namingService;
    private final ClientContext clientContext;
    private final List<StubCache> caches = new CopyOnWriteArrayList<>();

    public GrpcClientBeanPostProcessor(NamingService namingService, ClientContext clientContext) {
        this.namingService = namingService;
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
        StubCache cache = new StubCache(namingService, serviceName, stubClass, clientContext);
        caches.add(cache);
        return cache;
    }

    @Override
    public void destroy() {
        caches.forEach(StubCache::close);
        caches.clear();
    }

    /**
     * One cache per target service. A single stub is built over a dynamic endpoint
     * group, so Nacos instance changes are picked up without rebuilding the stub.
     */
    private static class StubCache {

        private final NamingService namingService;
        private final String serviceName;
        private final ClientContext clientContext;

        private final NacosEndpointGroup endpointGroup = new NacosEndpointGroup();
        private final ScheduledExecutorService retryExecutor;
        private final EventListener listener = this::onNacosEvent;

        private volatile boolean subscribed;
        private volatile ScheduledFuture<?> retryFuture;

        private final Object stub;

        StubCache(NamingService namingService, String serviceName,
                  Class<?> stubClass,
                  ClientContext clientContext) {
            this.namingService = namingService;
            this.serviceName = serviceName;
            this.clientContext = clientContext;
            this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "grpc-client-retry-" + serviceName);
                t.setDaemon(true);
                return t;
            });

            // The stub is built once over a dynamic endpoint group; an empty group simply
            // fails calls until instances are registered.
            this.stub = GrpcClients.builder(SessionProtocol.HTTP, endpointGroup)
                    .pathPrefix("/" + serviceName + "/api")
                    .intercept(new ClientContextClientInterceptor(clientContext))
                    .build(stubClass);

            boolean needsRetry = false;
            try {
                refresh();
            } catch (Exception e) {
                log.warn("No healthy instance for {} at startup, waiting for instances: {}",
                        serviceName, e.getMessage());
                needsRetry = true;
            }
            try {
                subscribeIfNeeded();
            } catch (Exception e) {
                log.warn("Failed to subscribe Nacos instance changes for {}: {}",
                        serviceName, e.getMessage());
                needsRetry = true;
            }
            if (needsRetry) {
                scheduleRetry();
            }
        }

        Object getStub() {
            return stub;
        }

        private void onNacosEvent(Event event) {
            log.info("Nacos instances changed for {}, refreshing endpoints", serviceName);
            try {
                refresh();
            } catch (Exception e) {
                log.warn("Failed to refresh endpoints for {}: {}", serviceName, e.getMessage());
                scheduleRetry();
            }
        }

        private synchronized void refresh() throws NacosException {
            List<Instance> instances = namingService.selectInstances(serviceName, true);
            if (instances.isEmpty()) {
                endpointGroup.update(List.of());
                throw new IllegalStateException("No healthy instances for service: " + serviceName);
            }
            List<Instance> pool = IdcPoolSelector.selectPool(
                    instances, clientContext.getIdc(), Instance::getMetadata);
            endpointGroup.update(pool);
            cancelRetry();
            log.info("Endpoints refreshed for {} ({} instances, {} same-idc)",
                    serviceName, instances.size(), pool.size());
        }

        private synchronized void subscribeIfNeeded() throws NacosException {
            if (subscribed) {
                return;
            }
            namingService.subscribe(serviceName, listener);
            subscribed = true;
            log.info("Subscribed Nacos instance changes for {}", serviceName);
        }

        private synchronized void scheduleRetry() {
            ScheduledFuture<?> existing = retryFuture;
            if (existing != null && !existing.isDone()) {
                return;
            }
            retryFuture = retryExecutor.scheduleWithFixedDelay(() -> {
                try {
                    refresh();
                    subscribeIfNeeded();
                    log.info("gRPC client endpoints available for {} after retry", serviceName);
                } catch (Exception e) {
                    log.warn("Endpoint refresh retry for {} failed: {}", serviceName, e.getMessage());
                }
            }, 3, 5, TimeUnit.SECONDS);
        }

        private void cancelRetry() {
            ScheduledFuture<?> future = retryFuture;
            if (future != null) {
                future.cancel(false);
                retryFuture = null;
            }
        }

        void close() {
            cancelRetry();
            retryExecutor.shutdownNow();
            if (subscribed) {
                try {
                    namingService.unsubscribe(serviceName, listener);
                } catch (NacosException e) {
                    log.warn("Failed to unsubscribe {} from Nacos: {}", serviceName, e.getErrMsg());
                }
            }
        }

        private static final class NacosEndpointGroup extends DynamicEndpointGroup {

            void update(List<Instance> instances) {
                List<Endpoint> endpoints = instances.stream()
                        .map(instance -> Endpoint.of(instance.getIp(), instance.getPort())
                                .withWeight(EndpointWeights.toArmeriaWeight(instance.getWeight())))
                        .toList();
                setEndpoints(endpoints);
            }
        }
    }
}
