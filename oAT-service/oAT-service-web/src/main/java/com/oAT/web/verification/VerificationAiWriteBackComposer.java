package com.oAT.web.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.oAT.web.common.UtilJson;
import com.oAT.web.verification.model.VerificationModels.AcceptanceCriterion;
import com.oAT.web.verification.model.VerificationModels.Finding;
import com.oAT.web.verification.model.VerificationModels.TestcaseProjection;
import com.oAT.web.verification.model.VerificationModels.TraceLink;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class VerificationAiWriteBackComposer {
    private static final int MAX_CONTEXT_CHARS = 16_000;

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的软件质量协作 AI，负责把“需求一致性分析发现”转成接收方可直接处理的回写内容。

            工作边界：
            1. 只能基于输入中的发现项、验收标准、测试用例、追溯关系和依据生成内容，不能编造不存在的系统、接口、用例、Bug 或源码位置。
            2. 回写内容必须让产品、测试或开发接收后可以直接判断要做什么、为什么做、验收条件是什么。
            3. 如果依据不足，必须明确写出“需要补充的依据”，不要把不确定内容包装成确定结论。
            4. 平台不会使用常规规则替你生成回写内容；你必须完成结构化归纳和可执行表达。

            只允许输出严格 JSON，不要 Markdown，不要解释文本。JSON 结构必须如下：
            {
              "title": "可直接作为 Bug/任务标题的短标题",
              "targetRole": "PRODUCT|TEST|DEVELOPMENT|CROSS",
              "priority": "P0|P1|P2|P3",
              "summary": "问题摘要，说明发现了什么",
              "impact": "影响范围或风险",
              "evidence": ["依据1", "依据2"],
              "expectedAction": "接收方需要执行的动作",
              "acceptanceCriteria": ["处理完成后的验收条件1", "处理完成后的验收条件2"],
              "suggestedComment": "可直接粘贴到外部平台评论区的完整说明",
              "confidence": 0.0
            }
            """;

    private final AiGateway aiGateway;

    public VerificationAiWriteBackComposer(AiGateway aiGateway) {
        this.aiGateway = aiGateway;
    }

    public String compose(WriteBackInput input) {
        if (!aiGateway.isAvailable()) {
            throw new IllegalStateException("AI服务不可用，无法生成回写内容");
        }
        String response = aiGateway.chat(SYSTEM_PROMPT, buildUserMessage(input));
        if (!StringUtils.hasText(response)) {
            throw new IllegalStateException("AI没有返回回写内容");
        }
        return toMarkdown(parse(response));
    }

    private WriteBackDraft parse(String response) {
        try {
            JsonNode root = UtilJson.getObjectMapper().readTree(stripFence(response));
            String title = text(root, "title");
            String summary = text(root, "summary");
            String expectedAction = text(root, "expectedAction");
            String suggestedComment = text(root, "suggestedComment");
            if (!StringUtils.hasText(title) || !StringUtils.hasText(summary)
                    || !StringUtils.hasText(expectedAction) || !StringUtils.hasText(suggestedComment)) {
                throw new IllegalArgumentException("AI回写结果缺少 title、summary、expectedAction 或 suggestedComment");
            }
            return new WriteBackDraft(title,
                    normalizeRole(text(root, "targetRole")),
                    normalizePriority(text(root, "priority")),
                    summary,
                    text(root, "impact"),
                    textList(root.path("evidence")),
                    expectedAction,
                    textList(root.path("acceptanceCriteria")),
                    suggestedComment,
                    confidence(root.path("confidence").asDouble(0.7)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI回写返回格式不合法，请重试或检查模型配置", e);
        }
    }

    private String toMarkdown(WriteBackDraft draft) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("### ").append(draft.title()).append("\n\n");
        markdown.append("- 接收方：").append(roleText(draft.targetRole())).append('\n');
        markdown.append("- 优先级：").append(draft.priority()).append('\n');
        markdown.append("- AI 置信度：").append(String.format(Locale.ROOT, "%.2f", draft.confidence())).append("\n\n");
        markdown.append("#### 问题摘要\n").append(draft.summary()).append("\n\n");
        if (StringUtils.hasText(draft.impact())) {
            markdown.append("#### 影响 / 风险\n").append(draft.impact()).append("\n\n");
        }
        appendList(markdown, "依据", draft.evidence());
        markdown.append("#### 期望处理动作\n").append(draft.expectedAction()).append("\n\n");
        appendList(markdown, "验收条件", draft.acceptanceCriteria());
        markdown.append("#### 可直接发送给对方的说明\n").append(draft.suggestedComment()).append('\n');
        return markdown.toString();
    }

    private String buildUserMessage(WriteBackInput input) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请基于以下真实资料生成可直接回写给对方处理的内容。\n\n");
        prompt.append("【回写目标】\n");
        prompt.append("目标角色: ").append(value(input.targetRole())).append('\n');
        prompt.append("外部链接: ").append(value(input.externalUrl())).append('\n');
        prompt.append("用户补充说明: ").append(value(input.userNote())).append("\n\n");

        prompt.append("【发现项】\n");
        Finding finding = input.finding();
        prompt.append("类型: ").append(value(finding.findingType())).append('\n');
        prompt.append("视角: ").append(finding.perspective()).append('\n');
        prompt.append("严重级别: ").append(finding.severity()).append('\n');
        prompt.append("结论: ").append(finding.verdict()).append('\n');
        prompt.append("标题: ").append(value(finding.title())).append('\n');
        prompt.append("描述: ").append(value(finding.description())).append('\n');
        prompt.append("建议: ").append(value(finding.suggestion())).append('\n');
        prompt.append("依据等级: ").append(finding.evidenceLevel()).append('\n');
        prompt.append("依据: ").append(json(finding.evidence())).append("\n\n");

        prompt.append("【关联验收标准】\n");
        if (input.criterion() == null) {
            prompt.append("(未找到关联验收标准)\n\n");
        } else {
            AcceptanceCriterion criterion = input.criterion();
            prompt.append("需求Key: ").append(value(criterion.requirementKey())).append('\n');
            prompt.append("验收标准Key: ").append(value(criterion.acKey())).append('\n');
            prompt.append("标题: ").append(value(criterion.title())).append('\n');
            prompt.append("内容: ").append(value(criterion.content())).append('\n');
            prompt.append("来源: ").append(value(criterion.sourceLocator())).append("\n\n");
        }

        prompt.append("【关联测试用例】\n");
        if (input.testcases().isEmpty()) {
            prompt.append("(无关联测试用例)\n\n");
        } else {
            for (TestcaseProjection testcase : input.testcases()) {
                prompt.append("- ").append(value(testcase.externalKey())).append(" / ").append(value(testcase.title())).append('\n');
                prompt.append("  步骤: ").append(value(testcase.steps())).append('\n');
                prompt.append("  预期: ").append(value(testcase.expected())).append('\n');
            }
            prompt.append('\n');
        }

        prompt.append("【关联追溯依据】\n");
        if (input.traceLinks().isEmpty()) {
            prompt.append("(无关联追溯依据)\n\n");
        } else {
            for (TraceLink link : input.traceLinks()) {
                prompt.append("- ").append(link.targetType()).append(" / ").append(value(link.targetId()))
                        .append(" / ").append(link.relationType())
                        .append(" / 置信度 ").append(link.confidence())
                        .append(" / 依据等级 ").append(link.evidenceLevel()).append('\n');
                prompt.append("  依据: ").append(json(link.evidence())).append('\n');
            }
            prompt.append('\n');
        }

        prompt.append("""
                输出要求：
                - 标题要短，可直接成为外部 Bug/任务标题。
                - expectedAction 必须明确接收方下一步动作。
                - acceptanceCriteria 必须是处理完成后可验证的条件。
                - suggestedComment 必须是可直接粘贴给对方的完整中文说明。
                """);
        return truncate(prompt.toString(), MAX_CONTEXT_CHARS);
    }

    private void appendList(StringBuilder markdown, String title, List<String> items) {
        if (items == null || items.isEmpty()) return;
        markdown.append("#### ").append(title).append('\n');
        for (String item : items) {
            if (StringUtils.hasText(item)) markdown.append("- ").append(item.trim()).append('\n');
        }
        markdown.append('\n');
    }

    private List<String> textList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) return StringUtils.hasText(node.asText()) ? List.of(node.asText().trim()) : List.of();
        return UtilJson.getObjectMapper().convertValue(node, UtilJson.getObjectMapper().getTypeFactory()
                .constructCollectionType(List.class, String.class));
    }

    private String stripFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (firstLine > 0 && end > firstLine) return trimmed.substring(firstLine + 1, end).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        return start >= 0 && end > start ? trimmed.substring(start, end + 1) : trimmed;
    }

    private String text(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (value.isMissingNode() || value.isNull()) return "";
        return value.isTextual() ? value.asText().trim() : value.toString();
    }

    private String normalizeRole(String value) {
        String normalized = value(value).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PRODUCT", "TEST", "DEVELOPMENT", "CROSS" -> normalized;
            default -> "CROSS";
        };
    }

    private String normalizePriority(String value) {
        String normalized = value(value).trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "P0", "P1", "P2", "P3" -> normalized;
            case "CRITICAL" -> "P0";
            case "HIGH" -> "P1";
            case "LOW", "INFO" -> "P3";
            default -> "P2";
        };
    }

    private String roleText(String value) {
        return switch (value) {
            case "PRODUCT" -> "产品";
            case "TEST" -> "测试";
            case "DEVELOPMENT" -> "开发";
            default -> "综合";
        };
    }

    private double confidence(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String json(Object value) {
        try {
            return UtilJson.getObjectMapper().writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "";
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int max) {
        String safe = value(value);
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    public record WriteBackInput(
            Finding finding,
            AcceptanceCriterion criterion,
            List<TestcaseProjection> testcases,
            List<TraceLink> traceLinks,
            String targetRole,
            String externalUrl,
            String userNote) {
    }

    private record WriteBackDraft(
            String title,
            String targetRole,
            String priority,
            String summary,
            String impact,
            List<String> evidence,
            String expectedAction,
            List<String> acceptanceCriteria,
            String suggestedComment,
            double confidence) {
    }
}
