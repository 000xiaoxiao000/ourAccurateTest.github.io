package com.oAT.web.service.entity;

import java.io.Serializable;

public class GitPullEstimateVo implements Serializable {
    private Long estimatedDurationMs;
    private Long estimatedPackageSizeBytes;
    private String branch;
    private String commitId;
    private PackageCommitVerifyVo packageCommitVerify;

    public Long getEstimatedDurationMs() {
        return estimatedDurationMs;
    }

    public void setEstimatedDurationMs(Long estimatedDurationMs) {
        this.estimatedDurationMs = estimatedDurationMs;
    }

    public Long getEstimatedPackageSizeBytes() {
        return estimatedPackageSizeBytes;
    }

    public void setEstimatedPackageSizeBytes(Long estimatedPackageSizeBytes) {
        this.estimatedPackageSizeBytes = estimatedPackageSizeBytes;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getCommitId() {
        return commitId;
    }

    public void setCommitId(String commitId) {
        this.commitId = commitId;
    }

    public PackageCommitVerifyVo getPackageCommitVerify() {
        return packageCommitVerify;
    }

    public void setPackageCommitVerify(PackageCommitVerifyVo packageCommitVerify) {
        this.packageCommitVerify = packageCommitVerify;
    }
}
