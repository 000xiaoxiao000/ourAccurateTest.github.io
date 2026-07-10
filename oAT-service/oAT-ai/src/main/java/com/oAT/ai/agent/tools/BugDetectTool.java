package com.oAT.ai.agent.tools;

import com.oAT.ai.agent.AgentContext;
import com.oAT.ai.agent.AgentDataProvider;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI代码Bug检测工具
 * 利用LLM对指定类/方法的源码进行智能分析，检测潜在Bug和代码缺陷
 *
 * <p>检测维度：</p>
 * <ul>
 *   <li>空指针风险 - 未做null检查的变量使用</li>
 *   <li>资源泄漏 - 未关闭的Stream/Connection</li>
 *   <li>并发问题 - 非线程安全的共享变量访问</li>
 *   <li>逻辑错误 - 条件判断遗漏、死代码等</li>
 *   <li>异常处理 - 吞掉异常、异常类型不匹配等</li>
 *   <li>性能问题 - 循环内重复计算、N+1查询模式等</li>
 * </ul>
 */
public class BugDetectTool {

    private static final Logger logger = LoggerFactory.getLogger(BugDetectTool.class);

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*)\\s*;");
    private static final Pattern PRIMARY_TYPE_PATTERN = Pattern.compile("(?m)\\b(?:public\\s+)?(?:class|interface|enum|record)\\s+([a-zA-Z_$][\\w$]*)\\b");

    private final AgentDataProvider dataProvider;

    public BugDetectTool(AgentDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Tool("对指定的Java类进行AI智能Bug检测；当用户要求检查代码Bug、源码缺陷、空指针、资源泄漏、并发问题时必须优先使用本工具")
    public String detectBugs(@P("要检测的Java类名，优先传入当前项目覆盖率/静态数据中的真实全限定名，也支持简单类名") String className) {
        if (className == null || className.trim().isEmpty()) {
            return "错误：请提供要检测的类名";
        }
        try {
            String requestedClassName = className.trim();
            String projectId = AgentContext.getCurrentProjectId();
            if (projectId == null) {
                return "错误：未找到项目上下文";
            }

            // 获取源码
            String sourceCode = dataProvider.getSourceCode(requestedClassName);
            if (sourceCode == null || sourceCode.trim().isEmpty()) {
                return "未找到类 " + requestedClassName + " 的源码，可能该类未被插桩或不在当前项目中。\n" +
                       "提示：请从当前项目的静态代码数据中选择真实类名或简单类名，不要使用示例/虚构包路径。";
            }

            String actualClassName = resolveActualClassName(sourceCode, requestedClassName);

            // 构建结构化的分析请求（返回给LLM进行分析）
            StringBuilder analysisRequest = new StringBuilder();
            analysisRequest.append("## 请分析以下Java类的源码，检测其中潜在的Bug和代码缺陷\n\n");
            analysisRequest.append("### 类名：").append(actualClassName).append("\n");
            if (!actualClassName.equals(requestedClassName)) {
                analysisRequest.append("### 用户输入：").append(requestedClassName).append("\n");
            }
            analysisRequest.append("\n");
            analysisRequest.append("```java\n").append(truncateSource(sourceCode)).append("\n```\n");
            analysisRequest.append("\n\n### 分析要求\n");
            analysisRequest.append("必须只基于上方源码中的真实 package/import/class/method 分析；禁止使用 com.example 等示例或虚构包路径，禁止把不存在于源码的类、方法、调用关系当成事实。\n\n");
            analysisRequest.append("请从以下维度逐一分析，给出具体发现（如果某维度无问题请说明\"未发现明显问题\"）：\n\n");
            analysisRequest.append("**1. 空指针风险** - 变量解引用前是否做了null检查？Optional/集合操作是否安全？\n");
            analysisRequest.append("**2. 资源泄漏** - Stream/Connection/InputStream等是否在finally/try-with-resources中关闭？\n");
            analysisRequest.append("**3. 并发问题** - 共享可变状态是否有同步保护？是否有竞态条件？\n");
            analysisRequest.append("**4. 逻辑错误** - 条件分支是否完整？是否有不可达代码？边界条件是否处理？\n");
            analysisRequest.append("**5. 异常处理** - 是否有空的catch块？异常信息是否丢失？异常类型是否精确？\n");
            analysisRequest.append("**6. 性能隐患** - 循环内是否有重复计算？是否有N+1查询？大对象是否合理回收？\n\n");
            analysisRequest.append("### 输出格式要求\n");
            analysisRequest.append("每个发现按以下格式输出：\n");
            analysisRequest.append("- 🔴🟡🟢 **[严重程度] [类别] 行号范围**: 具体描述\n");
            analysisRequest.append("  - 当前代码片段（引用原文）\n");
            analysisRequest.append("  - 风险说明\n");
            analysisRequest.append("  - 修复建议（给出修改后的代码示例）\n\n");
            analysisRequest.append("最后给出总结：共发现 X 个问题（严重 Y / 中等 Z / 建议 W），优先修复建议排序。");

            return analysisRequest.toString();
        } catch (Exception e) {
            logger.error("代码Bug检测失败", e);
            return "代码Bug检测失败：" + e.getMessage();
        }
    }

    @Tool("对指定的方法级别代码进行深度Bug检测，支持传入具体的源码片段")
    public String detectBugsInMethod(@P("类全限定名") String className,
                                      @P("方法名称") String methodName,
                                      @P("可选：自定义源码片段，如果不传则自动获取") String sourceCodeSnippet) {
        if (className == null || className.trim().isEmpty()) {
            return "错误：请提供要检测的类全限定名";
        }
        try {
            String projectId = AgentContext.getCurrentProjectId();
            if (projectId == null) {
                return "错误：未找到项目上下文";
            }

            String sourceCode = (sourceCodeSnippet != null && !sourceCodeSnippet.trim().isEmpty())
                    ? sourceCodeSnippet
                    : dataProvider.getSourceCode(className.trim());

            if (sourceCode == null || sourceCode.trim().isEmpty()) {
                return "未找到类 " + className + " 的源码";
            }

            String actualClassName = resolveActualClassName(sourceCode, className.trim());

            // 如果指定了方法名，尝试提取该方法
            String targetSource = sourceCode;
            if (methodName != null && !methodName.trim().isEmpty()) {
                String extracted = extractMethod(sourceCode, methodName.trim());
                if (extracted == null) {
                    logger.warn("未找到方法 {} 在类 {} 中，将分析整个类", methodName, className);
                } else {
                    targetSource = extracted;
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 方法级深度Bug检测\n\n");
            sb.append("**目标**: ").append(actualClassName);
            if (!actualClassName.equals(className.trim())) {
                sb.append("（用户输入：").append(className.trim()).append("）");
            }
            if (methodName != null) {
                sb.append(".").append(methodName);
            }
            sb.append("\n\n```java\n").append(truncateSource(targetSource, 3000)).append("\n```\n\n");
            sb.append("### 深度分析要求\n");
            sb.append("必须只基于上方源码中的真实 package/import/class/method 分析；禁止使用示例或虚构包路径。\n\n");
            sb.append("请对该代码进行逐行级别的深度分析：\n\n");
            sb.append("1. **控制流分析** - if/else/switch分支完整性，循环终止条件正确性\n");
            sb.append("2. **数据流分析** - 变量初始化顺序，未初始化使用，类型转换安全\n");
            sb.append("3. **API误用检测** - 集合操作的ConcurrentModificationException风险\n");
            sb.append("4. **边界条件** - 整数溢出、数组越界、除零风险\n");
            sb.append("5. **安全漏洞** - SQL注入/XSS/敏感信息泄露\n");
            sb.append("6. **编码规范** - 命名规范、魔法数字、过长方法\n\n");
            sb.append("输出格式：每个问题用表格形式展示 | 位置 | 严重度 | 问题 | 修复建议 |\n");

            return sb.toString();
        } catch (Exception e) {
            logger.error("方法级Bug检测失败", e);
            return "方法级Bug检测失败：" + e.getMessage();
        }
    }

    @Tool("批量检测多个类的Bug，生成汇总报告")
    public String batchDetectBugs(@P("类全限定名列表，逗号分隔") String classNames) {
        if (classNames == null || classNames.trim().isEmpty()) {
            return "错误：请提供要检测的类名列表（逗号分隔）";
        }
        try {
            String[] classes = classNames.split(",");
            StringBuilder sb = new StringBuilder();
            sb.append("## 批量代码Bug检测报告\n\n");
            sb.append("待检测类（").append(classes.length).append("个）：\n");

            int index = 0;
            for (String clazz : classes) {
                String cn = clazz.trim();
                if (!cn.isEmpty()) {
                    index++;
                    sb.append(index).append(". ").append(cn).append("\n");
                }
            }
            sb.append("\n以下内容由 detectBugs 逐类生成，请基于每段源码和真实包路径继续分析，最后生成一份汇总报告。\n");
            for (String clazz : classes) {
                String cn = clazz.trim();
                if (!cn.isEmpty()) {
                    sb.append("\n---\n\n");
                    sb.append(detectBugs(cn)).append("\n");
                }
            }
            sb.append("\n汇总报告需包含：\n");
            sb.append("- 各类的问题数量统计（按严重度分类）\n");
            sb.append("- TOP 10 最需关注的问题清单\n");
            sb.append("- 项目整体代码健康评分（满分10分）\n");
            sb.append("- 优先修复路线图\n");

            return sb.toString();
        } catch (Exception e) {
            logger.error("批量Bug检测失败", e);
            return "批量Bug检测失败：" + e.getMessage();
        }
    }

    @Tool("基于真实源码分析指定类或方法承载的业务逻辑/业务需求；当用户问某个方法相关业务需求、业务规则、业务逻辑时必须优先使用本工具")
    public String analyzeBusinessRequirement(@P("类全限定名或简单类名") String className,
                                             @P("方法名称，可选") String methodName) {
        if (className == null || className.trim().isEmpty()) {
            return "错误：请提供要分析的类名。不能使用示例类名或猜测类名。";
        }
        try {
            String sourceCode = dataProvider.getSourceCode(className.trim());
            if (sourceCode == null || sourceCode.trim().isEmpty()) {
                return "未找到类 " + className + " 的真实源码，无法分析业务需求。请确认类名或源码数据已上传/扫描；不要使用示例类名、示例包名或猜测内容回答。";
            }

            String actualClassName = resolveActualClassName(sourceCode, className.trim());
            String targetSource = sourceCode;
            if (methodName != null && !methodName.trim().isEmpty()) {
                String extracted = extractMethod(sourceCode, methodName.trim());
                if (extracted == null) {
                    return "已找到类 " + actualClassName + " 的源码，但未找到方法 " + methodName + "。请不要猜测该方法业务需求；请确认方法名后再分析。";
                }
                targetSource = extracted;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 基于真实源码的业务逻辑分析\n\n");
            sb.append("### 分析目标\n");
            sb.append("- 类：").append(actualClassName).append("\n");
            if (methodName != null && !methodName.trim().isEmpty()) {
                sb.append("- 方法：").append(methodName.trim()).append("\n");
            }
            sb.append("\n### 真实源码片段\n");
            sb.append("```java\n").append(truncateSource(targetSource, 5000)).append("\n```\n\n");
            sb.append("### 分析要求\n");
            sb.append("必须只基于上方真实源码分析，禁止使用 com.example、WebService、methodA/helperMethod 等示例或虚构类名/方法名。\n");
            sb.append("请输出：\n");
            sb.append("1. 该方法/类实际处理的业务输入和前置条件；\n");
            sb.append("2. 关键分支对应的业务规则；\n");
            sb.append("3. 返回值/副作用代表的业务结果；\n");
            sb.append("4. 无法从源码确认的业务需求请明确标注“源码无法确认”，不要猜测。\n");
            return sb.toString();
        } catch (Exception e) {
            logger.error("业务逻辑分析失败", e);
            return "业务逻辑分析失败：" + e.getMessage();
        }
    }

    // ========== 内部辅助方法 ==========

    /**
     * 截断过长的源码，保留关键部分
     */
    private String truncateSource(String source) {
        return truncateSource(source, 8000);
    }

    private String truncateSource(String source, int maxLength) {
        if (source == null) return "";
        if (source.length() <= maxLength) return source;
        // 保留开头和结尾
        int half = maxLength / 2;
        return source.substring(0, half) + "\n// ... [中间代码已截断] ...\n" +
               source.substring(source.length() - half);
    }

    /**
     * 从源码中提取指定方法的代码（简单文本匹配）
     */
    private String extractMethod(String source, String methodName) {
        // 简单匹配：找方法签名开始位置
        int idx = source.indexOf(methodName + "(");
        if (idx < 0) return null;

        // 回退找到方法签名的起始位置（找最近的换行或public/private/protected等）
        int start = Math.max(0, source.lastIndexOf('\n', idx));
        if (start > 0) start++; // 跳过换行符

        // 找方法体结束位置（简单计数花括号）
        int braceCount = 0;
        boolean foundOpen = false;
        int end = start;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                braceCount++;
                foundOpen = true;
            } else if (c == '}') {
                braceCount--;
                if (foundOpen && braceCount == 0) {
                    end = i + 1;
                    break;
                }
            }
        }

        if (end <= start) return null;
        return source.substring(start, end);
    }

    private Double parseDouble(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveActualClassName(String sourceCode, String requestedClassName) {
        String sourcePackage = extractPackageName(sourceCode);
        String sourceType = extractPrimaryTypeName(sourceCode);
        String requestedSimpleName = simpleClassName(requestedClassName);

        String typeName = sourceType != null ? sourceType : requestedSimpleName;
        if (typeName == null || typeName.isEmpty()) {
            return requestedClassName;
        }
        if (sourcePackage == null || sourcePackage.isEmpty()) {
            return typeName;
        }
        return sourcePackage + "." + typeName;
    }

    private String extractPackageName(String sourceCode) {
        if (sourceCode == null) return null;
        Matcher matcher = PACKAGE_PATTERN.matcher(sourceCode);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractPrimaryTypeName(String sourceCode) {
        if (sourceCode == null) return null;
        Matcher matcher = PRIMARY_TYPE_PATTERN.matcher(sourceCode);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String simpleClassName(String className) {
        if (className == null || className.trim().isEmpty()) return "";
        String trimmed = className.trim();
        int lastDot = trimmed.lastIndexOf('.');
        return lastDot >= 0 ? trimmed.substring(lastDot + 1) : trimmed;
    }

    private boolean isSameClass(String left, String right) {
        if (left == null || right == null) return false;
        String a = left.trim();
        String b = right.trim();
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }
}
