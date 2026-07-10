package com.oAT.ai.agent.fallback;

import java.lang.reflect.Type;
import java.util.List;

public final class FallbackToolParameterSchema {
    private final String paramName;
    private final String annotatedName;
    private final Class<?> paramType;
    private final Type genericType;
    private final List<String> candidates;

    public FallbackToolParameterSchema(String paramName, String annotatedName, Class<?> paramType,
                                       Type genericType, List<String> candidates) {
        this.paramName = paramName;
        this.annotatedName = annotatedName;
        this.paramType = paramType;
        this.genericType = genericType;
        this.candidates = candidates;
    }

    public String paramName() {
        return paramName;
    }

    public String annotatedName() {
        return annotatedName;
    }

    public Class<?> paramType() {
        return paramType;
    }

    public Type genericType() {
        return genericType;
    }

    public List<String> candidates() {
        return candidates;
    }
}
