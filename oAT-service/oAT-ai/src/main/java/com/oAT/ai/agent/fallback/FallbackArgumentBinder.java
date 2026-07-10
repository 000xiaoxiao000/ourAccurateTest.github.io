package com.oAT.ai.agent.fallback;

import dev.langchain4j.agent.tool.P;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FallbackArgumentBinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(FallbackArgumentBinder.class);

    private final FallbackTypeConverter typeConverter = new FallbackTypeConverter();

    /**
     * 判断参数是否为可注入的上下文参数（如 @V、@MemoryId 等）
     */
    public boolean isInjectableParameter(java.lang.reflect.Parameter param) {
        return param.isAnnotationPresent(dev.langchain4j.service.V.class)
                || param.isAnnotationPresent(dev.langchain4j.service.MemoryId.class);
    }

    private Object[] buildMethodArguments(Method method, Map<String, Object> args,
                                          FallbackToolMethodSchema schema, boolean relaxedMode,
                                          FallbackReport report) {
        java.lang.reflect.Parameter[] params = method.getParameters();
        Object[] invokeArgs = new Object[params.length];
        Map<String, Object> normalizedArgs = new LinkedHashMap<>();
        Map<String, Object> originalArgs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            originalArgs.put(entry.getKey(), entry.getValue());
            normalizedArgs.put(normalizeName(entry.getKey()), entry.getValue());
        }

        Set<String> consumedKeys = new LinkedHashSet<>();
        List<String> bindingDiagnostics = new ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            java.lang.reflect.Parameter param = params[i];
            String paramName = param.getName();
            Class<?> paramType = param.getType();

            if (isInjectableParameter(param)) {
                invokeArgs[i] = null;
                bindingDiagnostics.add(paramName + "=<injectable>");
                continue;
            }

            String annotatedName = getAnnotatedParameterName(param);
            List<String> candidates = schema != null
                    ? schema.getCandidatesFor(paramName, annotatedName)
                    : buildCandidateNames(paramName, annotatedName);
            MatchedArgument matchedArgument = getArgumentValue(originalArgs, normalizedArgs, candidates,
                    relaxedMode, consumedKeys, schema, paramType);
            Object value = matchedArgument.value;
            if (value == null && (containsArgument(originalArgs, normalizedArgs, paramName)
                    || containsArgument(originalArgs, normalizedArgs, annotatedName))) {
                invokeArgs[i] = null;
                bindingDiagnostics.add(paramName + "=<explicit-null via " + matchedArgument.matchedKey + ">"
                        + (matchedArgument.strategy != null ? " [" + matchedArgument.strategy + "]" : ""));
                if (report != null) {
                    report.parameterReports.add(FallbackParameterReport.explicitNull(
                            paramName, matchedArgument.matchedKey, matchedArgument.strategy, paramType.getSimpleName()));
                }
                if (matchedArgument.matchedKey != null && !matchedArgument.matchedKey.startsWith("<")) {
                    consumedKeys.add(normalizeName(matchedArgument.matchedKey));
                }
                continue;
            }

            if (value != null) {
                try {
                    invokeArgs[i] = typeConverter.convertType(value, paramType, param.getParameterizedType());
                    bindingDiagnostics.add(paramName + "<-" + matchedArgument.matchedKey
                            + " [" + matchedArgument.strategy + "] "
                            + describeConversion(value, invokeArgs[i], paramType));
                    if (report != null) {
                        report.parameterReports.add(FallbackParameterReport.success(
                                paramName, matchedArgument.matchedKey, matchedArgument.strategy,
                                safeTypeName(value), paramType.getSimpleName(), false, false,
                                describeConversion(value, invokeArgs[i], paramType)));
                    }
                    if (matchedArgument.matchedKey != null && !matchedArgument.matchedKey.startsWith("<")) {
                        consumedKeys.add(normalizeName(matchedArgument.matchedKey));
                    }
                } catch (Exception conversionError) {
                    bindingDiagnostics.add(paramName + "<-" + matchedArgument.matchedKey
                            + " [" + matchedArgument.strategy + "] conversion-failed: "
                            + describeConversionFailure(value, paramType, conversionError));
                    if (report != null) {
                        report.parameterReports.add(FallbackParameterReport.failure(
                                paramName, matchedArgument.matchedKey, matchedArgument.strategy,
                                safeTypeName(value), paramType.getSimpleName(),
                                describeConversionFailure(value, paramType, conversionError)));
                    }
                    throw conversionError;
                }
            } else {
                Object fallbackValue = null;
                String fallbackStrategy = null;
                if (paramType.isPrimitive()) {
                    if (!relaxedMode) {
                        String message = "缺少必填基础类型参数: " + paramName + " (" + paramType.getSimpleName() + ")";
                        if (report != null) {
                            report.parameterReports.add(FallbackParameterReport.failure(
                                    paramName, "<missing>", "missing-required-primitive",
                                    null, paramType.getSimpleName(), message));
                        }
                        throw new IllegalArgumentException(message);
                    }
                    fallbackValue = getSemanticDefaultValue(param, schema, paramName, annotatedName, paramType);
                    fallbackStrategy = fallbackValue != null ? "semantic-default" : "primitive-default";
                }
                if (report != null) {
                    report.parameterReports.add(FallbackParameterReport.defaulted(
                            paramName, paramType.getSimpleName(), fallbackStrategy,
                            fallbackValue != null ? String.valueOf(fallbackValue) : null));
                }
                invokeArgs[i] = fallbackValue != null ? fallbackValue : typeConverter.getDefaultForType(paramType);
                bindingDiagnostics.add(paramName + "=<default:" + (fallbackStrategy != null ? fallbackStrategy : "default")
                        + "> => " + String.valueOf(invokeArgs[i]));
            }
        }

        LOGGER.debug("Fallback binding diagnostics [{}][{}]: {}", method.getName(),
                relaxedMode ? "relaxed" : "strict", String.join(", ", bindingDiagnostics));
        return invokeArgs;
    }

    private String describeConversion(Object originalValue, Object convertedValue, Class<?> targetType) {
        return "convert " + safeTypeName(originalValue) + " -> " + targetType.getSimpleName()
                + " => " + safeTypeName(convertedValue);
    }

    private String describeConversionFailure(Object originalValue, Class<?> targetType, Exception error) {
        return safeTypeName(originalValue) + " -> " + targetType.getSimpleName() + " failed: " + error.getMessage();
    }

    private String safeTypeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    public String getAnnotatedParameterName(java.lang.reflect.Parameter param) {
        P annotation = param.getAnnotation(P.class);
        if (annotation == null) {
            return null;
        }
        return annotation.value();
    }

    private boolean containsArgument(Map<String, Object> originalArgs, Map<String, Object> normalizedArgs, String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) {
                continue;
            }
            if (originalArgs.containsKey(candidate) || normalizedArgs.containsKey(normalizeName(candidate))) {
                return true;
            }
        }
        return false;
    }

    private MatchedArgument getArgumentValue(Map<String, Object> originalArgs, Map<String, Object> normalizedArgs,
                                             List<String> candidates, boolean relaxedMode,
                                             Set<String> consumedKeys, FallbackToolMethodSchema schema, Class<?> targetType) {
        List<ArgumentMatchScore> scoredMatches = scoreArgumentMatches(originalArgs, normalizedArgs, candidates,
                consumedKeys, schema, targetType);
        if (!scoredMatches.isEmpty()) {
            ArgumentMatchScore bestMatch = scoredMatches.get(0);
            return MatchedArgument.of(bestMatch.value, bestMatch.matchedKey, bestMatch.strategy);
        }
        if (!relaxedMode) {
            if (candidates.size() == 1 && !originalArgs.isEmpty()) {
                return MatchedArgument.of(originalArgs.values().iterator().next(), "<single-arg>", "single-value-fallback");
            }
            return MatchedArgument.notMatched();
        }

        String candidateKey = selectRelaxedArgumentKey(normalizedArgs.keySet(), candidates, consumedKeys);
        if (candidateKey != null) {
            return MatchedArgument.of(normalizedArgs.get(candidateKey), candidateKey, "relaxed-contains");
        }
        if (normalizedArgs.size() == 1) {
            return MatchedArgument.of(normalizedArgs.values().iterator().next(), "<single-normalized-arg>", "relaxed-single-value");
        }
        return MatchedArgument.notMatched();
    }

    private List<ArgumentMatchScore> scoreArgumentMatches(Map<String, Object> originalArgs,
                                                          Map<String, Object> normalizedArgs,
                                                          List<String> candidates,
                                                          Set<String> consumedKeys,
                                                          FallbackToolMethodSchema schema,
                                                          Class<?> targetType) {
        List<ArgumentMatchScore> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (originalArgs.containsKey(candidate)) {
                String normalizedKey = normalizeName(candidate);
                if (!consumedKeys.contains(normalizedKey)) {
                    matches.add(new ArgumentMatchScore(originalArgs.get(candidate), candidate, "exact",
                            scoreMatch(candidate, targetType, schema, true, false)));
                }
            }
            String normalizedCandidate = normalizeName(candidate);
            if (!normalizedCandidate.isEmpty() && normalizedArgs.containsKey(normalizedCandidate)
                    && !consumedKeys.contains(normalizedCandidate)) {
                matches.add(new ArgumentMatchScore(normalizedArgs.get(normalizedCandidate), normalizedCandidate,
                        "normalized", scoreMatch(normalizedCandidate, targetType, schema, false, true)));
            }
        }
        matches.sort(Comparator.comparingInt(ArgumentMatchScore::score).reversed());
        return matches;
    }

    private int scoreMatch(String key, Class<?> targetType, FallbackToolMethodSchema schema,
                           boolean exact, boolean normalized) {
        int score = exact ? 100 : 70;
        if (normalized) {
            score += 5;
        }
        if (schema != null && schema.matchesTypeHint(key, targetType)) {
            score += 20;
        }
        return score;
    }

    public List<String> buildCandidateNames(String paramName, String annotatedName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidateName(candidates, paramName);
        addCandidateName(candidates, annotatedName);
        return new ArrayList<>(candidates);
    }

    private void addCandidateName(Set<String> candidates, String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return;
        }
        String trimmed = rawName.trim();
        candidates.add(trimmed);
        candidates.add(toSnakeCase(trimmed));
        candidates.add(toKebabCase(trimmed));
        candidates.addAll(extractSemanticAliases(trimmed));
    }

    private Set<String> extractSemanticAliases(String rawName) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        String compact = rawName.replaceAll("[（(].*?[)）]", " ")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}]+", " ")
                .trim();
        if (compact.isEmpty()) {
            return aliases;
        }
        aliases.add(compact);
        String[] segments = compact.split("\\s+");
        for (String segment : segments) {
            if (!segment.isEmpty()) {
                aliases.add(segment);
                aliases.add(toSnakeCase(segment));
                aliases.add(toKebabCase(segment));
            }
        }
        return aliases;
    }

    private String toSnakeCase(String value) {
        return splitCamelCase(value, "_");
    }

    private String toKebabCase(String value) {
        return splitCamelCase(value, "-");
    }

    private String splitCamelCase(String value, String delimiter) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.replaceAll("([a-z0-9])([A-Z])", "$1" + delimiter + "$2").toLowerCase(Locale.ROOT);
    }

    private String selectRelaxedArgumentKey(Set<String> availableKeys, List<String> candidates, Set<String> consumedKeys) {
        for (String candidate : candidates) {
            String normalizedCandidate = normalizeName(candidate);
            for (String availableKey : availableKeys) {
                if (consumedKeys.contains(availableKey)) {
                    continue;
                }
                if (availableKey.contains(normalizedCandidate) || normalizedCandidate.contains(availableKey)) {
                    return availableKey;
                }
            }
        }
        return null;
    }

    public FallbackExecutionResult execute(FallbackToolMethodBinding binding, Map<String, Object> args,
                                                           FallbackToolMethodSchema schema, boolean relaxedMode,
                                                           FallbackReport report) {
        try {
            if (report != null) {
                report.strategy = relaxedMode ? "relaxed" : "strict";
            }
            Object[] invokeArgs = buildMethodArguments(binding.method, args, schema, relaxedMode, report);
            binding.method.setAccessible(true);
            Object result = binding.method.invoke(binding.toolInstance, invokeArgs);
            return FallbackExecutionResult.success(result);
        } catch (Exception e) {
            String strategy = relaxedMode ? "relaxed" : "strict";
            LOGGER.debug("Fallback {} binding failed for tool {}: {}",
                    strategy, binding.method.getName(), e.getMessage(), e);
            return FallbackExecutionResult.failure(e);
        }
    }


    private Object getSemanticDefaultValue(java.lang.reflect.Parameter param, FallbackToolMethodSchema schema,
                                           String paramName, String annotatedName, Class<?> paramType) {
        String semanticName = resolveSemanticParameterName(param, schema, paramName, annotatedName);
        String normalizedName = normalizeName(semanticName);
        if (normalizedName.isEmpty()) {
            normalizedName = normalizeName(paramName);
        }
        if (paramType == int.class || paramType == Integer.class) {
            if (normalizedName.contains("limit") || normalizedName.contains("size")
                    || normalizedName.contains("count") || normalizedName.contains("top")
                    || normalizedName.contains("pageSize".toLowerCase(Locale.ROOT))) {
                Integer parsed = extractIntegerDefault(semanticName);
                return parsed != null ? parsed : 10;
            }
            if (normalizedName.contains("page") || normalizedName.contains("index")) {
                Integer parsed = extractIntegerDefault(semanticName);
                return parsed != null ? parsed : 1;
            }
        }
        if (paramType == long.class || paramType == Long.class) {
            if (normalizedName.contains("limit") || normalizedName.contains("size") || normalizedName.contains("count")) {
                Integer parsed = extractIntegerDefault(semanticName);
                return parsed != null ? parsed.longValue() : 10L;
            }
        }
        if (paramType == boolean.class || paramType == Boolean.class) {
            if (normalizedName.contains("enable") || normalizedName.contains("enabled")
                    || normalizedName.contains("include") || normalizedName.contains("with")) {
                return false;
            }
        }
        return null;
    }

    private String resolveSemanticParameterName(java.lang.reflect.Parameter param, FallbackToolMethodSchema schema,
                                                String paramName, String annotatedName) {
        if (annotatedName != null && !annotatedName.trim().isEmpty()) {
            return annotatedName.trim();
        }
        if (schema != null) {
            List<String> candidates = schema.getCandidatesFor(paramName, annotatedName);
            if (candidates != null && !candidates.isEmpty()) {
                return candidates.get(0);
            }
        }
        if (param != null && param.isNamePresent() && param.getName() != null && !param.getName().trim().isEmpty()) {
            return param.getName().trim();
        }
        return paramName;
    }

    private Integer extractIntegerDefault(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = Pattern.compile("默认\\s*(\\d+)|default\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) {
            String matched = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (matched != null && !matched.isEmpty()) {
                return Integer.parseInt(matched);
            }
        }
        Matcher firstNumber = Pattern.compile("(\\d+)").matcher(text);
        if (firstNumber.find()) {
            return Integer.parseInt(firstNumber.group(1));
        }
        return null;
    }

    private String normalizeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String normalized = rawName.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static final class ArgumentMatchScore {
        private final Object value;
        private final String matchedKey;
        private final String strategy;
        private final int score;

        private ArgumentMatchScore(Object value, String matchedKey, String strategy, int score) {
            this.value = value;
            this.matchedKey = matchedKey;
            this.strategy = strategy;
            this.score = score;
        }

        private int score() {
            return score;
        }
    }

    private static final class MatchedArgument {
        private final Object value;
        private final String matchedKey;
        private final String strategy;

        private MatchedArgument(Object value, String matchedKey, String strategy) {
            this.value = value;
            this.matchedKey = matchedKey;
            this.strategy = strategy;
        }

        private static MatchedArgument of(Object value, String matchedKey, String strategy) {
            return new MatchedArgument(value, matchedKey, strategy);
        }

        private static MatchedArgument notMatched() {
            return new MatchedArgument(null, "<unmatched>", "none");
        }
    }

}
