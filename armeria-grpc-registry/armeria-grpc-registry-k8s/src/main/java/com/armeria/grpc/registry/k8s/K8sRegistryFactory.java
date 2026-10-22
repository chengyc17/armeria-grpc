package com.armeria.grpc.registry.k8s;

import com.armeria.grpc.registry.api.Registry;
import com.armeria.grpc.registry.api.RegistryFactory;
import com.armeria.grpc.registry.api.RegistryProperties;
import org.springframework.core.env.Environment;

/**
 * K8s 注册中心工厂（极简）：把目标 appId 当作 K8s Service 名，用 Service DNS 域名直连。
 *
 * <p>namespace 解析优先级：{@code registries[].namespace} 显式配置
 * {@code >} 本机 ops env.properties 的 {@code env} 值（Spring property {@code env}，
 * 由 armeria-grpc-common 的 OpsEnvironmentPostProcessor 注入）；两者皆无则启动失败。
 */
public class K8sRegistryFactory implements RegistryFactory {

    private final Environment environment;

    public K8sRegistryFactory(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String type() {
        return "k8s";
    }

    @Override
    public Registry create(RegistryProperties.Item props) {
        String namespace = props.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            // k8s 命名空间默认 = ops env.properties 中的 env 值
            namespace = environment.getProperty("env");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalStateException("k8s registry [" + props.getName()
                    + "] namespace 未配置：请在 rpc.registry.registries[] 中显式配置 namespace，"
                    + "或确保本机 ops env.properties 包含 env 键");
        }
        return new K8sRegistry(props, namespace.trim());
    }
}
