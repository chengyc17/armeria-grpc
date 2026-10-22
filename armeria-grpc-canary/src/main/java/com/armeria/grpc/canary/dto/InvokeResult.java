package com.armeria.grpc.canary.dto;

import java.util.Map;

/**
 * 调用结果：目标实例返回的 HTTP 状态、响应头与响应体。
 * 响应体为 JSON 对象时按 JsonNode 返回，否则原样返回文本。
 */
public record InvokeResult(
        int status,
        Map<String, String> headers,
        Object body,
        long durationMs) {
}
