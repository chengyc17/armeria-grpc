package org.example.service;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroupException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.example.config.GatewayProperties;
import org.example.exception.ServiceUnavailableException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 网关核心转发服务：/{appId}/api/{method} → 从 Nacos 选中的后端实例。
 *
 * <p>全程非阻塞 + 流式转发：请求体与响应体都不聚合到内存，不占用阻塞线程，
 * 适合高并发场景。每个 appId 复用同一个 {@link WebClient}，实例变化由
 * {@link NacosEndpointGroup} 动态感知，无需重建客户端。</p>
 */
@Service
public class GatewayService implements HttpService {

    private static final Pattern APP_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern METHOD_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final NacosInstanceRegistry registry;
    private final GatewayProperties properties;
    private final ClientFactory clientFactory;
    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    public GatewayService(NacosInstanceRegistry registry, GatewayProperties properties) {
        this.registry = registry;
        this.properties = properties;
        this.clientFactory = ClientFactory.builder()
                .connectTimeoutMillis(properties.getProxy().getConnectTimeoutMs())
                .idleTimeoutMillis(properties.getProxy().getIdleTimeoutMs())
                .maxNumEventLoopsPerEndpoint(properties.getProxy().getMaxNumConnectionsPerEndpoint())
                .build();
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        String appId;
        try {
            appId = requireValid(ctx.pathParam("appId"), APP_ID_PATTERN, "appId 不合法");
            requireValid(ctx.pathParam("method"), METHOD_PATTERN, "method 不合法");
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // 空实例快速路径：避免走到异步失败再映射
        if (registry.snapshot(appId).instances().isEmpty()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "服务没有可用的健康实例");
        }

        final WebClient client;
        try {
            client = client(appId);
        } catch (ServiceUnavailableException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "服务不可用: " + e.getMessage());
        }

        // 原样流式转发：保留路径、查询参数与全部请求头
        try {
            return client.execute(req).recover(this::toError);
        } catch (RuntimeException e) {
            return toError(e);
        }
    }

    /**
     * 每个 appId 缓存一个 WebClient；底层端点组是动态的，实例变更无需重建客户端。
     */
    private WebClient client(String appId) {
        return clients.computeIfAbsent(appId, id -> {
            NacosEndpointGroup group = registry.endpointGroup(id);
            return WebClient.builder(SessionProtocol.HTTP, group)
                    .factory(clientFactory)
                    .responseTimeoutMillis(properties.getProxy().getResponseTimeoutMs())
                    .maxResponseLength(properties.getProxy().getMaxResponseLength())
                    .build();
        });
    }

    private HttpResponse toError(Throwable cause) {
        // Armeria 可能把底层异常包装在 UnprocessedRequestException 等异常里，沿 cause 链查找
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof EndpointGroupException) {
                return error(HttpStatus.SERVICE_UNAVAILABLE, "服务没有可用的健康实例");
            }
            if (current instanceof ResponseTimeoutException) {
                return error(HttpStatus.GATEWAY_TIMEOUT, "转发到后端超时");
            }
        }
        return error(HttpStatus.BAD_GATEWAY, "转发失败: " + String.valueOf(cause.getMessage()));
    }

    private static String requireValid(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(message + ": " + value);
        }
        return value;
    }

    private static HttpResponse error(HttpStatus status, String message) {
        return HttpResponse.of(status, MediaType.PLAIN_TEXT_UTF_8, message);
    }
}