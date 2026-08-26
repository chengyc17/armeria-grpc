package org.example.client;

import com.example.orderservice.CreateOrderRequest;
import com.example.orderservice.CreateOrderResponse;
import com.example.orderservice.GetOrderRequest;
import com.example.orderservice.GetOrderResponse;
import com.example.orderservice.OrderServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订单服务消费方：通过 Nacos 注册中心发现 example-server 并发起 gRPC 调用。
 *
 * <p>{@code orderStub} 由 armeria-server 的 {@code GrpcClientBeanPostProcessor}
 * 以字段注入方式创建：从 stub 类反推 appId（100004337），向 Nacos 查询健康实例，
 * 构建 {@code gproto+http://ip:port/100004337/api} 的客户端；实例上线/下线时会自动重建并刷新。
 *
 * <p>每次调用时，{@code ClientContextClientInterceptor} 会自动携带本进程的 appId
 * （来自本进程 ProductService proto 的 app_id，此处为 100004336）及 IP/机房等信息到 gRPC 请求头，
 * 服务端据此做黑白名单等治理决策。
 */
@Service
public class OrderClientService {

    private static final Logger log = LoggerFactory.getLogger(OrderClientService.class);

    private static final String ORDER_SERVICE_APP_ID = "100004337";

    /** 由 GrpcClientBeanPostProcessor 自动注入（必须字段注入，构造器执行时 stub 尚未创建）。 */
    private OrderServiceGrpc.OrderServiceBlockingStub orderStub;

    /** 是否已经从 Nacos 发现可用实例并创建了 stub。 */
    public boolean isReady() {
        return orderStub != null;
    }

    public CreateOrderResponse createOrder(long userId, List<Long> productIds) {
        log.debug("createOrder via Nacos: userId={}, productIds={}", userId, productIds);
        return requireStub().createOrder(CreateOrderRequest.newBuilder()
                .setUserId(userId)
                .addAllProductIds(productIds)
                .build());
    }

    public GetOrderResponse getOrder(long orderId) {
        log.debug("getOrder via Nacos: orderId={}", orderId);
        return requireStub().getOrder(GetOrderRequest.newBuilder()
                .setOrderId(orderId)
                .build());
    }

    private OrderServiceGrpc.OrderServiceBlockingStub requireStub() {
        OrderServiceGrpc.OrderServiceBlockingStub stub = orderStub;
        if (stub == null) {
            throw new IllegalStateException(
                    "order server (appId=" + ORDER_SERVICE_APP_ID + ") instance not available from Nacos yet, retrying...");
        }
        return stub;
    }
}
