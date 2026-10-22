package com.armeria.grpc.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 统一配置解析器：YAML / JSON / properties / auto（按 dataId 后缀或内容识别）。
 * 所有 ConfigSource 实现复用同一套解析逻辑，保证各配置中心行为一致。
 */
public final class ConfigParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigParser() {
    }

    /**
     * @param format  AUTO / PROPERTIES / YAML / JSON
     * @param dataId  用于 AUTO 模式按后缀识别；可为 null
     * @param content 原始配置内容；null 或空白返回 empty
     */
    public static ConfigSnapshot parse(Format format, String dataId, String content) {
        if (content == null || content.isBlank()) {
            return ConfigSnapshot.empty();
        }
        return switch (format) {
            case YAML -> parseYaml(content);
            case JSON -> parseJson(content);
            case PROPERTIES -> parseProperties(content);
            case AUTO -> parseAuto(dataId, content);
        };
    }

    private static ConfigSnapshot parseAuto(String dataId, String content) {
        if (dataId != null) {
            String lower = dataId.toLowerCase();
            if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
                return parseYaml(content);
            }
            if (lower.endsWith(".json")) {
                return parseJson(content);
            }
        }
        if (isYamlLike(content)) {
            return parseYaml(content);
        }
        return parseProperties(content);
    }

    private static boolean isYamlLike(String content) {
        return content.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .findFirst()
                .map(line -> line.matches("^[A-Za-z0-9_.\\-]+:\\s.*$"))
                .orElse(false);
    }

    private static ConfigSnapshot parseYaml(String content) {
        Object loaded = new Yaml().load(content);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "YAML config must be a mapping, but got: "
                            + (loaded == null ? "null" : loaded.getClass().getName()));
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        Map<String, String> flat = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            structured.put(key, value);
            flatten(key, value, flat);
        }
        return new ConfigSnapshot(content, structured, flat);
    }

    private static ConfigSnapshot parseProperties(String content) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse properties config: " + e.getMessage(), e);
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        Map<String, String> flat = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name);
            structured.put(name, value);
            flat.put(name, value);
        }
        return new ConfigSnapshot(content, structured, flat);
    }

    private static ConfigSnapshot parseJson(String content) {
        try {
            JsonNode root = MAPPER.readTree(content);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("JSON config must be a JSON object");
            }
            Map<String, Object> structured = new LinkedHashMap<>();
            Map<String, String> flat = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                Object value = toJava(entry.getValue());
                structured.put(entry.getKey(), value);
                flatten(entry.getKey(), value, flat);
            });
            return new ConfigSnapshot(content, structured, flat);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON config: " + e.getMessage(), e);
        }
    }

    private static Object toJava(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), toJava(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(toJava(item)));
            return list;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.toString();
    }

    private static void flatten(String prefix, Object value, Map<String, String> flat) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                flatten(prefix + "." + entry.getKey(), entry.getValue(), flat);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), flat);
            }
        } else {
            flat.put(prefix, value == null ? "" : value.toString());
        }
    }

    public enum Format {
        AUTO,
        PROPERTIES,
        YAML,
        JSON
    }

    /** 一次解析的结果：原始内容 + 结构化视图 + 扁平视图（均不可变）。 */
    public record ConfigSnapshot(String rawContent, Map<String, Object> structured, Map<String, String> flat) {

        public static ConfigSnapshot empty() {
            return new ConfigSnapshot(null, Map.of(), Map.of());
        }

        public ConfigSnapshot {
            structured = Collections.unmodifiableMap(new LinkedHashMap<>(structured));
            flat = Collections.unmodifiableMap(new LinkedHashMap<>(flat));
        }
    }
}
