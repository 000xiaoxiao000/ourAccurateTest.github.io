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
public class PolyglotLanguageAnalyzer implements LanguageAnalyzer {
    private static final Pattern JS_TYPE = Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:default\\s+)?(?:class|interface)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern JS_FUNCTION = Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*\\{");
    private static final Pattern JS_ARROW = Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?\\(([^)]*)\\)\\s*=>");
    private static final Pattern GO_TYPE = Pattern.compile("(?m)^\\s*type\\s+([A-Za-z_][\\w]*)\\s+(?:struct|interface)");
    private static final Pattern GO_FUNCTION = Pattern.compile("(?m)^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)");
    private static final Pattern PY_TYPE = Pattern.compile("(?m)^\\s*class\\s+([A-Za-z_][\\w]*)");
    private static final Pattern PY_FUNCTION = Pattern.compile("(?m)^(\\s*)(?:async\\s+)?def\\s+([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)");
    private static final Pattern CPP_TYPE = Pattern.compile("(?m)^\\s*(?:class|struct|enum)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern CPP_FUNCTION = Pattern.compile("(?m)^\\s*(?:[A-Za-z_][\\w:<>,~*&\\s]*\\s+)?([A-Za-z_~][\\w:~]*)\\s*\\(([^;{}()]*)\\)\\s*(?:const\\s*)?\\{");
    private static final Pattern INVOCATION = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\(");

    @Override
    public boolean supports(String path) {
        return language(path) != null;
    }

    @Override
    public List<SymbolSnapshot> analyze(String path, String source) {
        if (!StringUtils.hasText(source) || language(path) == null) return List.of();
        String language = language(path);
        List<SymbolSnapshot> symbols = new ArrayList<>();
        switch (language) {
            case "frontend" -> { addTypes(symbols, path, source, language, JS_TYPE); addFunctions(symbols, path, source, language, JS_FUNCTION, 1, 2, false); addFunctions(symbols, path, source, language, JS_ARROW, 1, 2, false); }
            case "go" -> { addTypes(symbols, path, source, language, GO_TYPE); addFunctions(symbols, path, source, language, GO_FUNCTION, 1, 2, false); }
            case "python" -> { addTypes(symbols, path, source, language, PY_TYPE); addFunctions(symbols, path, source, language, PY_FUNCTION, 2, 3, true); }
            case "cpp" -> { addTypes(symbols, path, source, language, CPP_TYPE); addFunctions(symbols, path, source, language, CPP_FUNCTION, 1, 2, false); }
            default -> { }
        }
        return symbols;
    }

    private void addTypes(List<SymbolSnapshot> result, String path, String source, String language, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1);
            result.add(snapshot(language + "://" + path + "#" + name, SymbolKind.TYPE, language, name, name, path, lineAt(source, matcher.start()), lineAt(source, matcher.end()), matcher.group(), List.of()));
        }
    }

    private void addFunctions(List<SymbolSnapshot> result, String path, String source, String language, Pattern pattern, int nameGroup, int parameterGroup, boolean indentationScoped) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(nameGroup);
            String parameters = matcher.groupCount() >= parameterGroup ? normalize(matcher.group(parameterGroup)) : "";
            int start = lineAt(source, matcher.start());
            int endOffset = indentationScoped ? pythonEndOffset(source, matcher.end(), leadingIndent(source, matcher.start())) : closingBraceOffset(source, matcher.end() - 1);
            int end = endOffset >= 0 ? lineAt(source, endOffset) : start;
            String body = endOffset >= 0 ? source.substring(matcher.start(), endOffset + 1) : matcher.group();
            String signature = name + "(" + parameters + ")";
            result.add(snapshot(language + "://" + path + "#" + signature, SymbolKind.METHOD, language, name, signature, path, start, end, body, invocations(body)));
        }
    }

    private SymbolSnapshot snapshot(String key, SymbolKind kind, String language, String qualifiedName, String signature, String path, int start, int end, String body, List<String> invoked) {
        String normalized = normalize(body);
        return new SymbolSnapshot(key, kind, language, qualifiedName, signature, path, new LineRange(start, end), hash(normalized), hash(normalized), hash(signature), body.length() <= 4000 ? body : body.substring(0, 4000), invoked);
    }
    private String language(String path) { if (path == null) return null; String value = path.toLowerCase(Locale.ROOT); if (value.matches(".*\\.(js|jsx|ts|tsx|vue)$")) return "frontend"; if (value.endsWith(".go")) return "go"; if (value.endsWith(".py")) return "python"; if (value.matches(".*\\.(c|cc|cpp|cxx|h|hpp)$")) return "cpp"; return null; }
    private int lineAt(String source, int offset) { int line = 1; for (int i = 0; i < Math.min(offset, source.length()); i++) if (source.charAt(i) == '\n') line++; return line; }
    private int closingBraceOffset(String source, int start) { int depth = 0; for (int i = Math.max(0, start); i < source.length(); i++) { char c = source.charAt(i); if (c == '{') depth++; else if (c == '}' && depth > 0 && --depth == 0) return i; } return -1; }
    private int leadingIndent(String source, int offset) { int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1; int indent = 0; while (lineStart + indent < source.length() && Character.isWhitespace(source.charAt(lineStart + indent)) && source.charAt(lineStart + indent) != '\n') indent++; return indent; }
    private int pythonEndOffset(String source, int start, int indent) { String[] lines = source.substring(start).split("\\R", -1); int offset = start; for (String line : lines) { if (!line.isBlank() && line.length() - line.stripLeading().length() <= indent) return Math.max(start - 1, offset - 1); offset += line.length() + 1; } return source.length() - 1; }
    private List<String> invocations(String body) { Matcher matcher = INVOCATION.matcher(body); List<String> result = new ArrayList<>(); while (matcher.find()) { String name = matcher.group(1); if (!List.of("if", "for", "while", "switch", "catch", "return", "sizeof", "func").contains(name)) result.add(name); } return result.stream().distinct().toList(); }
    private String normalize(String value) { return value == null ? "" : value.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*|#[^\\n]*", "").replaceAll("\\s+", " ").trim(); }
    private String hash(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
}
