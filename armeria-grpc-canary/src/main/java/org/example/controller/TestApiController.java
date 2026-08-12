package org.example.controller;

import org.example.dto.InstanceInfo;
import org.example.dto.InvokeRequest;
import org.example.dto.InvokeResult;
import org.example.dto.MethodInfo;
import org.example.service.InstanceService;
import org.example.service.RpcProxyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/test")
public class TestApiController {

    private final InstanceService instanceService;
    private final RpcProxyService rpcProxyService;

    public TestApiController(InstanceService instanceService, RpcProxyService rpcProxyService) {
        this.instanceService = instanceService;
        this.rpcProxyService = rpcProxyService;
    }

    /**
     * 根据 appid 从 Nacos 查询全部健康实例。
     */
    @GetMapping("/instances")
    public List<InstanceInfo> instances(@RequestParam("appId") String appId) {
        return instanceService.listInstances(requireText(appId, "appId 不能为空"));
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
