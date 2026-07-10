package com.oAT.web.service.entity;

import java.util.List;

public class GitDiffVo {
    private String className;
    private List<Integer> changedLines;
    private String changeType; // ADD, MODIFY, DELETE
    private String originalPath; // The full path in the git repository

    public GitDiffVo(String className, List<Integer> changedLines, String changeType) {
        this.className = className;
        this.changedLines = changedLines;
        this.changeType = changeType;
    }

    public GitDiffVo(String className, List<Integer> changedLines, String changeType, String originalPath) {
        this.className = className;
        this.changedLines = changedLines;
        this.changeType = changeType;
        this.originalPath = originalPath;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public List<Integer> getChangedLines() {
        return changedLines;
    }

    public void setChangedLines(List<Integer> changedLines) {
        this.changedLines = changedLines;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public void setOriginalPath(String originalPath) {
        this.originalPath = originalPath;
    }
}

