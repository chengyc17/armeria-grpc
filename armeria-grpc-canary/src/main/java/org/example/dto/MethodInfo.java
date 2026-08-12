package org.example.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 实例上的一个可调用接口。
 *
 * @param method   方法名，例如 getUser
 * @param path     完整调用路径，例如 /user-service/api/getUser
 * @param template 请求 JSON 模板（默认值）
 */
public record MethodInfo(String method, String path, JsonNode template) {
}
