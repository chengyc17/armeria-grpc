package org.example;

import com.example.userservice222.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.example.rpc.annotation.GrpcService;

@GrpcService
public class GrpcTest extends UserServiceGrpc.UserServiceImplBase {

    private com.example.userservice.UserServiceGrpc.UserServiceBlockingStub userServiceStub;

    @Override
    public void getUser(GetUserRequest request, StreamObserver<GetUserResponse> responseObserver) {
        com.example.userservice.GetUserRequest r =
                com.example.userservice.GetUserRequest.newBuilder().setUserId(223333).build();
        com.example.userservice.GetUserResponse res = userServiceStub.getUser(r);
        System.out.println(res);
        responseObserver.onNext(GetUserResponse.newBuilder().setMessage(res.getMessage()).build());
        responseObserver.onCompleted();
    }

    @Override
    public void createUser(CreateUserRequest request, StreamObserver<CreateUserResponse> responseObserver) {
        String username = request.getUsername();
        String email = request.getEmail();
        Gender gender = request.getGender();
        System.out.println("createUser: username = " + username + ", email = " + email + ", gender = " + gender);

        if (username == null || username.isBlank() || email == null || email.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("username and email are required")
                    .asRuntimeException());
            return;
        }

        // 演示：用当前时间戳作为新用户 id；真实场景应写入数据库并返回主键
        long newUserId = System.currentTimeMillis();
        responseObserver.onNext(CreateUserResponse.newBuilder()
                .setSuccess(true)
                .setUserId(newUserId)
                .build());
        responseObserver.onCompleted();
    }
}
