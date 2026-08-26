package org.example.client;

import com.example.productservice.CreateProductRequest;
import com.example.productservice.CreateProductResponse;
import com.example.productservice.GetProductRequest;
import com.example.productservice.GetProductResponse;
import com.example.productservice.Product;
import com.example.productservice.ProductServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.example.rpc.annotation.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 商品服务 gRPC 实现（内存版示例），由 example-client 提供并注册到 Nacos。
 *
 * <p>由 {@code @GrpcService} 标记，armeria-server 自动配置在启动时会找到它，
 * 读取 {@code product_service.proto} 中 {@code option (app_id) = "100004336"}，
 * 以该 appId 将当前实例注册到 Nacos；该 appId 同时作为本进程调用其他服务时的
 * {@code x-client-app-id} 调用方标识。
 */
@GrpcService
public class ProductImpl extends ProductServiceGrpc.ProductServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ProductImpl.class);

    private final ConcurrentMap<Long, Product> products = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong();

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<GetProductResponse> responseObserver) {
        Product product = products.get(request.getProductId());
        if (product == null) {
            log.warn("getProduct -> product not found: {}", request.getProductId());
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("product not found: " + request.getProductId())
                    .asRuntimeException());
            return;
        }

        log.info("getProduct -> productId={}, name={}, price={}, category={}",
                product.getId(), product.getName(), product.getPrice(), product.getCategory());
        responseObserver.onNext(GetProductResponse.newBuilder()
                .setProduct(product)
                .setMessage("OK")
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void createProduct(CreateProductRequest request, StreamObserver<CreateProductResponse> responseObserver) {
        long productId = idGenerator.incrementAndGet();
        Product product = Product.newBuilder()
                .setId(productId)
                .setName(request.getName())
                .setPrice(request.getPrice())
                .setCategory(request.getCategory())
                .build();
        products.put(productId, product);

        log.info("createProduct -> productId={}, name={}, price={}, category={}",
                productId, request.getName(), request.getPrice(), request.getCategory());

        responseObserver.onNext(CreateProductResponse.newBuilder()
                .setSuccess(true)
                .setProductId(productId)
                .build());
        responseObserver.onCompleted();
    }
}
