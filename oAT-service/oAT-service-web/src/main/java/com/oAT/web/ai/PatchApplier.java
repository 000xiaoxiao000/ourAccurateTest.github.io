package com.oAT.web.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用 git 风格 unified diff 到原始文本。仅支持标准 {@code @@} hunk 头与 {@code +}/{-}/{@code  } 上下文行。
 *
 * <p>用于 AI 生成的补丁在 {@code replaceEntireContent=false} 场景下的应用；
 * {@code replaceEntireContent=true} 时由调用方直接整文件替换，不走此类。
 */
public final class PatchApplier {

    private static final Pattern HEADER = Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+\\d+(?:,\\d+)? @@");

    private PatchApplier() {
    }

    public static String apply(String original, String diff) {
        List<String> orig = new ArrayList<>(Arrays.asList((original == null ? "" : original).split("\n")));
        String[] diffLines = (diff == null ? "" : diff).split("\n");
        List<String> out = new ArrayList<>();
        int origPos = 0;
        int i = 0;
        while (i < diffLines.length && !diffLines[i].startsWith("@@")) {
            i++;
        }
        while (i < diffLines.length) {
            String hunk = diffLines[i];
            if (!hunk.startsWith("@@")) {
                i++;
                continue;
            }
            int oldStart = parseOldStart(hunk);
            while (origPos < oldStart - 1 && origPos < orig.size()) {
                out.add(orig.get(origPos));
                origPos++;
            }
            i++;
            while (i < diffLines.length && !diffLines[i].startsWith("@@")) {
                String dl = diffLines[i];
                if (dl.startsWith("+")) {
                    out.add(dl.substring(1));
                } else if (dl.startsWith("-")) {
                    if (origPos < orig.size()) {
                        origPos++;
                    }
                } else if (dl.startsWith(" ")) {
                    if (origPos < orig.size()) {
                        out.add(dl.substring(1));
                        origPos++;
                    } else {
                        out.add(dl.substring(1));
                    }
                } else if (dl.startsWith("\\")) {
                    // "\ No newline at end of file" —— 忽略
                } else {
                    throw new IllegalArgumentException("无法解析的补丁行: " + dl);
                }
                i++;
            }
        }
        while (origPos < orig.size()) {
            out.add(orig.get(origPos));
            origPos++;
        }
        return String.join("\n", out);
    }

    private static int parseOldStart(String hunkHeader) {
        Matcher m = HEADER.matcher(hunkHeader);
        if (!m.find()) {
            throw new IllegalArgumentException("无法解析 hunk 头: " + hunkHeader);
        }
        return Integer.parseInt(m.group(1));
    }
}
