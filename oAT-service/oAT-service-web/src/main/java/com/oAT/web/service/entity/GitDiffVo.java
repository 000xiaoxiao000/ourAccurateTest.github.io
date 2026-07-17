package com.oAT.web.service.entity;

import java.util.ArrayList;
import java.util.List;

public class GitDiffVo {
    private String className;
    private List<Integer> changedLines;
    private String changeType;
    private String originalPath;
    private String oldPath;
    private String newPath;
    private int renameScore;
    private String oldBlobId;
    private String newBlobId;
    private List<LineRange> oldRanges = new ArrayList<>();
    private List<LineRange> newRanges = new ArrayList<>();

    public GitDiffVo(String className, List<Integer> changedLines, String changeType) {
        this(className, changedLines, changeType, null);
    }

    public GitDiffVo(String className, List<Integer> changedLines, String changeType, String originalPath) {
        this.className = className;
        this.changedLines = changedLines;
        this.changeType = changeType;
        this.originalPath = originalPath;
        this.newPath = originalPath;
    }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public List<Integer> getChangedLines() { return changedLines; }
    public void setChangedLines(List<Integer> changedLines) { this.changedLines = changedLines; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getOriginalPath() { return originalPath; }
    public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }
    public String getOldPath() { return oldPath; }
    public void setOldPath(String oldPath) { this.oldPath = oldPath; }
    public String getNewPath() { return newPath; }
    public void setNewPath(String newPath) { this.newPath = newPath; }
    public int getRenameScore() { return renameScore; }
    public void setRenameScore(int renameScore) { this.renameScore = renameScore; }
    public String getOldBlobId() { return oldBlobId; }
    public void setOldBlobId(String oldBlobId) { this.oldBlobId = oldBlobId; }
    public String getNewBlobId() { return newBlobId; }
    public void setNewBlobId(String newBlobId) { this.newBlobId = newBlobId; }
    public List<LineRange> getOldRanges() { return oldRanges; }
    public void setOldRanges(List<LineRange> oldRanges) { this.oldRanges = oldRanges; }
    public List<LineRange> getNewRanges() { return newRanges; }
    public void setNewRanges(List<LineRange> newRanges) { this.newRanges = newRanges; }

    public static class LineRange {
        private int startLine;
        private int endLine;
        public LineRange() { }
        public LineRange(int startLine, int endLine) { this.startLine = startLine; this.endLine = endLine; }
        public int getStartLine() { return startLine; }
        public void setStartLine(int startLine) { this.startLine = startLine; }
        public int getEndLine() { return endLine; }
        public void setEndLine(int endLine) { this.endLine = endLine; }
    }
}
