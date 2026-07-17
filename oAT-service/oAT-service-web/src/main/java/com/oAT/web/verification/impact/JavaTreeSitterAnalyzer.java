package com.oAT.web.verification.impact;

import com.oAT.web.verification.impact.ImpactModels.LineRange;
import com.oAT.web.verification.impact.ImpactModels.SymbolKind;
import com.oAT.web.verification.impact.ImpactModels.SymbolSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JavaTreeSitterAnalyzer implements LanguageAnalyzer {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("(?m)^\\s*(?:@[\\w.]+(?:\\([^\\n]*\\))?\\s*)*(?:public|protected|private|abstract|final|static|sealed|non-sealed|\\s)*(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern METHOD = Pattern.compile("(?m)^\\s*(?:@[\\w.]+(?:\\([^\\n]*\\))?\\s*)*(?:public|protected|private|static|final|synchronized|abstract|native|default|\\s)+([\\w$<>?,.\\[\\] ]+)\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*(?:throws[^\\{]+)?\\{");
    private static final Pattern INVOCATION = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\(");

    @Override
    public boolean supports(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".java");
    }

    @Override
    public List<SymbolSnapshot> analyze(String path, String source) {
        if (!StringUtils.hasText(source)) return List.of();
        String packageName = match(PACKAGE, source, 1);
        String type = match(TYPE, source, 1);
        String qualifiedType = StringUtils.hasText(packageName) ? packageName + "." + type : type;
        List<SymbolSnapshot> symbols = new ArrayList<>();
        if (StringUtils.hasText(type)) symbols.add(snapshot("java://" + qualifiedType, SymbolKind.TYPE, qualifiedType, type, path, 1, lines(source), source, List.of()));
        Matcher matcher = METHOD.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(2);
            String parameters = normalizeParameters(matcher.group(3));
            int begin = lineAt(source, matcher.start());
            int end = closingBraceLine(source, matcher.end() - 1, begin);
            String body = lines(source, begin, end);
            String signature = name + "(" + parameters + ")";
            String key = "java://" + qualifiedType + "#" + signature;
            symbols.add(snapshot(key, SymbolKind.METHOD, qualifiedType + "." + name, signature, path, begin, end, body, invocations(body)));
        }
        return symbols;
    }

    private SymbolSnapshot snapshot(String key, SymbolKind kind, String qualifiedName, String signature, String path, int start, int end, String body, List<String> invocations) {
        return new SymbolSnapshot(key, kind, "java", qualifiedName, signature, path, new LineRange(start, end), hash(normalize(body)), hash(normalize(body)), hash(signature), clip(body), invocations);
    }
    private String match(Pattern pattern, String source, int group) { Matcher matcher = pattern.matcher(source); return matcher.find() ? matcher.group(group) : ""; }
    private String normalizeParameters(String value) { return value == null || value.isBlank() ? "" : value.replaceAll("@[\\w.]+", "").replaceAll("\\s+", " ").trim(); }
    private List<String> invocations(String body) { Matcher m = INVOCATION.matcher(body); List<String> result = new ArrayList<>(); while (m.find()) { String value = m.group(1); if (!List.of("if", "for", "while", "switch", "catch", "return", "new").contains(value)) result.add(value); } return result.stream().distinct().toList(); }
    private int lineAt(String source, int offset) { int line = 1; for (int i = 0; i < offset; i++) if (source.charAt(i) == '\n') line++; return line; }
    private int closingBraceLine(String source, int start, int fallback) { int depth = 0; for (int i = start; i < source.length(); i++) { char c = source.charAt(i); if (c == '{') depth++; else if (c == '}' && --depth == 0) return lineAt(source, i); } return fallback; }
    private int lines(String source) { return lineAt(source, source.length()); }
    private String lines(String source, int start, int end) { String[] all = source.split("\\R", -1); StringBuilder result = new StringBuilder(); for (int i = Math.max(1, start); i <= Math.min(end, all.length); i++) result.append(all[i - 1]).append('\n'); return result.toString(); }
    private String normalize(String value) { return value.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "").replaceAll("\\s+", " ").trim(); }
    private String hash(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String clip(String value) { return value.length() <= 4000 ? value : value.substring(0, 4000); }
}
