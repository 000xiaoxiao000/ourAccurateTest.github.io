package com.oAT.ai.agent.fallback;

public final class FallbackParameterReport {
    private final String parameterName;
    private final String matchedKey;
    private final String strategy;
    private final String sourceType;
    private final String targetType;
    private final boolean usedDefault;
    private final boolean explicitNull;
    private final String message;

    private FallbackParameterReport(String parameterName, String matchedKey, String strategy,
                                    String sourceType, String targetType,
                                    boolean usedDefault, boolean explicitNull, String message) {
        this.parameterName = parameterName;
        this.matchedKey = matchedKey;
        this.strategy = strategy;
        this.sourceType = sourceType;
        this.targetType = targetType;
        this.usedDefault = usedDefault;
        this.explicitNull = explicitNull;
        this.message = message;
    }

    public FallbackParameterReport copy() {
        return new FallbackParameterReport(parameterName, matchedKey, strategy, sourceType, targetType,
                usedDefault, explicitNull, message);
    }

    public static FallbackParameterReport success(String parameterName, String matchedKey, String strategy,
                                                  String sourceType, String targetType,
                                                  boolean usedDefault, boolean explicitNull, String message) {
        return new FallbackParameterReport(parameterName, matchedKey, strategy, sourceType, targetType,
                usedDefault, explicitNull, message);
    }

    public static FallbackParameterReport failure(String parameterName, String matchedKey, String strategy,
                                                  String sourceType, String targetType, String message) {
        return new FallbackParameterReport(parameterName, matchedKey, strategy, sourceType, targetType,
                false, false, message);
    }

    public static FallbackParameterReport defaulted(String parameterName, String targetType,
                                                    String strategy, String defaultValue) {
        String resolvedStrategy = strategy != null ? strategy : "default";
        String message = defaultValue != null
                ? "used default value: " + defaultValue
                : "used default value";
        return new FallbackParameterReport(parameterName, "<default>", resolvedStrategy,
                null, targetType, true, false, message);
    }

    public static FallbackParameterReport explicitNull(String parameterName, String matchedKey,
                                                       String strategy, String targetType) {
        return new FallbackParameterReport(parameterName, matchedKey, strategy,
                null, targetType, false, true, "explicit null");
    }

    public String describe() {
        return parameterName + "<-" + matchedKey + " [" + strategy + "] " + message;
    }

    public String getParameterName() {
        return parameterName;
    }

    public String getMatchedKey() {
        return matchedKey;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getTargetType() {
        return targetType;
    }

    public boolean isUsedDefault() {
        return usedDefault;
    }

    public boolean isExplicitNull() {
        return explicitNull;
    }

    public String getMessage() {
        return message;
    }
}
