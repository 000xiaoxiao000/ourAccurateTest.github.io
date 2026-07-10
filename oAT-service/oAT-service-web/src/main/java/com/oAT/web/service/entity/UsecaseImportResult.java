package com.oAT.web.service.entity;

import java.util.ArrayList;
import java.util.List;

public class UsecaseImportResult {
    private int totalCount;
    private int successCount;
    private int failureCount;
    private List<UsecaseImportError> errors = new ArrayList<>();

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public List<UsecaseImportError> getErrors() {
        return errors;
    }

    public void setErrors(List<UsecaseImportError> errors) {
        this.errors = errors;
    }

    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}
