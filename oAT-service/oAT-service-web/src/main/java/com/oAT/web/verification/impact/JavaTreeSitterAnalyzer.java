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
    private static final Pattern PACKAGE    = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE       = Pattern.compile("(?m)(?:^|[;{}])\\s*(?:@[\\w.]+(?:\\([^\\n]*\\))?\\s*)*(?:public|protected|private|abstract|final|static|sealed|non-sealed|\\s)*(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern METHOD     = Pattern.compile("(?m)(?:^|[;{}])\\s*(?:@[\\w.]+(?:\\([^\\n]*\\))?\\s*)*(?:(?:public|protected|private|static|final|synchronized|abstract|native|default)\\s+)*([\\w$<>?,.\\[\\] ]+)\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*(?:throws[^\\{]+)?\\{");
    private static final Pattern INVOCATION = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\(");
    /** Captures "extends Foo" or "extends Foo, Bar" and "implements A, B" on a class/interface declaration line. */
    private static final Pattern EXTENDS    = Pattern.compile("\\bextends\\s+([\\w.$,\\s]+?)(?=\\s*(?:implements|\\{|$))");
    private static final Pattern IMPLEMENTS = Pattern.compile("\\bimplements\\s+([\\w.$,\\s]+?)(?=\\s*\\{)");
    /** Field with optional injection annotation: @Autowired / @Resource / @Inject before the type declaration. */
    private static final Pattern FIELD      = Pattern.compile("(?m)^\\s*(?:(@(?:Autowired|Resource|Inject|Value|Qualifier)[^\\n]*)\\n[^\\n]*\\n?)?\\s*(?:(?:private|protected|public|static|final|volatile|transient)\\s+)*(\\w[\\w$.<>?,\\[\\] ]*)\\s+([A-Za-z_$][\\w$]*)\\s*[;=]");
    private static final List<String> JAVA_CONTROL_KEYWORDS = List.of("if", "for", "while", "switch", "catch", "try", "synchronized");

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
        if (StringUtils.hasText(type)) {
            List<String> superTypes = superTypes(source);
            List<String> interfaces = implementedInterfaces(source);
            List<String> allSuper = new ArrayList<>(superTypes); allSuper.addAll(interfaces);
            List<String> fieldTypes = fieldTypes(source);
            List<String> injectFields = injectAnnotatedFields(source);
            symbols.add(snapshot("java://" + qualifiedType, SymbolKind.TYPE, qualifiedType, type, path,
                    1, lines(source), source, List.of(), allSuper, fieldTypes, injectFields));
        }
        Matcher matcher = METHOD.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(2);
            if (JAVA_CONTROL_KEYWORDS.contains(name)) continue;
            String parameters = normalizeParameters(matcher.group(3));
            int begin = lineAt(source, matcher.start());
            int endOffset = closingBraceOffset(source, matcher.end() - 1);
            int end = endOffset >= 0 ? lineAt(source, endOffset) : begin;
            String body = endOffset >= 0 ? source.substring(matcher.start(), endOffset + 1) : matcher.group();
            String signature = name + "(" + parameters + ")";
            String key = "java://" + qualifiedType + "#" + signature;
            symbols.add(snapshot(key, SymbolKind.METHOD, qualifiedType + "." + name, signature, path,
                    begin, end, body, invocations(body), List.of(), List.of(), List.of()));
        }
        return symbols;
    }

    private SymbolSnapshot snapshot(String key, SymbolKind kind, String qualifiedName, String signature,
                                    String path, int start, int end, String body, List<String> invocations,
                                    List<String> superTypes, List<String> fieldTypes, List<String> injectFields) {
        return new SymbolSnapshot(key, kind, "java", qualifiedName, signature, path,
                new LineRange(start, end), hash(normalize(body)), hash(normalize(body)), hash(signature),
                clip(body), invocations, superTypes, fieldTypes, injectFields);
    }

    private List<String> superTypes(String source) {
        Matcher m = EXTENDS.matcher(source);
        if (!m.find()) return List.of();
        return splitTypeList(m.group(1));
    }

    private List<String> implementedInterfaces(String source) {
        Matcher m = IMPLEMENTS.matcher(source);
        if (!m.find()) return List.of();
        return splitTypeList(m.group(1));
    }

    private List<String> fieldTypes(String source) {
        Matcher m = FIELD.matcher(source);
        List<String> result = new ArrayList<>();
        while (m.find()) {
            String typeName = simpleTypeName(m.group(2));
            if (StringUtils.hasText(typeName) && Character.isUpperCase(typeName.charAt(0))) result.add(typeName);
        }
        return result.stream().distinct().toList();
    }

    private List<String> injectAnnotatedFields(String source) {
        Matcher m = FIELD.matcher(source);
        List<String> result = new ArrayList<>();
        while (m.find()) {
            if (m.group(1) != null) {
                String typeName = simpleTypeName(m.group(2));
                if (StringUtils.hasText(typeName) && Character.isUpperCase(typeName.charAt(0))) result.add(typeName);
            }
        }
        return result.stream().distinct().toList();
    }

    private List<String> splitTypeList(String csv) {
        List<String> result = new ArrayList<>();
        for (String part : csv.split(",")) {
            String name = simpleTypeName(part.trim());
            if (StringUtils.hasText(name)) result.add(name);
        }
        return List.copyOf(result);
    }

    /** Strips generic parameters and returns the base simple type name. */
    private String simpleTypeName(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        int angle = raw.indexOf('<');
        String base = angle >= 0 ? raw.substring(0, angle) : raw;
        base = base.trim();
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1) : base;
    }
    private String match(Pattern pattern, String source, int group) { Matcher matcher = pattern.matcher(source); return matcher.find() ? matcher.group(group) : ""; }
    private String normalizeParameters(String value) { return value == null || value.isBlank() ? "" : value.replaceAll("@[\\w.]+", "").replaceAll("\\s+", " ").trim(); }
    private List<String> invocations(String body) { Matcher m = INVOCATION.matcher(body); List<String> result = new ArrayList<>(); while (m.find()) { String value = m.group(1); if (!JAVA_CONTROL_KEYWORDS.contains(value) && !List.of("return", "new").contains(value)) result.add(value); } return result.stream().distinct().toList(); }
    private int lineAt(String source, int offset) { int line = 1; for (int i = 0; i < offset; i++) if (source.charAt(i) == '\n') line++; return line; }
    private int closingBraceOffset(String source, int start) { int depth = 0; for (int i = start; i < source.length(); i++) { char c = source.charAt(i); if (c == '{') depth++; else if (c == '}' && --depth == 0) return i; } return -1; }
    private int lines(String source) { return lineAt(source, source.length()); }
    private String lines(String source, int start, int end) { String[] all = source.split("\\R", -1); StringBuilder result = new StringBuilder(); for (int i = Math.max(1, start); i <= Math.min(end, all.length); i++) result.append(all[i - 1]).append('\n'); return result.toString(); }
    private String normalize(String value) { return value.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "").replaceAll("\\s+", " ").trim(); }
    private String hash(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String clip(String value) { return value.length() <= 4000 ? value : value.substring(0, 4000); }
}
