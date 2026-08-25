package com.oAT.web.ai;

import java.util.Map;

/**
 * 写/执行类 AI 工具端点的统一响应。ovanth 工具网关按 {@code success} 判断成败，
 * {@code data} 携带端点特定的结果字段。
 */
public record AiToolResult(boolean success, String message, Map<String, Object> data) {

    public static AiToolResult ok(String message, Map<String, Object> data) {
        return new AiToolResult(true, message, data == null ? Map.of() : data);
    }

    public static AiToolResult fail(String message) {
        return new AiToolResult(false, message, Map.of());
    }
}
