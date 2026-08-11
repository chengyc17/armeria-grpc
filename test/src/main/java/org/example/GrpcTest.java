package org.example;

import com.example.userservice.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.example.rpc.annotation.GrpcService;

@GrpcService
public class GrpcTest extends UserServiceGrpc.UserServiceImplBase {

    @Override
    public void getUser(GetUserRequest request, StreamObserver<GetUserResponse> responseObserver) {
        long userId = request.getUserId();
        System.out.println("getUser: userId = " + userId);

        if (userId <= 0) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("userId must be positive: " + userId)
                    .asRuntimeException());
            return;
        }

        // 演示：按 userId 构造一个用户返回；真实场景应查询数据源
        User user = User.newBuilder()

                .setId(userId)
                .setUsername("user-" + userId)
                .setEmail("user-" + userId + "@example.com")
                .setAddress(User.Address.newBuilder()
                        .setCountry("CN")
                        .setCity("Shanghai")
                        .setDetail("somewhere")
                        .build())
                .addTags("default")
                .build();

        responseObserver.onNext(GetUserResponse.newBuilder()
                .setUser(user)
                .setMessage("ok")
                .build());
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
