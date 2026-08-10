package org.example.rpc.client;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.AbstractStub;
import org.example.rpc.context.ClientContext;
import org.example.rpc.interceptor.ClientContextClientInterceptor;
import org.example.rpc.server.AppIdReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

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
            if (!AbstractStub.class.isAssignableFrom(fieldType)) continue;

            try {
                Object proxy = createProxy(fieldType);
                field.setAccessible(true);
                field.set(bean, proxy);
                log.info("Injected gRPC client stub {} into {}.{}", fieldType.getSimpleName(),
                        bean.getClass().getSimpleName(), field.getName());
            } catch (Exception e) {
                throw new RuntimeException("Failed to create gRPC client for " + fieldType.getName(), e);
            }
        }
        return bean;
    }

    @SuppressWarnings("unchecked")
    private <T> T createProxy(Class<T> stubClass) throws Exception {
        Class<?> outerClass = stubClass.getEnclosingClass();
        if (outerClass == null) {
            throw new IllegalArgumentException(
                    stubClass.getName() + " is not an inner class of a generated gRPC stub");
        }

        io.grpc.ServiceDescriptor sd = (io.grpc.ServiceDescriptor) outerClass
                .getMethod("getServiceDescriptor")
                .invoke(null);

        String serviceName = AppIdReader.getAppId(sd);

        // 首次从 Nacos 获取实例 + 注册订阅监听
        StubCache cache = new StubCache(namingService, serviceName, outerClass, stubClass, clientContext);

        return (T) Proxy.newProxyInstance(
                stubClass.getClassLoader(),
                new Class[]{stubClass},
                (proxy, method, args) -> {
                    Object stub = cache.getStub();
                    return method.invoke(stub, args);
                }
        );
    }

    private static class StubCache {

        private final NamingService namingService;
        private final String serviceName;
        private final Class<?> outerClass;
        private final String factoryMethod;
        private final ClientContext clientContext;

        private volatile Object cachedStub;

        StubCache(NamingService namingService, String serviceName,
                  Class<?> outerClass, Class<?> stubClass,
                  ClientContext clientContext) throws Exception {
            this.namingService = namingService;
            this.serviceName = serviceName;
            this.outerClass = outerClass;
            this.factoryMethod = resolveFactoryMethod(stubClass);
            this.clientContext = clientContext;

            // 首次拉取实例并建 stub
            refresh();

            // 订阅 Nacos 变更通知，服务上下线自动刷新
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

        private synchronized void refresh() throws Exception {
            List<Instance> instances = namingService.selectInstances(serviceName, true);
            if (instances.isEmpty()) {
                throw new IllegalStateException("No healthy instances for service: " + serviceName);
            }
            Instance instance = instances.get(0);

            Channel channel = ManagedChannelBuilder
                    .forAddress(instance.getIp(), instance.getPort())
                    .usePlaintext()
                    .intercept(new ClientContextClientInterceptor(clientContext))
                    .build();

            Method factory = outerClass.getMethod(factoryMethod, Channel.class);
            cachedStub = factory.invoke(null, channel);
            log.info("Stub refreshed for {} -> {}:{}", serviceName, instance.getIp(), instance.getPort());
        }

        private static String resolveFactoryMethod(Class<?> stubClass) {
            String simpleName = stubClass.getSimpleName();
            if (simpleName.endsWith("FutureStub")) return "newFutureStub";
            if (simpleName.contains("BlockingStub")) return "newBlockingStub";
            return "newStub";
        }
    }
}
