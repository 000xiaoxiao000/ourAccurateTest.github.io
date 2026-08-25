package com.oAT.web.ai;

/**
 * {@code POST /api/ai-tools/tests/run} 请求体。触发测试执行投影（oAT 不直跑测试套件，
 * 而是复用既有投影能力）。
 */
public record RunTestsRequest(String projectId, String baselineId, String scope) {
}
