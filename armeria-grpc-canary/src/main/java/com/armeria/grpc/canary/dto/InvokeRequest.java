package com.armeria.grpc.canary.dto;

/**
 * 发起调用请求。
 *
 * @param appId   服务 appid（Nacos 服务名）
 * @param host    目标实例 IP
 * @param port    目标实例端口
 * @param method  接口方法名
 * @param request 请求 JSON 文本
 */
public record InvokeRequest(String appId, String host, int port, String method, String request) {
}
