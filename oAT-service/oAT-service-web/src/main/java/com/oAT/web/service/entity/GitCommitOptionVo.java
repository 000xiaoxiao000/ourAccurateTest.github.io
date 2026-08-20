package com.oAT.web.service.entity;

import java.io.Serializable;

public class GitCommitOptionVo implements Serializable {
    private String commitId;
    private String shortCommitId;
    private String message;
    private String author;
    private String commitTimeText;
    private Long commitTimestampMs;

    public GitCommitOptionVo() {
    }

    public GitCommitOptionVo(String commitId, String shortCommitId, String message, String author) {
        this.commitId = commitId;
        this.shortCommitId = shortCommitId;
        this.message = message;
        this.author = author;
    }

    public String getCommitId() {
        return commitId;
    }

    public void setCommitId(String commitId) {
        this.commitId = commitId;
    }

    public String getShortCommitId() {
        return shortCommitId;
    }

    public void setShortCommitId(String shortCommitId) {
        this.shortCommitId = shortCommitId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getCommitTimeText() {
        return commitTimeText;
    }

    public void setCommitTimeText(String commitTimeText) {
        this.commitTimeText = commitTimeText;
    }

    public Long getCommitTimestampMs() {
        return commitTimestampMs;
    }

    public void setCommitTimestampMs(Long commitTimestampMs) {
        this.commitTimestampMs = commitTimestampMs;
    }
}
