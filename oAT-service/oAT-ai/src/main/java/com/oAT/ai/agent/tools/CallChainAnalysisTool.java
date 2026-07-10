package com.oAT.ai.agent.tools;

import com.oAT.ai.agent.AgentContext;
import com.oAT.ai.agent.AgentDataProvider;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * AI调用链路分析工具
 * 基于已有的调用链追踪数据，结合AI分析能力，生成结构化的调用链路图、性能瓶颈定位、异常根因分析
 *
 * <p>能力维度：</p>
 * <ul>
 *   <li>链路拓扑可视化 - 生成Mermaid格式的调用链图</li>
 *   <li>性能瓶颈定位 - 找出耗时最长的节点和路径</li>
 *   <li>异常根因追溯 - 从错误节点向上追溯到根因</li>
 *   <li>跨服务依赖分析 - 识别关键依赖路径</li>
 *   <li>慢请求模式识别 - 发现重复出现的慢请求特征</li>
 * </ul>
 */
public class CallChainAnalysisTool {

    private static final Logger logger = LoggerFactory.getLogger(CallChainAnalysisTool.class);

    private final AgentDataProvider dataProvider;

    public CallChainAnalysisTool(AgentDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Tool("对指定调用链进行深度分析，生成完整的调用拓扑、性能瓶颈定位、异常根因分析")
    public String analyzeCallChain(@P("调用链ID（TraceID）") String traceId) {
        if (traceId == null || traceId.trim().isEmpty()) {
            return "错误：请提供调用链ID（TraceID）";
        }
        try {
            Map<String, Object> trace = dataProvider.getTraceDetail(traceId);
            if (trace == null || trace.isEmpty()) {
                return "未找到 TraceID: " + traceId + " 的调用链数据。可能原因：\n" +
                       "1. 该TraceID不存在或已过期\n" +
                       "2. 调用链数据尚未上报到Server端\n" +
                       "3. 请使用 getRecentTraces 工具先获取有效的TraceID列表";
            }

            // 构建结构化的链路分析数据
            StringBuilder analysisData = new StringBuilder();
            analysisData.append("## AI调用链深度分析\n\n");
            analysisData.append("### 基本信息\n");
            analysisData.append("- **TraceID**: ").append(trace.getOrDefault("traceId", "-")).append("\n");
            analysisData.append("- **入口URL**: ").append(trace.getOrDefault("url", "-")).append("\n");
            analysisData.append("- **应用**: ").append(trace.getOrDefault("appName", "-")).append("\n");
            analysisData.append("- **客户端IP**: ").append(trace.getOrDefault("clientIp", "-")).append("\n");
            analysisData.append("- **开始时间**: ").append(trace.getOrDefault("startTime", "-")).append("\n");
            analysisData.append("- **总耗时**: ").append(trace.getOrDefault("duration", "-")).append("ms\n");

            // 提取调用节点
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) trace.get("nodes");

            if (nodes != null && !nodes.isEmpty()) {
                analysisData.append("\n### 调用节点明细（").append(nodes.size()).append("个）\n\n");

                // 按耗时排序统计
                long totalDuration = parseLong(trace.get("duration"));
                long maxNodeDuration = 0;
                String slowestNode = "";
                int errorCount = 0;
                String errorNodeName = "";

                // 构建Mermaid格式调用图
                analysisData.append("```mermaid\ngraph TD\n");
                analysisData.append("    Start([\"入口: ").append(safeStr(trace.get("url"))).append("\"])\n");

                for (int i = 0; i < nodes.size(); i++) {
                    Map<String, Object> node = nodes.get(i);
                    String name = safeStr(node.get("name"));
                    String type = safeStr(node.get("type"));
                    long duration = parseLong(node.get("duration"));
                    boolean hasError = Boolean.TRUE.equals(node.get("error")) ||
                                       "error".equalsIgnoreCase(type) ||
                                       node.get("exception") != null;

                    String nodeId = "N" + i;
                    String label = name.length() > 30 ? name.substring(0, 27) + "..." : name;
                    String nodeStyle = hasError ? "style " + nodeId + " fill:#ff6b6b,color:#fff" :
                                      (duration > (totalDuration > 0 ? totalDuration * 0.2 : 100) ?
                                       "style " + nodeId + " fill:#ffa94d,color:#fff" : "");

                    analysisData.append("    ").append(nodeId)
                            .append("[\"").append(label)
                            .append("\\n").append(type)
                            .append(" ").append(duration).append("ms\"]\n");

                    if (i == 0) {
                        analysisData.append("    Start --> ").append(nodeId).append("\n");
                    } else if (i > 0) {
                        analysisData.append("    N").append(i - 1).append(" --> ").append(nodeId).append("\n");
                    }

                    if (!nodeStyle.isEmpty()) {
                        analysisData.append("    ").append(nodeStyle).append("\n");
                    }

                    // 统计
                    if (duration > maxNodeDuration) {
                        maxNodeDuration = duration;
                        slowestNode = name;
                    }
                    if (hasError) {
                        errorCount++;
                        errorNodeName = name;
                    }
                }
                analysisData.append("```\n\n");

                // 性能瓶颈分析
                analysisData.append("### 性能分析\n\n");
                analysisData.append("**最慢节点**: ").append(slowestNode).append(" (").append(maxNodeDuration).append("ms)\n");
                if (totalDuration > 0) {
                    double pct = totalDuration > 0 ? (double) maxNodeDuration / totalDuration * 100 : 0;
                    analysisData.append("**占比**: ").append(String.format("%.1f%%", pct)).append(" 总耗时\n");
                }
                analysisData.append("**错误节点数**: ").append(errorCount);
                if (errorCount > 0) {
                    analysisData.append(" (").append(errorNodeName).append(")");
                }
                analysisData.append("\n");

                // 各节点详情表
                analysisData.append("\n### 节点耗时排行\n\n");
                analysisData.append("| # | 类型 | 名称 | 耗时(ms) | 占比 | 状态 |\n");
                analysisData.append("|---|------|------|----------|------|------|\n");

                // 排序输出
                List<Map<String, Object>> sortedNodes = new java.util.ArrayList<>(nodes);
                sortedNodes.sort((a, b) -> Long.compare(parseLong(b.get("duration")), parseLong(a.get("duration"))));

                int rank = 0;
                for (Map<String, Object> node : sortedNodes) {
                    rank++;
                    String name = safeStr(node.get("name"));
                    String type = safeStr(node.get("type"));
                    long duration = parseLong(node.get("duration"));
                    double pct = totalDuration > 0 ? (double) duration / totalDuration * 100 : 0;
                    boolean hasError = Boolean.TRUE.equals(node.get("error")) || "error".equalsIgnoreCase(type);

                    analysisData.append("| ").append(rank);
                    analysisData.append(" | ").append(type);
                    analysisData.append(" | ").append(name.length() > 25 ? name.substring(0, 22) + "..." : name);
                    analysisData.append(" | ").append(duration);
                    analysisData.append(" | ").append(String.format("%.1f%%", pct));
                    analysisData.append(" | ").append(hasError ? "❌ 异常" : "✅ 正常").append(" |\n");
                    if (rank >= 15) break;
                }

                // AI分析引导
                analysisData.append("\n### 请基于以上数据进行智能分析\n\n");
                analysisData.append("请从以下角度给出专业分析：\n");
                analysisData.append("1. **调用链合理性** - 调用层级是否合理，是否有不必要的远程调用？\n");
                analysisData.append("2. **性能优化建议** - 最慢节点的优化方案，是否可以并行化或缓存？\n");
                analysisData.append("3. **错误根因分析** - 如果有错误节点，分析错误的可能原因和影响范围\n");
                analysisData.append("4. **SLA合规性** - 本次请求是否符合SLA要求（通常P99<500ms），如不符合给出改进方向\n");
                analysisData.append("5. **架构建议** - 从该调用链反映出的架构问题及改进建议\n");
            } else {
                analysisData.append("\n⚠️ 该调用链暂无详细节点数据。\n");
            }

            return analysisData.toString();
        } catch (Exception e) {
            logger.error("调用链分析失败", e);
            return "调用链分析失败：" + e.getMessage();
        }
    }

