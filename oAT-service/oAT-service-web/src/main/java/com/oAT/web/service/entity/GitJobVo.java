package com.oAT.web.service.entity;

import java.io.Serializable;

public class GitJobVo implements Serializable {
    private String id;
    private int progress;
    private String progressName;
    private boolean finish;
    private boolean success;
    private String message;

    // Result
    private String fileName;
    private String cachePath; // The path where the zipped file is stored
    private String md5;
    private String repoCommitId;
    private Long pullDurationMs;
    private Long packageSizeBytes;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public String getProgressName() {
        return progressName;
    }

    public void setProgressName(String progressName) {
        this.progressName = progressName;
    }

    public boolean isFinish() {
        return finish;
    }

    public void setFinish(boolean finish) {
        this.finish = finish;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getCachePath() {
        return cachePath;
    }

    public void setCachePath(String cachePath) {
        this.cachePath = cachePath;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }

    public String getRepoCommitId() {
        return repoCommitId;
    }

    public void setRepoCommitId(String repoCommitId) {
        this.repoCommitId = repoCommitId;
    }

    public Long getPullDurationMs() {
        return pullDurationMs;
    }

    public void setPullDurationMs(Long pullDurationMs) {
        this.pullDurationMs = pullDurationMs;
    }

    public Long getPackageSizeBytes() {
        return packageSizeBytes;
    }

    public void setPackageSizeBytes(Long packageSizeBytes) {
        this.packageSizeBytes = packageSizeBytes;
    }
}

