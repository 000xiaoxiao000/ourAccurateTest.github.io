package com.oAT.ai.agent.fallback;

import java.lang.reflect.Method;

public final class FallbackToolMethodBinding {
    public final Object toolInstance;
    public final Method method;

    public FallbackToolMethodBinding(Object toolInstance, Method method) {
        this.toolInstance = toolInstance;
        this.method = method;
    }

    public Object toolInstance() {
        return toolInstance;
    }

    public Method method() {
        return method;
    }
}