    @Tool("获取指定应用的最近N条调用链的汇总分析，发现共性问题和模式")
    public String analyzeRecentCallChains(@P("应用名称") String appName, @P("分析数量限制，默认10") int limit) {
        if (limit <= 0) limit = 10;
        try {
            String projectId = AgentContext.getCurrentProjectId();
            if (projectId == null) return "错误：未找到项目上下文";

            List<Map<String, Object>> traces;
            if (appName != null && !appName.trim().isEmpty()) {
                // 先找appId
                List<Map<String, Object>> apps = dataProvider.getApps(projectId);
                String appId = findAppIdByName(apps, appName.trim());
                if (appId == null) return "未找到应用：" + appName;
                traces = dataProvider.getTraceList(projectId, appId, limit);
            } else {
                traces = dataProvider.getTraceList(projectId, null, limit);
            }

            if (traces == null || traces.isEmpty()) {
                return "暂无调用链数据";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 最近 ").append(traces.size()).append(" 条调用链汇总分析\n\n");
            sb.append("### 链路列表概览\n\n");
            sb.append("| # | TraceID | URL | 应用 | 耗时(ms) | 状态 |\n");
            sb.append("|---|---------|-----|------|----------|------|\n");

            int errorCount = 0;
            long totalDuration = 0;
            long maxDuration = 0;
            String slowestTraceId = "";

            for (int i = 0; i < traces.size(); i++) {
                Map<String, Object> t = traces.get(i);
                long dur = parseLong(t.get("duration"));
                totalDuration += dur;
                if (dur > maxDuration) {
                    maxDuration = dur;
                    slowestTraceId = safeStr(t.get("traceId"));
                }
                boolean hasError = "error".equals(t.get("status")) || t.get("error") != null;
                if (hasError) errorCount++;

                sb.append("| ").append(i + 1);
                sb.append(" | ").append(safeStr(t.get("traceId")).substring(0, Math.min(12, safeStr(t.get("traceId")).length())));
                String url = safeStr(t.get("url"));
                sb.append(" | ").append(url.length() > 40 ? url.substring(0, 37) + "..." : url);
                sb.append(" | ").append(safeStr(t.get("appName")));
                sb.append(" | ").append(dur);
                sb.append(" | ").append(hasError ? "❌ 错误" : "✅ 正常").append(" |\n");
            }

            double avgDur = traces.size() > 0 ? (double) totalDuration / traces.size() : 0;

            sb.append("\n### 统计摘要\n\n");
            sb.append("- **平均耗时**: ").append(String.format("%.0fms", avgDur)).append("\n");
            sb.append("- **最大耗时**: ").append(maxDuration).append("ms (").append(slowestTraceId).append(")\n");
            sb.append("- **错误率**: ").append(traces.size() > 0 ? String.format("%.1f%%", (double) errorCount / traces.size() * 100) : "0%").append("\n");
            sb.append("- **总请求数**: ").append(traces.size()).append("\n\n");

            sb.append("### 请进行以下分析\n\n");
            sb.append("1. **性能基线评估** - 平均/最大/P95耗时的合理性，与行业基准对比\n");
            sb.append("2. **错误模式识别** - 错误请求的共同特征（URL、参数、时机）\n");
            sb.append("3. **慢请求规律** - 高耗时请求的共性和可优化的环节\n");
            sb.append("4. **健康度评分** - 对当前应用的调用链健康度打分(1-10)，并说明理由\n");
            sb.append("5. **行动项** - 给出TOP 3需要立即关注的问题和解决方案\n");

            return sb.toString();
        } catch (Exception e) {
            logger.error("批量调用链分析失败", e);
            return "批量调用链分析失败：" + e.getMessage();
        }
    }

    @Tool("分析指定类/方法的调用关系图，展示完整的上下游调用链路")
    public String analyzeMethodCallChain(@P("类全限定名") String className,
                                          @P("方法名（可选）") String methodName) {
        if (className == null || className.trim().isEmpty()) {
            return "错误：请提供类名";
        }
        try {
            Map<String, Object> graph = dataProvider.getCallGraph(className, methodName);
            if (graph == null || graph.isEmpty()) {
                return "未找到类 " + className + " 的真实调用关系数据。请不要使用示例调用链、示例链接或猜测的上下游方法回答。";
            }

            Boolean classFound = (Boolean) graph.get("classFound");
            Boolean methodFound = (Boolean) graph.get("methodFound");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> callers = (List<Map<String, Object>>) graph.get("callers");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> callees = (List<Map<String, Object>>) graph.get("callees");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> relatedTraces = (List<Map<String, Object>>) graph.get("traces");
            if ((callers == null || callers.isEmpty()) && (callees == null || callees.isEmpty()) && (relatedTraces == null || relatedTraces.isEmpty())) {
                String target = className + (methodName != null && !methodName.isEmpty() ? ("." + methodName) : "");
                if (Boolean.FALSE.equals(classFound)) {
                    return "未找到类 " + className + " 的静态源码/调用关系数据，无法展示真实调用链。请确认类名或先完成静态扫描；禁止使用示例类名、示例链接或猜测调用链。";
                }
                if (Boolean.FALSE.equals(methodFound)) {
                    return "已找到类 " + className + "，但未找到方法 " + methodName + " 的静态信息或真实调用关系数据。请确认方法名；禁止猜测上下游方法。";
                }
                return "未找到 " + target + " 的真实调用关系数据。该类/方法可能尚无运行时 trace 或静态调用边数据；请不要使用示例调用链、示例链接或猜测的上下游方法回答。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## ").append(className);
            if (methodName != null && !methodName.isEmpty()) {
                sb.append(".").append(methodName);
            }
            sb.append(" - 完整调用链路分析\n\n");

            // 调用方
            if (callers != null && !callers.isEmpty()) {
                sb.append("### 上游调用方（谁调用了它）\n\n");
                sb.append("```mermaid\ngraph LR\n");
                for (int i = 0; i < callers.size() && i < 20; i++) {
                    Map<String, Object> c = callers.get(i);
                    String cn = shortName(safeStr(c.get("className")));
                    String mn = safeStr(c.get("methodName"));
                    sb.append("    C").append(i).append("[\"").append(cn).append(".").append(mn).append("\"] --> Target\n");
                }
                sb.append("    Target[\"").append(shortName(className));
                if (methodName != null) sb.append(".").append(methodName);
                sb.append("\"].\n```\n\n");

                sb.append("| 调用方类 | 方法 | 调用类型 | 出现次数 |\n");
                sb.append("|---------|------|---------|--------|\n");
                for (Map<String, Object> c : callers) {
                    sb.append("| ").append(shortName(safeStr(c.get("className"))));
                    sb.append(" | ").append(safeStr(c.get("methodName")));
                    sb.append(" | ").append(safeStr(c.get("type"), "-"));
                    sb.append(" | ").append(safeStr(c.get("count"), "-")).append(" |\n");
                }
            } else {
                sb.append("\n### 上游调用方\n暂无调用记录（该代码可能未被实际调用过）\n");
            }

            // 被调用方
            if (callees != null && !callees.isEmpty()) {
                sb.append("\n### 下游被调用方（它调用了谁）\n\n");
                sb.append("```mermaid\ngraph TD\n");
                sb.append("    Target[\"").append(shortName(className));
                if (methodName != null) sb.append(".").append(methodName);
                sb.append("\"]\n");
                for (int i = 0; i < callees.size() && i < 20; i++) {
                    Map<String, Object> c = callees.get(i);
                    String cn = shortName(safeStr(c.get("className")));
                    String mn = safeStr(c.get("methodName"));
                    sb.append("    Target --> D").append(i).append("[\"").append(cn).append(".").append(mn).append("\"]\n");
                }
                sb.append("```\n\n");

                sb.append("| 被调用方类 | 方法 | 调用类型 | 出现次数 |\n");
                sb.append("|-----------|------|---------|--------|\n");
                for (Map<String, Object> c : callees) {
                    sb.append("| ").append(shortName(safeStr(c.get("className"))));
                    sb.append(" | ").append(safeStr(c.get("methodName")));
                    sb.append(" | ").append(safeStr(c.get("type"), "-"));
                    sb.append(" | ").append(safeStr(c.get("count"), "-")).append(" |\n");
                }
            } else {
                sb.append("\n### 下游被调用方\n暂无下游调用记录\n");
            }

            // 关联调用链
            if (relatedTraces != null && !relatedTraces.isEmpty()) {
                sb.append("\n### 关联调用链（最近").append(relatedTraces.size()).append("条）\n\n");
                for (Map<String, Object> t : relatedTraces.subList(0, Math.min(10, relatedTraces.size()))) {
                    sb.append("- TraceID: ").append(safeStr(t.get("traceId")));
                    sb.append(" | URL: ").append(safeStr(t.get("url")));
                    sb.append(" | 时间: ").append(safeStr(t.get("createTime"))).append("\n");
                }
            }

            sb.append("\n### 智能分析请求\n\n");
            sb.append("请只根据以上真实调用关系进行分析；没有数据的位置请明确说暂无真实数据，不要补造调用链、图片链接或源码中不存在的方法。\n");
            sb.append("1. **核心路径** - 这条调用链中的关键路径是什么？哪个环节最重要？\n");
            sb.append("2. **耦合度评估** - 该类的上游/下游依赖是否过多？是否存在循环依赖风险？\n");
            sb.append("3. **变更影响面** - 如果修改这个类/方法，会影响哪些模块？风险评估？\n");
            sb.append("4. **优化机会** - 是否有不必要的中间层？能否简化调用路径？\n");
            sb.append("5. **测试策略** - 基于调用关系，给出针对性的测试覆盖建议\n");

            return sb.toString();
        } catch (Exception e) {
            logger.error("方法调用链分析失败", e);
            return "方法调用链分析失败：" + e.getMessage();
        }
    }

    @Tool("分析某个接口URL的调用链路模式，找出该接口的典型执行路径和异常情况")
    public String analyzeUrlCallPattern(@P("接口URL路径（如/api/user/info）") String urlPattern) {
        if (urlPattern == null || urlPattern.trim().isEmpty()) {
            return "错误：请提供接口URL路径";
        }
        try {
            String projectId = AgentContext.getCurrentProjectId();
            if (projectId == null) return "错误：未找到项目上下文";

            // 先搜索包含该URL的调用链
            List<Map<String, Object>> allTraces = dataProvider.getTraceList(projectId, null, 50);
            if (allTraces == null || allTraces.isEmpty()) {
                return "暂无调用链数据";
            }

            // 过滤匹配的trace
            List<Map<String, Object>> matched = new java.util.ArrayList<>();
            for (Map<String, Object> t : allTraces) {
                String url = safeStr(t.get("url"));
                if (url.contains(urlPattern) || urlPattern.contains(url)) {
                    matched.add(t);
                }
            }

            if (matched.isEmpty()) {
                return "未找到匹配URL \"" + urlPattern + "\" 的调用链。\n" +
                       "已检查最近50条调用链，均不包含该URL。\n" +
                       "提示：可以先用 getRecentTraces 查看所有调用链的URL列表。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 接口调用链路模式分析\n\n");
            sb.append("**目标URL**: ").append(urlPattern).append("\n");
            sb.append("**命中数量**: ").append(matched.size()).append(" 条\n\n");

            // 统计
            long sumDuration = 0;
            long minD = Long.MAX_VALUE, maxD = 0;
            int errCount = 0;

            for (Map<String, Object> t : matched) {
                long d = parseLong(t.get("duration"));
                sumDuration += d;
                if (d < minD) minD = d;
                if (d > maxD) maxD = d;
                if ("error".equals(t.get("status"))) errCount++;
            }

            double avgD = matched.size() > 0 ? (double) sumDuration / matched.size() : 0;

            sb.append("### 性能指标\n\n");
            sb.append("| 指标 | 值 |\n|------|-----|\n");
            sb.append("| 平均耗时 | ").append(String.format("%.0fms", avgD)).append(" |\n");
            sb.append("| 最小耗时 | ").append(minD == Long.MAX_VALUE ? "-" : minD + "ms").append(" |\n");
            sb.append("| 最大耗时 | ").append(maxD).append("ms |\n");
            sb.append("| P90估算 | ~").append(String.format("%.0fms", avgD + (maxD - avgD) * 0.8)).append(" (估算) |\n");
            sb.append("| 错误率 | ").append(matched.size() > 0 ? String.format("%.1f%%", (double) errCount / matched.size() * 100) : "0%").append(" |\n\n");

            // 取一条典型链路做深入分析
            if (!matched.isEmpty()) {
                String sampleTraceId = safeStr(matched.get(0).get("traceId"));
                sb.append("> 📋 典型调用链 TraceID: ").append(sampleTraceId).append("\n");
                sb.append("> 可使用 analyzeCallChain(\"").append(sampleTraceId).append("\") 进行深度分析\n\n");
            }

            sb.append("### 请分析\n\n");
            sb.append("1. **响应时间分布是否合理** - 根据上述统计数据判断该接口性能是否达标\n");
            sb.append("2. **波动性分析** - min/max差距大吗？是否有抖动？可能的原因\n");
            sb.append("3. **错误模式** - 有错误的话，错误类型和频率如何\n");
            sb.append("4. **优化建议** - 针对该接口的具体优化方案（缓存、异步、SQL优化等）\n");

            return sb.toString();
        } catch (Exception e) {
            logger.error("URL调用模式分析失败", e);
            return "URL调用模式分析失败：" + e.getMessage();
        }
    }

    // ========== 辅助方法 ==========

    private String findAppIdByName(List<Map<String, Object>> apps, String appName) {
        if (apps == null) return null;
        for (Map<String, Object> app : apps) {
            String name = (String) app.getOrDefault("name", "");
            if (name.equalsIgnoreCase(appName) || name.toLowerCase().contains(appName.toLowerCase())) {
                return (String) app.get("id");
            }
        }
        return null;
    }

    private static long parseLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try { return Long.parseLong(obj.toString()); } catch (Exception e) { return 0L; }
    }

    private static String safeStr(Object obj) {
        return safeStr(obj, "");
    }

    private static String safeStr(Object obj, String defaultValue) {
        if (obj == null) return defaultValue;
        return obj.toString();
    }

    private static String shortName(String fullName) {
        if (fullName == null || !fullName.contains(".")) return fullName;
        return fullName.substring(fullName.lastIndexOf(".") + 1);
    }
}
