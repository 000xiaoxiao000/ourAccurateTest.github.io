package com.oAT.ai.agent.fallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FallbackReport {
    public String toolName;
    public String candidateToolName;
    public String strategy;
    public boolean retried;
    public boolean success;
    public String errorMessage;
    public String schemaDescription;
    public int resultLength;
    public long startedAt;
    public long finishedAt;
    public final List<FallbackParameterReport> parameterReports = new ArrayList<>();

    public FallbackReport copy() {
        FallbackReport copied = new FallbackReport();
        copied.toolName = toolName;
        copied.candidateToolName = candidateToolName;
        copied.strategy = strategy;
        copied.retried = retried;
        copied.success = success;
        copied.errorMessage = errorMessage;
        copied.schemaDescription = schemaDescription;
        copied.resultLength = resultLength;
        copied.startedAt = startedAt;
        copied.finishedAt = finishedAt;
        for (FallbackParameterReport parameterReport : parameterReports) {
            copied.parameterReports.add(parameterReport.copy());
        }
        return copied;
    }

    public String describe() {
        List<String> paramDescriptions = new ArrayList<>();
        for (FallbackParameterReport parameterReport : parameterReports) {
            paramDescriptions.add(parameterReport.describe());
        }
        return "tool=" + toolName
                + ", candidateTool=" + candidateToolName
                + ", strategy=" + strategy
                + ", retried=" + retried
                + ", success=" + success
                + ", durationMs=" + Math.max(0, finishedAt - startedAt)
                + ", resultLength=" + resultLength
                + ", error=" + errorMessage
                + ", schema=" + schemaDescription
                + ", params=" + paramDescriptions;
    }

    public String getToolName() {
        return toolName;
    }

    public String getCandidateToolName() {
        return candidateToolName;
    }

    public String getEffectiveToolName() {
        return candidateToolName != null && !candidateToolName.isEmpty() ? candidateToolName : toolName;
    }

    public String getStrategy() {
        return strategy;
    }

    public boolean isRetried() {
        return retried;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getSchemaDescription() {
        return schemaDescription;
    }

    public int getResultLength() {
        return resultLength;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public long getDurationMs() {
        return Math.max(0, finishedAt - startedAt);
    }

    public List<FallbackParameterReport> getParameterReports() {
        List<FallbackParameterReport> copies = new ArrayList<>();
        for (FallbackParameterReport parameterReport : parameterReports) {
            copies.add(parameterReport.copy());
        }
        return Collections.unmodifiableList(copies);
    }
}
