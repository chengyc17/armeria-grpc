package org.example.service;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.example.dto.InstanceInfo;
import org.example.exception.ServiceUnavailableException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 网关状态查看端点（与旧版 REST Controller 保持一致）。
 */
@Service
public class GatewayStatusService {

    private static final Pattern APP_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final NacosInstanceRegistry registry;

    public GatewayStatusService(NacosInstanceRegistry registry) {
        this.registry = registry;
    }

    /** GET /gateway/services/{appId}/instances：查看某个 appId 当前缓存的健康实例。 */
    public HttpResponse instances(ServiceRequestContext ctx, HttpRequest req) {
        String appId = ctx.pathParam("appId");
        if (appId == null || !APP_ID_PATTERN.matcher(appId).matches()) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8, "appId 不合法: " + appId);
        }
        try {
            List<InstanceInfo> instances = registry.snapshot(appId).instances();
            return HttpResponse.ofJson(instances);
        } catch (ServiceUnavailableException e) {
            return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8,
                    "查询 Nacos 失败: " + e.getMessage());
        }
    }

    /** GET /gateway/status：查看缓存状态：appId -> 实例数 / 更新时间。 */
    public HttpResponse status(ServiceRequestContext ctx, HttpRequest req) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        registry.snapshotMap().forEach((appId, snapshot) ->
                result.put(appId, Map.of(
                        "instanceCount", snapshot.instances().size(),
                        "updatedAt", snapshot.updatedAt().toString())));
        return HttpResponse.ofJson(result);
    }
}