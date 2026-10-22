package com.armeria.grpc.gateway.exception;

/**
 * 服务不可用：Nacos 查询失败、没有健康实例等情况，映射为 HTTP 503。
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
