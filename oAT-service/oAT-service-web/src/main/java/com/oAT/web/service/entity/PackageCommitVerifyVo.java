package com.oAT.web.service.entity;

import java.io.Serializable;

public class PackageCommitVerifyVo implements Serializable {
    private String runtimeCommitId;
    private String targetCommitId;
    private Boolean matched;
    private Boolean probeOnline;
    private String unavailableReason;

    public PackageCommitVerifyVo() {
    }

    public PackageCommitVerifyVo(String runtimeCommitId, String targetCommitId, Boolean matched) {
        this.runtimeCommitId = runtimeCommitId;
        this.targetCommitId = targetCommitId;
        this.matched = matched;
    }

    public PackageCommitVerifyVo(String runtimeCommitId, String targetCommitId, Boolean matched, Boolean probeOnline, String unavailableReason) {
        this.runtimeCommitId = runtimeCommitId;
        this.targetCommitId = targetCommitId;
        this.matched = matched;
        this.probeOnline = probeOnline;
        this.unavailableReason = unavailableReason;
    }

    public String getRuntimeCommitId() {
        return runtimeCommitId;
    }

    public void setRuntimeCommitId(String runtimeCommitId) {
        this.runtimeCommitId = runtimeCommitId;
    }

    public String getTargetCommitId() {
        return targetCommitId;
    }

    public void setTargetCommitId(String targetCommitId) {
        this.targetCommitId = targetCommitId;
    }

    public Boolean getMatched() {
        return matched;
    }

    public void setMatched(Boolean matched) {
        this.matched = matched;
    }

    public Boolean getProbeOnline() {
        return probeOnline;
    }

    public void setProbeOnline(Boolean probeOnline) {
        this.probeOnline = probeOnline;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public void setUnavailableReason(String unavailableReason) {
        this.unavailableReason = unavailableReason;
    }
}
