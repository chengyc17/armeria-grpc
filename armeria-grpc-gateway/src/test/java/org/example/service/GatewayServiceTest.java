package org.example.service;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.Server;
import org.example.config.GatewayProperties;
import org.example.support.FakeNamingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GatewayService 端到端测试：真实起两个 Armeria Server（后端 + 网关），
 * 验证流式转发、空实例 503、参数校验 400、后端超时 504。
 */
class GatewayServiceTest {

    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        servers.forEach(Server::close);
        servers.clear();
    }

    @Test
    void forwardsRequestToBackendInstance() throws Exception {
        Server backend = startServer(Server.builder()
                .http(0)
                .service("/{appId}/api/{method}", (ctx, req) -> HttpResponse.of(
                        HttpStatus.OK, MediaType.JSON_UTF_8,
                        "{\"echo\":\"" + ctx.pathParam("appId") + "/" + ctx.pathParam("method") + "\"}"))
                .build());
        GatewayProperties properties = properties(5000);
        Server gateway = startGateway(properties, instances(backend));

        AggregatedHttpResponse response = call(gateway, "/order/api/pay", "{\"amount\":1}");

        assertEquals(HttpStatus.OK, response.status());
        assertTrue(response.contentUtf8().contains("order/pay"));
    }

    @Test
    void returns503WhenNoHealthyInstance() throws Exception {
        GatewayProperties properties = properties(5000);
        Server gateway = startGateway(properties, List.of());

        AggregatedHttpResponse response = call(gateway, "/order/api/pay", "{\"amount\":1}");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status());
    }

    @Test
    void returns400ForInvalidAppId() throws Exception {
        GatewayProperties properties = properties(5000);
        Server gateway = startGateway(properties, List.of());

        AggregatedHttpResponse response = call(gateway, "/bad!/api/pay", "{}");

        assertEquals(HttpStatus.BAD_REQUEST, response.status());
    }

    @Test
    void returns504WhenBackendTimesOut() throws Exception {
        Server backend = startServer(Server.builder()
                .http(0)
                .service("/{appId}/api/{method}", (ctx, req) ->
                        HttpResponse.delayed(HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "late"),
                                Duration.ofSeconds(3)))
                .build());
        GatewayProperties properties = properties(500);
        Server gateway = startGateway(properties, instances(backend));

        AggregatedHttpResponse response = call(gateway, "/order/api/pay", "{}");

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.status());
    }

    private Server startGateway(GatewayProperties properties, List<Instance> instances) throws Exception {
        FakeNamingService naming = new FakeNamingService();
        naming.defaultInstances(instances);
        NacosInstanceRegistry registry = new NacosInstanceRegistry(naming.proxy(), properties);
        GatewayService gatewayService = new GatewayService(registry, properties);
        return startServer(Server.builder()
                .http(0)
                .service("/{appId}/api/{method}", gatewayService)
                .build());
    }

    private Server startServer(Server server) throws Exception {
        server.start().join();
        servers.add(server);
        return server;
    }

    private GatewayProperties properties(int responseTimeoutMs) {
        GatewayProperties properties = new GatewayProperties();
        properties.getProxy().setConnectTimeoutMs(2000);
        properties.getProxy().setResponseTimeoutMs(responseTimeoutMs);
        return properties;
    }

    private List<Instance> instances(Server backend) {
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(backend.activeLocalPort());
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setWeight(1.0);
        return List.of(instance);
    }

    private AggregatedHttpResponse call(Server gateway, String path, String body) {
        WebClient client = WebClient.of("http://127.0.0.1:" + gateway.activeLocalPort());
        HttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST, path), HttpData.ofUtf8(body));
        return response.aggregate().join();
    }
}