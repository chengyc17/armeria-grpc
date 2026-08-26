package org.example.client;

import com.example.productservice.Category;
import com.example.productservice.CreateProductRequest;
import com.example.productservice.CreateProductResponse;
import com.example.productservice.GetProductRequest;
import com.example.productservice.GetProductResponse;
import com.example.productservice.Product;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductImplTest {

    private final ProductImpl service = new ProductImpl();

    @Test
    void createThenGetProduct() {
        AtomicReference<CreateProductResponse> created = new AtomicReference<>();
        service.createProduct(CreateProductRequest.newBuilder()
                .setName("iPhone 17")
                .setPrice(6999.0)
                .setCategory(Category.CATEGORY_ELECTRONICS)
                .build(), collecting(created));

        assertTrue(created.get().getSuccess());
        long productId = created.get().getProductId();

        AtomicReference<GetProductResponse> got = new AtomicReference<>();
        service.getProduct(GetProductRequest.newBuilder().setProductId(productId).build(), collecting(got));

        Product product = got.get().getProduct();
        assertNotNull(product);
        assertEquals(productId, product.getId());
        assertEquals("iPhone 17", product.getName());
        assertEquals(6999.0, product.getPrice());
        assertEquals(Category.CATEGORY_ELECTRONICS, product.getCategory());
    }

    @Test
    void getMissingProductReturnsNotFound() {
        AtomicReference<Throwable> error = new AtomicReference<>();
        service.getProduct(GetProductRequest.newBuilder().setProductId(999L).build(), errorCollecting(error));

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
