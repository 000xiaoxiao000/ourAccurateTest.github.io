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
 * 缺陷统计工具
 * 提供缺陷统计、错误分析、异常追踪等查询能力
 */
public class DefectStatisticsTool {

    private static final Logger logger = LoggerFactory.getLogger(DefectStatisticsTool.class);

    private final AgentDataProvider dataProvider;

    public DefectStatisticsTool(AgentDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Tool("获取项目的缺陷统计概览，包括错误数、异常类型分布等")
    public String getDefectOverview() {
        try {
            String projectId = AgentContext.getCurrentProjectId();
            if (projectId == null) {
                return "错误：未找到项目上下文";
            }

            List<Map<String, Object>> apps = dataProvider.getApps(projectId);
            if (apps == null || apps.isEmpty()) {
                return "当前项目下没有应用";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 项目缺陷统计概览\n\n");

            int totalErrors = 0;
            int totalExceptions = 0;
            Map<String, Integer> errorTypeDist = new java.util.HashMap<>();

            for (Map<String, Object> app : apps) {
                String appName = (String) app.getOrDefault("name", "未知");
                String appId = (String) app.get("id");

                try {
                    List<Map<String, Object>> traces = dataProvider.getTraceList(projectId, appId, 200);
                    if (traces != null) {
                        int appErrors = 0;
                        int appExceptions = 0;

                        for (Map<String, Object> trace : traces) {
                            Integer statusCode = parseInt(trace.get("statusCode"));
                            String error = (String) trace.get("error");
                            String exception = (String) trace.get("exception");

                            // 统计HTTP错误
                            if (statusCode != null && statusCode >= 400) {
                                appErrors++;
                                String errorType = statusCode >= 500 ? "服务器错误" : "客户端错误";
                                errorTypeDist.put(errorType, errorTypeDist.getOrDefault(errorType, 0) + 1);
                            }

                            // 统计异常
                            if (error != null && !error.isEmpty()) {
                                appExceptions++;
                                String errorType = extractErrorType(error);
                                errorTypeDist.put(errorType, errorTypeDist.getOrDefault(errorType, 0) + 1);
                            }

                            if (exception != null && !exception.isEmpty()) {
                                appExceptions++;
                            }
                        }

                        totalErrors += appErrors;
                        totalExceptions += appExceptions;

                        if (appErrors > 0 || appExceptions > 0) {
                            sb.append("### ").append(appName).append("\n");
                            sb.append("- HTTP错误数: ").append(appErrors).append("\n");
                            sb.append("- 异常数: ").append(appExceptions).append("\n");
                        }
                    }
                } catch (Exception e) {
                    logger.warn("获取应用 {} 的缺陷统计失败: {}", appName, e.getMessage());
                }
            }

            sb.append("\n---\n");
            sb.append("### 总体统计\n");
            sb.append("- 总HTTP错误数: **").append(totalErrors).append("**\n");
            sb.append("- 总异常数: **").append(totalExceptions).append("**\n");

            if (!errorTypeDist.isEmpty()) {
                sb.append("\n### 错误类型分布\n");
                sb.append("| 错误类型 | 数量 | 占比 |\n");
                sb.append("|----------|------|------|\n");

                int total = totalErrors + totalExceptions;
                errorTypeDist.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .forEach(entry -> {
                        double percentage = (entry.getValue() * 100.0) / total;
                        sb.append("| ").append(entry.getKey());
                        sb.append(" | ").append(entry.getValue());
                        sb.append(" | ").append(String.format("%.1f%%", percentage));
                        sb.append(" |\n");
                    });
            }

            return sb.toString();
        } catch (Exception e) {
            logger.error("获取缺陷统计概览失败", e);
            return "获取缺陷统计概览失败：" + e.getMessage();
        }
    }

    @Tool("获取应用的错误请求详情，包括错误URL、错误类型、错误信息等")
    public String getAppErrorDetails(@P("应用名称") String appName, @P("返回数量限制，默认10") int limit) {
        if (appName == null || appName.trim().isEmpty()) {
            return "错误：请提供应用名称";
        }
        if (limit <= 0) {
            limit = 10;
        }
        try {
            String projectId = AgentContext.getCurrentProjectId();
            if (projectId == null) {
                return "错误：未找到项目上下文";
            }

            List<Map<String, Object>> apps = dataProvider.getApps(projectId);
            String appId = findAppIdByName(apps, appName.trim());
            if (appId == null) {
                return "未找到应用：" + appName;
            }

            List<Map<String, Object>> traces = dataProvider.getTraceList(projectId, appId, 300);
            if (traces == null || traces.isEmpty()) {
                return "应用【" + appName + "】暂无调用链数据";
            }

            // 筛选错误请求
            List<Map<String, Object>> errorTraces = new java.util.ArrayList<>();
            for (Map<String, Object> trace : traces) {
                Integer statusCode = parseInt(trace.get("statusCode"));
                String error = (String) trace.get("error");
                
                if ((statusCode != null && statusCode >= 400) || (error != null && !error.isEmpty())) {
                    errorTraces.add(trace);
                }
            }

            if (errorTraces.isEmpty()) {
                return "✅ 应用【" + appName + "】暂无错误请求，运行状态良好！";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## ").append(appName).append(" - 错误请求详情\n\n");
            sb.append("共发现 **").append(errorTraces.size()).append("** 个错误请求\n\n");

            int count = 0;
            for (Map<String, Object> trace : errorTraces) {
                if (count >= limit) break;
                count++;

                String url = (String) trace.getOrDefault("url", "-");
                Integer statusCode = parseInt(trace.get("statusCode"));
                String error = (String) trace.get("error");
                String exception = (String) trace.get("exception");
                String createTime = (String) trace.getOrDefault("createTime", "-");
                Long duration = parseLong(trace.get("duration"));

                sb.append("### ").append(count).append(". ");
                if (statusCode != null && statusCode >= 400) {
                    sb.append("HTTP ").append(statusCode);
                    if (statusCode >= 500) {
                        sb.append(" 🔴");
                    } else {
                        sb.append(" 🟡");
                    }
                } else {
                    sb.append("异常");
                }
                sb.append("\n\n");

                sb.append("- 接口: `").append(url).append("`\n");
                if (statusCode != null) {
                    sb.append("- 状态码: ").append(statusCode).append("\n");
                }
                if (duration != null) {
                    sb.append("- 响应时间: ").append(duration).append("ms\n");
                }
                if (error != null && !error.isEmpty()) {
                    sb.append("- 错误信息: ").append(truncateString(error, 100)).append("\n");
                }
                if (exception != null && !exception.isEmpty()) {
                    sb.append("- 异常类型: ").append(extractErrorType(exception)).append("\n");
                }
                sb.append("- 发生时间: ").append(createTime).append("\n");
                sb.append("\n");
            }

            if (errorTraces.size() > limit) {
                sb.append("> 仅显示前").append(limit).append("个错误，共").append(errorTraces.size()).append("个错误\n");
            }

            return sb.toString();
        } catch (Exception e) {
            logger.error("获取应用错误详情失败", e);
            return "获取应用错误详情失败：" + e.getMessage();
        }
    }

    @Tool("获取最近发生的异常列表，包括异常类型、发生位置等")
    public String getRecentExceptions(@P("应用名称") String appName, @P("返回数量限制，默认10") int limit) {
        if (appName == null || appName.trim().isEmpty()) {
            return "错误：请提供应用名称";
        }
        if (limit <= 0) {
            limit = 10;
        }
        try {
            String projectId = AgentContext.getCurrentProjectId();
            if (projectId == null) {
                return "错误：未找到项目上下文";
            }

            List<Map<String, Object>> apps = dataProvider.getApps(projectId);
            String appId = findAppIdByName(apps, appName.trim());
            if (appId == null) {
                return "未找到应用：" + appName;
            }

            List<Map<String, Object>> traces = dataProvider.getTraceList(projectId, appId, 300);
            if (traces == null || traces.isEmpty()) {
                return "应用【" + appName + "】暂无调用链数据";
            }

            // 筛选异常
            List<Map<String, Object>> exceptionTraces = new java.util.ArrayList<>();
            Map<String, Integer> exceptionTypeCount = new java.util.HashMap<>();

            for (Map<String, Object> trace : traces) {
                String exception = (String) trace.get("exception");
                if (exception != null && !exception.isEmpty()) {
                    exceptionTraces.add(trace);
                    String excType = extractErrorType(exception);
                    exceptionTypeCount.put(excType, exceptionTypeCount.getOrDefault(excType, 0) + 1);
                }
            }

            if (exceptionTraces.isEmpty()) {
                return "✅ 应用【" + appName + "】暂无异常，运行状态良好！";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## ").append(appName).append(" - 异常统计\n\n");
            sb.append("共发现 **").append(exceptionTraces.size()).append("** 个异常\n\n");

            // 异常类型分布
            sb.append("### 异常类型分布\n");
            sb.append("| 异常类型 | 数量 |\n");
            sb.append("|----------|------|\n");
            exceptionTypeCount.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(entry -> {
                    sb.append("| ").append(entry.getKey());
                    sb.append(" | ").append(entry.getValue());
                    sb.append(" |\n");
                });

            // 最近异常详情
            sb.append("\n### 最近异常详情\n");
            int count = 0;
            for (Map<String, Object> trace : exceptionTraces) {
                if (count >= limit) break;
                count++;

                String url = (String) trace.getOrDefault("url", "-");
                String exception = (String) trace.get("exception");
                String createTime = (String) trace.getOrDefault("createTime", "-");

                sb.append(count).append(". **").append(extractErrorType(exception)).append("**\n");
                sb.append("   - 接口: `").append(url).append("`\n");
                sb.append("   - 异常: ").append(truncateString(exception, 80)).append("\n");
                sb.append("   - 时间: ").append(createTime).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            logger.error("获取异常列表失败", e);
            return "获取异常列表失败：" + e.getMessage();
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

    private String extractErrorType(String error) {
        if (error == null || error.isEmpty()) return "未知错误";
        // 提取异常类名
        int colonIndex = error.indexOf(':');
        if (colonIndex > 0) {
            String fullType = error.substring(0, colonIndex).trim();
            int lastDot = fullType.lastIndexOf('.');
            return lastDot >= 0 ? fullType.substring(lastDot + 1) : fullType;
        }
        return error.length() > 50 ? error.substring(0, 50) + "..." : error;
    }

    private String truncateString(String str, int maxLength) {
        if (str == null) return "";
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }

    private Long parseLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
