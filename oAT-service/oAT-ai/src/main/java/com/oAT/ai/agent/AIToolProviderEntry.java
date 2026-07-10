package com.oAT.ai.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

final class AIToolProviderEntry {
    final ToolSpecification specification;
    final ToolExecutor executor;

    AIToolProviderEntry(ToolSpecification specification, ToolExecutor executor) {
        this.specification = specification;
        this.executor = executor;
    }
}
