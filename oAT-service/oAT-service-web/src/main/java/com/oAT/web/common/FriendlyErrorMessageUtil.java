package com.oAT.web.common;

import org.springframework.util.StringUtils;

import java.net.UnknownHostException;

public final class FriendlyErrorMessageUtil {

    private FriendlyErrorMessageUtil() {
    }

    public static String git(Throwable throwable) {
        String message = getCombinedMessage(throwable);
        String lowerMessage = message.toLowerCase();

        if (hasCause(throwable, UnknownHostException.class)
                || lowerMessage.contains("unknownhostexception")
                || lowerMessage.contains("resolve host")) {
            return "无法连接到代码仓库，请检查仓库域名、网络或 VPN 后再试。";
        }
        if (lowerMessage.contains("cannot open git-upload-pack")) {
            return "无法访问代码仓库，请检查仓库地址、网络连通性或账号权限。";
        }
        if (lowerMessage.contains("401") || lowerMessage.contains("not authorized") || lowerMessage.contains("auth fail")) {
            return "代码仓库认证失败，请检查用户名、密码或访问令牌是否正确。";
        }
        if (lowerMessage.contains("forbidden") || lowerMessage.contains("403")) {
            return "代码仓库访问被拒绝，请确认账号有该仓库的读取权限。";
        }
        if (lowerMessage.contains("404") || lowerMessage.contains("not found")) {
            return "代码仓库地址无效或仓库不存在，请检查仓库配置。";
        }
        if (lowerMessage.contains("connection") || lowerMessage.contains("timeout") || lowerMessage.contains("timed out")) {
            return "连接代码仓库超时，请稍后重试或检查网络、防火墙配置。";
        }
        if (lowerMessage.contains("pre-receive hook declined") || lowerMessage.contains("hook-declined")) {
            return "代码仓库服务端拒绝了本次操作，请联系仓库管理员确认 Git Hook 配置。";
        }
        if (lowerMessage.contains("consistency check") || lowerMessage.contains("corrupt")) {
            return "代码仓库数据校验异常，请联系仓库管理员检查仓库状态。";
        }
        if (lowerMessage.contains("no space left") || lowerMessage.contains("disk full")) {
            return "服务器磁盘空间不足，请清理缓存后再试。";
        }
        if (lowerMessage.contains("permission denied") && lowerMessage.contains("filesystem")) {
            return "服务器文件系统权限不足，无法读写 Git 缓存目录。";
        }

        return StringUtils.hasText(message) ? message : "Git 操作失败，请稍后重试或检查仓库配置。";
    }

    public static String general(Throwable throwable) {
        String message = getCombinedMessage(throwable);
        String lowerMessage = message.toLowerCase();
        if (lowerMessage.contains("search_phase_execution_exception") || lowerMessage.contains("all shards failed")) {
            return "数据查询异常，请检查数据库连接或服务状态。";
        }
        if (lowerMessage.contains("connection refused") || lowerMessage.contains("connection timed out")) {
            return "服务连接失败，请检查相关服务是否在线。";
        }
        return StringUtils.hasText(message) ? message : "操作失败，请稍后重试。";
    }

    public static String getCombinedMessage(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                if (builder.length() > 0) {
                    builder.append("; ");
                }
                builder.append(current.getMessage());
            }
            current = current.getCause();
        }
        return builder.length() > 0 ? builder.toString() : throwable.toString();
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> causeClass) {
        Throwable current = throwable;
        while (current != null) {
            if (causeClass.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
