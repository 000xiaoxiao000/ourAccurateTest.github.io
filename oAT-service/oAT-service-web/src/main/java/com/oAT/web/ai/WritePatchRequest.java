package com.oAT.web.ai;

/**
 * {@code POST /api/ai-tools/write-patch} 请求体。
 *
 * <ul>
 *   <li>{@code projectId} / {@code assetId} / {@code patch} 必填。</li>
 *   <li>{@code replaceEntireContent=true}：{@code patch} 字段直接替换原资产内容（最稳，P0 主路径）。</li>
 *   <li>{@code replaceEntireContent=false}（默认）：按 unified diff 解析应用，见 {@link PatchApplier}。</li>
 *   <li>{@code baselineId} 可选；提供时补丁应用后触发该基线的静态图重投影（best-effort）。</li>
 * </ul>
 */
public record WritePatchRequest(String projectId, String assetId, String baselineId,
                                String patch, Boolean replaceEntireContent) {
}
