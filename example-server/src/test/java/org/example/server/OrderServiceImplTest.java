package org.example.server;

import com.example.orderservice.CreateOrderRequest;
import com.example.orderservice.CreateOrderResponse;
import com.example.orderservice.GetOrderRequest;
import com.example.orderservice.GetOrderResponse;
import com.example.orderservice.Order;
import com.example.orderservice.OrderStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderServiceImplTest {

    private final OrderServiceImpl service = new OrderServiceImpl();

    @Test
    void createThenGetOrder() {
        AtomicReference<CreateOrderResponse> created = new AtomicReference<>();
        service.createOrder(CreateOrderRequest.newBuilder()
                .setUserId(10001L)
                .addAllProductIds(List.of(1L, 2L, 3L))
                .build(), collecting(created));

        assertTrue(created.get().getSuccess());
        long orderId = created.get().getOrderId();

        AtomicReference<GetOrderResponse> got = new AtomicReference<>();
        service.getOrder(GetOrderRequest.newBuilder().setOrderId(orderId).build(), collecting(got));

        Order order = got.get().getOrder();
        assertNotNull(order);
        assertEquals(orderId, order.getId());
        assertEquals(10001L, order.getUserId());
        assertEquals(OrderStatus.ORDER_STATUS_PENDING, order.getStatus());
        assertEquals(List.of(1L, 2L, 3L), order.getProductIdsList());
    }

    @Test
    void getMissingOrderReturnsNotFound() {
        AtomicReference<Throwable> error = new AtomicReference<>();
        service.getOrder(GetOrderRequest.newBuilder().setOrderId(999L).build(), errorCollecting(error));

        assertNotNull(error.get());
        assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(error.get()).getCode());
    }

    private static <T> StreamObserver<T> collecting(AtomicReference<T> result) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                result.set(value);
            }

            @Override
            public void onError(Throwable t) {
                throw new AssertionError("Unexpected error: " + t.getMessage(), t);
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    private static <T> StreamObserver<T> errorCollecting(AtomicReference<Throwable> error) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                throw new AssertionError("Unexpected value: " + value);
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
            }

            @Override
            public void onCompleted() {
                throw new AssertionError("Unexpected completion");
            }
        };
    }
}
