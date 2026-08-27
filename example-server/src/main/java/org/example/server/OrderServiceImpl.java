package org.example.server;

import com.example.orderservice.CreateOrderRequest;
import com.example.orderservice.CreateOrderResponse;
import com.example.orderservice.GetOrderRequest;
import com.example.orderservice.GetOrderResponse;
import com.example.orderservice.Order;
import com.example.orderservice.OrderServiceGrpc;
import com.example.orderservice.OrderStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.example.rpc.annotation.GrpcService;
import org.example.rpc.context.ClientContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单服务 gRPC 实现（内存版示例）。
 *
 * <p>由 {@code @GrpcService} 标记，armeria-server 自动配置会在启动时找到它，
 * 以应用身份 {@code appid=100004337}（来自 {@code META-INF/app.config}，与
 * {@code order_service.proto} 中 {@code option (app_id) = "100004337"} 一致）
 * 将当前实例注册到 Nacos。
 */
@GrpcService
public class OrderServiceImpl extends OrderServiceGrpc.OrderServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final ConcurrentMap<Long, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong();

    @Override
    public void createOrder(CreateOrderRequest request, StreamObserver<CreateOrderResponse> responseObserver) {
        long orderId = idGenerator.incrementAndGet();
        Order order = Order.newBuilder()
                .setId(orderId)
                .setUserId(request.getUserId())
                .setStatus(OrderStatus.ORDER_STATUS_PENDING)
                .setTotalAmount(0)
                .addAllProductIds(request.getProductIdsList())
                .build();
        orders.put(orderId, order);

        log.info("createOrder -> userId={}, productIds={}, orderId={}",
                request.getUserId(), request.getProductIdsList(), orderId);

        responseObserver.onNext(CreateOrderResponse.newBuilder()
                .setSuccess(true)
                .setOrderId(orderId)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<GetOrderResponse> responseObserver) {
        log.info(String.format("请求来自：client appid:%s, client ip: %s", ClientContextHolder.get().getAppId(),ClientContextHolder.get().getClientIp()));

        Order order = Order.newBuilder().setId(1L).setTotalAmount(200d).setUserId(22222L).build();

        log.info("getOrder -> orderId={}, status={}", order.getId(), order.getStatus());
        responseObserver.onNext(GetOrderResponse.newBuilder()
                .setOrder(order)
                .setMessage("OK")
                .build());
        responseObserver.onCompleted();
    }
}
