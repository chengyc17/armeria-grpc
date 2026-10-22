package com.armeria.grpc.gateway.support;

import com.armeria.grpc.registry.api.RegistryInstance;
import com.armeria.grpc.registry.api.ServiceDiscovery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用的 ServiceDiscovery 桩：可配置查询结果、记录订阅/退订与查询次数，
 * 可手动推送实例变更（模拟注册中心推送）。
 */
public final class FakeServiceDiscovery implements ServiceDiscovery {

    private final String name;
    private final Deque<Object> queryResults = new ArrayDeque<>();
    private List<RegistryInstance> defaultInstances = List.of();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> subscribed = new ArrayList<>();
    private final List<String> unsubscribed = new ArrayList<>();
    private int queryCount;

    public FakeServiceDiscovery(String name) {
        this.name = name;
    }

    public FakeServiceDiscovery defaultInstances(List<RegistryInstance> instances) {
        this.defaultInstances = instances;
        return this;
    }

    public FakeServiceDiscovery nextQueryError(RuntimeException error) {
        this.queryResults.addLast(error);
        return this;
    }

    public int queryCount() {
        return queryCount;
    }

    /** 最近一次注册的监听器（用于手动触发变更）。 */
    public Listener lastListener() {
        return listeners.isEmpty() ? null : listeners.get(listeners.size() - 1);
    }

    public List<String> subscribed() {
        return subscribed;
    }

    public List<String> unsubscribed() {
        return unsubscribed;
    }

    /**
     * 模拟注册中心推送实例变更：先更新内部实例列表（与真实中心一致：
     * 通知只是信号，消费方随后 lookup 会拿到最新列表），再触发监听器。
     */
    public void push(List<RegistryInstance> instances) {
        this.defaultInstances = List.copyOf(instances);
        listeners.forEach(listener -> listener.onInstancesChanged(instances));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "fake";
    }

    @Override
    public List<RegistryInstance> lookup(String serviceName) {
        queryCount++;
        Object result = queryResults.isEmpty() ? defaultInstances : queryResults.removeFirst();
        if (result instanceof RuntimeException error) {
            throw error;
        }
        return result == null ? List.of() : (List<RegistryInstance>) result;
    }

    @Override
    public void subscribe(String serviceName, Listener listener) {
        subscribed.add(serviceName);
        listeners.add(listener);
    }

    @Override
    public void unsubscribe(String serviceName, Listener listener) {
        unsubscribed.add(serviceName);
        listeners.remove(listener);
    }

    @Override
    public void close() {
        listeners.clear();
    }
}
