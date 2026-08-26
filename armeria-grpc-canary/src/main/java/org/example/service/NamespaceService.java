package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.NamingServiceManager;
import org.example.config.TestConsoleProperties;
import org.example.dto.NamespaceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过 Nacos 服务端 Open API 拉取全部命名空间。
 *
 * <p>命名空间列表不在 {@code NamingService} 客户端 API 的能力范围内，需要直接调用服务端 HTTP 接口。
 * 依次尝试（从新到旧）：
 * <ul>
 *   <li>Nacos 3.x admin 接口 {@code /nacos/v3/admin/core/namespace/list}；</li>
 *   <li>Nacos 1.x/2.x console 接口 {@code /nacos/v1/console/namespaces}；</li>
 *   <li>Nacos 2.x console 接口 {@code /nacos/v2/console/namespace/list}。</li>
 * </ul>
 * 三种返回结构相近（code + data 数组），字段名做兼容解析。
 * 鉴权优先级：服务端 identity 头（Nacos 3.x admin 接口强制鉴权时的免登录通道），
 * 其次用户名密码登录换 accessToken；两者都没配置则匿名访问。
 */
@Service
public class NamespaceService {

    private static final Logger log = LoggerFactory.getLogger(NamespaceService.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final List<String> NAMESPACE_PATHS = List.of(
            "/nacos/v3/admin/core/namespace/list",
            "/nacos/v1/console/namespaces",
            "/nacos/v2/console/namespace/list");

    private final TestConsoleProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public NamespaceService(TestConsoleProperties properties) {
        this.properties = properties;
    }

    /**
     * 拉取全部命名空间；public 永远排在首位。
     */
    public List<NamespaceInfo> listNamespaces() {
        String token = loginToken();
        String lastError = null;
        for (String path : NAMESPACE_PATHS) {
            try {
                return ensurePublicFirst(fetchNamespaces(path, token));
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("Namespace endpoint {} failed: {}", path, lastError);
            }
        }
        throw new IllegalStateException("从 Nacos 获取命名空间列表失败: " + lastError);
    }

    private List<NamespaceInfo> fetchNamespaces(String path, String token) throws Exception {
        String url = baseUrl() + path;
        if (token != null) {
            url += "?accessToken=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .GET();
        // Nacos 3.x 的 admin 接口在 auth.admin.enabled=true 时强制鉴权，
        // 服务端 identity 头是唯一不需要用户名密码的通行方式。
        String identityKey = properties.getNacos().getIdentityKey();
        String identityValue = properties.getNacos().getIdentityValue();
        if (identityValue != null && !identityValue.isBlank() && identityKey != null && !identityKey.isBlank()) {
            builder.header(identityKey, identityValue);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + truncate(response.body()));
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("响应缺少 data 数组: " + truncate(response.body()));
        }
        Map<String, NamespaceInfo> result = new LinkedHashMap<>();
        for (JsonNode item : data) {
            String id = firstText(item, "namespace", "namespaceId");
            String name = firstText(item, "namespaceShowName", "namespaceName");
            // v1 console 中 public 的 id 为空串，归一化为 "public"
            if (id == null || id.isBlank()) {
                id = NamingServiceManager.PUBLIC_NAMESPACE;
            }
            if (name == null || name.isBlank() || name.equals(id)) {
                name = id;
            }
            result.putIfAbsent(id, new NamespaceInfo(id, name));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("返回的命名空间列表为空");
        }
        return new ArrayList<>(result.values());
    }

    /**
     * 确保 public 命名空间存在且排第一位，便于页面默认展示。
     */
    private List<NamespaceInfo> ensurePublicFirst(List<NamespaceInfo> namespaces) {
        NamespaceInfo publicInfo = null;
        List<NamespaceInfo> others = new ArrayList<>();
        for (NamespaceInfo info : namespaces) {
            if (NamingServiceManager.PUBLIC_NAMESPACE.equals(info.id())) {
                publicInfo = info;
            } else {
                others.add(info);
            }
        }
        List<NamespaceInfo> result = new ArrayList<>();
        if (publicInfo != null) {
            result.add(publicInfo);
        } else {
            result.add(new NamespaceInfo(NamingServiceManager.PUBLIC_NAMESPACE, NamingServiceManager.PUBLIC_NAMESPACE));
        }
        result.addAll(others);
        return result;
    }

    /**
     * 配置了用户名密码时，先登录换取 accessToken；失败则退化为匿名访问。
     */
    private String loginToken() {
        String username = properties.getNacos().getUsername();
        String password = properties.getNacos().getPassword();
        if (username == null || username.isBlank()) {
            return null;
        }
        try {
            String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password == null ? "" : password, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/nacos/v1/auth/users/login"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Nacos 登录失败: HTTP {}，将尝试匿名访问", response.statusCode());
                return null;
            }
            JsonNode token = objectMapper.readTree(response.body()).get("accessToken");
            return token == null || token.isNull() ? null : token.asText();
        } catch (Exception e) {
            log.warn("Nacos 登录异常，将尝试匿名访问: {}", e.getMessage());
            return null;
        }
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * serverAddr 可能是逗号分隔的集群地址，取第一个用于 HTTP 访问。
     */
    private String baseUrl() {
        String serverAddr = properties.getNacos().getServerAddr();
        String first = serverAddr == null ? "127.0.0.1:8848" : serverAddr.split(",")[0].trim();
        return first.startsWith("http") ? first : "http://" + first;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
