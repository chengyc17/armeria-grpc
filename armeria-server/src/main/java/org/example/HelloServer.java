package org.example;

import com.example.api.HelloReply;
import com.example.api.HelloRequest;
import com.example.api.HelloServiceGrpc;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class HelloServer {

    private static final Logger logger = LoggerFactory.getLogger(HelloServer.class);

    public static void main(String[] args) throws Exception {
        // Create gRPC service implementation
        final HelloServiceGrpc.HelloServiceImplBase helloService = new HelloServiceGrpc.HelloServiceImplBase() {
            @Override
            public void sayHello(com.example.api.HelloRequest request,
                                 io.grpc.stub.StreamObserver<com.example.api.HelloReply> responseObserver) {
                String message = String.format("Hello, %s! Welcome to Armeria gRPC Server.",
                        request.getName());

                HelloReply reply = HelloReply.newBuilder()
                        .setMessage(message)
                        .build();

                responseObserver.onNext(reply);
                responseObserver.onCompleted();

                logger.info("Responded to client: {}", request.getName());
            }
        };

        // Build gRPC service
        GrpcService grpcService = GrpcService.builder()
                .addService(helloService)
                .enableUnframedRequests(true) // Enable HTTP/1.1 JSON requests
                .build();

        // Build and start server
        Server server = Server.builder()
                .http(8080)
                .service(grpcService)
                .build();

        server.start().join();

        logger.info("Server started at http://127.0.0.1:8080");
        logger.info("gRPC endpoint available at: http://127.0.0.1:8080/grpc");
        logger.info("Press Ctrl+C to stop.");

        server.closeOnJvmShutdown();
    }
}