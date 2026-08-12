package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.InvokeResult;
import org.example.dto.MethodInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 与目标 gRPC 实例交互的代理服务。
 *
 * <p>复用 armeria-server 已有的能力：
 * <ul>
 *   <li>GET /rpc/schema 返回 {"appId/api/方法名": 请求模板}，用于枚举接口并生成请求 JSON；</li>
 *   <li>POST /{appId}/api/{方法名}（unframed HTTP/JSON）用于发起调用。</li>
 * </ul>
 */
@Service
public class RpcProxyService {

    private static final Logger log = LoggerFactory.getLogger(RpcProxyService.class);

    private static final Duration SCHEMA_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration INVOKE_TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 拉取实例 schema，过滤出当前 appid 的接口列表。
     */
    public List<MethodInfo> listMethods(String appId, String host, int port) {
        Map<String, JsonNode> schemas = fetchSchemas(host, port);
        String prefix = appId + "/api/";
        return schemas.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(entry -> new MethodInfo(
                        entry.getKey().substring(prefix.length()),
                        "/" + entry.getKey(),
                        entry.getValue()))
                .sorted(Comparator.comparing(MethodInfo::method))
                .toList();
    }

    /**
     * 向选中实例发起调用，返回目标实例的原始状态、响应头与响应体。
     */
    public InvokeResult invoke(String appId, String host, int port, String method, String requestJson) {
        try {
            objectMapper.readTree(requestJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("request 不是合法的 JSON: " + e.getMessage());
        }

        String url = baseUrl(host, port) + "/" + appId + "/api/" + method;
        log.info("Invoke {} -> {}", method, url);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(INVOKE_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            long startNanos = System.nanoTime();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> headers.put(name, String.join(",", values)));
            return new InvokeResult(response.statusCode(), headers, parseBody(response.body()), durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("调用实例失败: " + e.getMessage(), e);
        }
    }

    private Map<String, JsonNode> fetchSchemas(String host, int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(host, port) + "/rpc/schema"))
                    .timeout(SCHEMA_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("获取实例 schema 失败: HTTP " + response.statusCode()
                        + " -> " + truncate(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isObject()) {
                throw new IllegalStateException("实例 /rpc/schema 返回的不是 JSON 对象");
            }
            Map<String, JsonNode> schemas = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> schemas.put(entry.getKey(), entry.getValue()));
            return schemas;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取实例 schema 被中断", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法访问实例 schema: " + e.getMessage(), e);
        }
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return body;
        }
    }

    private String baseUrl(String host, int port) {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
