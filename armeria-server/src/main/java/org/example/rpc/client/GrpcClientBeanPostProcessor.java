package org.example.rpc.client;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.linecorp.armeria.client.grpc.GrpcClients;
import io.grpc.ServiceDescriptor;
import io.grpc.stub.AbstractStub;
import org.example.rpc.context.ClientContext;
import org.example.rpc.interceptor.ClientContextClientInterceptor;
import org.example.rpc.server.AppIdReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class GrpcClientBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(GrpcClientBeanPostProcessor.class);

    private final NamingService namingService;
    private final ClientContext clientContext;

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
                cache.register(field, bean);
                if (stub != null) {
                    log.info("Injected gRPC client stub {} into {}.{}", fieldType.getSimpleName(),
                            bean.getClass().getSimpleName(), field.getName());
                } else {
                    log.info("gRPC client stub {} for {}.{} deferred until an instance is available",
                            fieldType.getSimpleName(), bean.getClass().getSimpleName(), field.getName());
                }
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
        return new StubCache(namingService, serviceName, stubClass, clientContext);
    }

    private static class StubCache {

        private final NamingService namingService;
        private final String serviceName;
        private final Class<?> stubClass;
        private final ClientContext clientContext;

        private final List<FieldEntry> fieldEntries = new CopyOnWriteArrayList<>();

        private final ScheduledExecutorService retryExecutor;
        private volatile ScheduledFuture<?> retryFuture;

        private volatile Object cachedStub;

        StubCache(NamingService namingService, String serviceName,
                  Class<?> stubClass,
                  ClientContext clientContext) throws Exception {
            this.namingService = namingService;
            this.serviceName = serviceName;
            this.stubClass = stubClass;
            this.clientContext = clientContext;
            this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "grpc-client-retry-" + serviceName);
                t.setDaemon(true);
                return t;
            });

            // 首次从 Nacos 获取实例并创建 stub；没有实例时先不失败，后台重试
            try {
                refresh();
            } catch (Exception e) {
                log.warn("No healthy instance for {} at startup, stub creation deferred: {}",
                        serviceName, e.getMessage());
                scheduleRetry();
            }

            // 订阅 Nacos 变更通知，服务上下线时重建 stub 并刷新已注入的字段
            namingService.subscribe(serviceName, event -> {
                if (event instanceof NamingEvent) {
                    log.info("Nacos instances changed for {}, refreshing stub", serviceName);
                    try {
                        refresh();
                    } catch (Exception e) {
                        log.error("Failed to refresh stub for {}", serviceName, e);
                    }
                }
            });
        }

        Object getStub() {
            return cachedStub;
        }

        void register(Field field, Object bean) {
            fieldEntries.add(new FieldEntry(field, bean));
        }

        private void scheduleRetry() {
            retryFuture = retryExecutor.scheduleWithFixedDelay(() -> {
                try {
                    refresh();
                    log.info("Stub created for {} after retry", serviceName);
                } catch (Exception e) {
                    log.warn("Stub creation retry for {} failed: {}", serviceName, e.getMessage());
                }
            }, 3, 5, TimeUnit.SECONDS);
        }

        private synchronized void refresh() throws Exception {
            List<Instance> instances = namingService.selectInstances(serviceName, true);
            if (instances.isEmpty()) {
                throw new IllegalStateException("No healthy instances for service: " + serviceName);
            }
            Instance instance = instances.get(0);

            // 与 armeria-server 的挂载路径保持一致：/{appId}/api/{包名.Service}/{方法名}
            String uri = "gproto+http://" + instance.getIp() + ":" + instance.getPort();
            Object newStub = GrpcClients.builder(uri)
                    .pathPrefix("/" + serviceName + "/api")
                    .intercept(new ClientContextClientInterceptor(clientContext))
                    .build(stubClass);
            cachedStub = newStub;

            for (FieldEntry entry : fieldEntries) {
                try {
                    entry.field.setAccessible(true);
                    entry.field.set(entry.bean, newStub);
                } catch (Exception e) {
                    log.error("Failed to update gRPC stub field {}.{}",
                            entry.bean.getClass().getSimpleName(), entry.field.getName(), e);
                }
            }
            ScheduledFuture<?> future = retryFuture;
            if (future != null) {
                future.cancel(false);
                retryFuture = null;
            }
            log.info("Stub refreshed for {} -> {}:{}", serviceName, instance.getIp(), instance.getPort());
        }

        private static class FieldEntry {

            private final Field field;
            private final Object bean;

            FieldEntry(Field field, Object bean) {
                this.field = field;
                this.bean = bean;
            }
        }
    }
}
