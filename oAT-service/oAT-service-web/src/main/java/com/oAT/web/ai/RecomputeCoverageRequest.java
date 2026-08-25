package com.oAT.web.ai;

import java.util.List;

/**
 * {@code POST /api/ai-tools/coverage/recompute} 请求体。给定变更符号列表，触发增量覆盖率影响面重算。
 */
public record RecomputeCoverageRequest(String projectId, String baselineId, List<String> changedSymbols) {
}
