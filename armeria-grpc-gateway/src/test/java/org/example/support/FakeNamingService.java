package org.example.support;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 测试用的 NamingService 桩：可配置查询结果、记录订阅/退订与查询次数。
 * 通过动态代理实现，避免对 Mockito 的依赖。
 */
public final class FakeNamingService {

    private final Deque<Object> queryResults = new ArrayDeque<>();
    private List<Instance> defaultInstances = List.of();
    private final List<EventListener> listeners = new ArrayList<>();
    private final List<String> unsubscribed = new ArrayList<>();
    private int queryCount;

    public NamingService proxy() {
        return (NamingService) Proxy.newProxyInstance(
                NamingService.class.getClassLoader(),
                new Class<?>[]{NamingService.class},
                this::invoke);
    }

    public FakeNamingService defaultInstances(List<Instance> instances) {
        this.defaultInstances = instances;
        return this;
    }

    public FakeNamingService nextQueryError(NacosException error) {
        this.queryResults.addLast(error);
        return this;
    }

    public int queryCount() {
        return queryCount;
    }

    public EventListener lastListener() {
        return listeners.isEmpty() ? null : listeners.get(listeners.size() - 1);
    }

    public List<String> unsubscribed() {
        return unsubscribed;
    }

    private Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "getAllInstances":
                queryCount++;
                Object result = queryResults.isEmpty() ? defaultInstances : queryResults.removeFirst();
                if (result instanceof NacosException error) {
                    throw error;
                }
                return result;
            case "subscribe":
                if (args != null) {
                    for (Object arg : args) {
                        if (arg instanceof EventListener listener) {
                            listeners.add(listener);
                            break;
                        }
                    }
                }
                return null;
            case "unsubscribe":
                if (args != null && args.length > 0) {
                    unsubscribed.add((String) args[0]);
                }
                return null;
            case "toString":
                return "FakeNamingService";
            default:
                return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
