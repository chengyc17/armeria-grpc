package org.example.controller;

import org.example.config.NamingServiceManager;
import org.example.dto.InstanceInfo;
import org.example.dto.InvokeRequest;
import org.example.dto.InvokeResult;
import org.example.dto.MethodInfo;
import org.example.dto.NamespaceInfo;
import org.example.service.InstanceService;
import org.example.service.NamespaceService;
import org.example.service.RpcProxyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestApiController {

    private final InstanceService instanceService;
    private final RpcProxyService rpcProxyService;
    private final NamespaceService namespaceService;
    private final NamingServiceManager namingServiceManager;

    public TestApiController(InstanceService instanceService,
                             RpcProxyService rpcProxyService,
                             NamespaceService namespaceService,
                             NamingServiceManager namingServiceManager) {
        this.instanceService = instanceService;
        this.rpcProxyService = rpcProxyService;
        this.namespaceService = namespaceService;
        this.namingServiceManager = namingServiceManager;
    }

    /**
     * 列出 Nacos 全部命名空间，供页面顶部的命名空间选择使用；
     * defaultNamespace 为页面初始选中项（test-console.nacos.namespace > env > public）。
     */
    @GetMapping("/namespaces")
    public Map<String, Object> namespaces() {
        List<NamespaceInfo> namespaces = namespaceService.listNamespaces();
        String preferred = namingServiceManager.defaultNamespace();
        String defaultNamespace = namespaces.stream()
                .map(NamespaceInfo::id)
                .anyMatch(preferred::equals) ? preferred : namespaces.get(0).id();
        return Map.of(
                "defaultNamespace", defaultNamespace,
                "namespaces", namespaces);
    }

    /**
     * 根据 appid 从 Nacos 指定命名空间查询全部健康实例；namespace 缺省时使用默认命名空间。
     */
    @GetMapping("/instances")
    public List<InstanceInfo> instances(@RequestParam("appId") String appId,
                                        @RequestParam(value = "namespace", required = false) String namespace) {
        return instanceService.listInstances(requireText(appId, "appId 不能为空"), namespace);
    }

    /**
     * 从选中实例拉取 schema，返回该 appid 的接口列表及请求模板。
     */
    @GetMapping("/methods")
    public List<MethodInfo> methods(@RequestParam("appId") String appId,
                                    @RequestParam("host") String host,
                                    @RequestParam("port") int port) {
        return rpcProxyService.listMethods(requireText(appId, "appId 不能为空"), host, port);
    }

    /**
     * 向选中实例的指定接口发起调用。
     */
    @PostMapping("/invoke")
    public InvokeResult invoke(@RequestBody InvokeRequest request) {
        return rpcProxyService.invoke(
                requireText(request.appId(), "appId 不能为空"),
                requireText(request.host(), "host 不能为空"),
                request.port(),
                requireText(request.method(), "method 不能为空"),
                request.request() == null ? "{}" : request.request());
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
