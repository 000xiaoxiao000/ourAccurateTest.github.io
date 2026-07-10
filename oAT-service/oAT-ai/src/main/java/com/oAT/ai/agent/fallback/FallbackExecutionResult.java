package com.oAT.ai.agent.fallback;

public final class FallbackExecutionResult {
    public final boolean success;
    public final Object result;
    public final Exception error;
    public final String errorMessage;

    private FallbackExecutionResult(boolean success, Object result, Exception error, String errorMessage) {
        this.success = success;
        this.result = result;
        this.error = error;
        this.errorMessage = errorMessage;
    }

    public static FallbackExecutionResult success(Object result) {
        return new FallbackExecutionResult(true, result, null, null);
    }

    public static FallbackExecutionResult failure(Exception error) {
        return new FallbackExecutionResult(false, null, error, error != null ? error.getMessage() : null);
    }

    public boolean success() {
        return success;
    }

    public Object result() {
        return result;
    }

    public Exception error() {
        return error;
    }

    public String errorMessage() {
        return errorMessage;
    }
}
