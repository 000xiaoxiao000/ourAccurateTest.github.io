package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.Date;

public class VersionCompareReportVo implements Serializable {
    private String id;
    private String name;
    private Date createTime;
    // 目标版本
    private String sourceVersion;
    // 源版本
    private String targetVersion;
    // Git 元信息
    private String gitBranch;
    private String gitOldCommit;
    private String gitNewCommit;

    // 统计字段
    private int addClassCount;
    private int updateClassCount;
    private int deleteClassCount;
    private int addMethodCount;
    private int updateMethodCount;
    private int deleteMethodCount;
    private int impactCaseCount;

    public VersionCompareReportVo(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public VersionCompareReportVo() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(String sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public String getGitBranch() {
        return gitBranch;
    }

    public void setGitBranch(String gitBranch) {
        this.gitBranch = gitBranch;
    }

    public String getGitOldCommit() {
        return gitOldCommit;
    }

    public void setGitOldCommit(String gitOldCommit) {
        this.gitOldCommit = gitOldCommit;
    }

    public String getGitNewCommit() {
        return gitNewCommit;
    }

    public void setGitNewCommit(String gitNewCommit) {
        this.gitNewCommit = gitNewCommit;
    }

    public int getAddClassCount() {
        return addClassCount;
    }

    public void setAddClassCount(int addClassCount) {
        this.addClassCount = addClassCount;
    }

    public int getUpdateClassCount() {
        return updateClassCount;
    }

    public void setUpdateClassCount(int updateClassCount) {
        this.updateClassCount = updateClassCount;
    }

    public int getDeleteClassCount() {
        return deleteClassCount;
    }

    public void setDeleteClassCount(int deleteClassCount) {
        this.deleteClassCount = deleteClassCount;
    }

    public int getAddMethodCount() {
        return addMethodCount;
    }

    public void setAddMethodCount(int addMethodCount) {
        this.addMethodCount = addMethodCount;
    }

    public int getUpdateMethodCount() {
        return updateMethodCount;
    }

    public void setUpdateMethodCount(int updateMethodCount) {
        this.updateMethodCount = updateMethodCount;
    }

    public int getDeleteMethodCount() {
        return deleteMethodCount;
    }

    public void setDeleteMethodCount(int deleteMethodCount) {
        this.deleteMethodCount = deleteMethodCount;
    }

    public int getImpactCaseCount() {
        return impactCaseCount;
    }

    public void setImpactCaseCount(int impactCaseCount) {
        this.impactCaseCount = impactCaseCount;
    }
}
