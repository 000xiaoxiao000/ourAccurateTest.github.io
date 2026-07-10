package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.Date;

public class VersionItemVo implements Serializable {
    private String id;
    private String appId;
    private String projectId;
    private String versionNumber;
    private String describe;
    private String programFile;
    private String programName;
    private String[] configFile;
    private String[] databaseFile;

    private String sourceType;
    private String repoBranch;
    private String repoCommitId;
    private String setAsCurrent;

    private Date createTime;
    private boolean fileExist;
    private boolean hasReport;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(String versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getDescribe() {
        return describe;
    }

    public void setDescribe(String describe) {
        this.describe = describe;
    }

    public String getProgramFile() {
        return programFile;
    }

    public void setProgramFile(String programFile) {
        this.programFile = programFile;
    }

    public String getProgramName() {
        return programName;
    }

    public void setProgramName(String programName) {
        this.programName = programName;
    }

    public String[] getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String[] configFile) {
        this.configFile = configFile;
    }

    public String[] getDatabaseFile() {
        return databaseFile;
    }

    public void setDatabaseFile(String[] databaseFile) {
        this.databaseFile = databaseFile;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getRepoBranch() {
        return repoBranch;
    }

    public void setRepoBranch(String repoBranch) {
        this.repoBranch = repoBranch;
    }

    public boolean isFileExist() {
        return fileExist;
    }

    public void setFileExist(boolean fileExist) {
        this.fileExist = fileExist;
    }

    public String getRepoCommitId() {
        return repoCommitId;
    }

    public void setRepoCommitId(String repoCommitId) {
        this.repoCommitId = repoCommitId;
    }

    public String getSetAsCurrent() {
        return setAsCurrent;
    }

    public void setSetAsCurrent(String setAsCurrent) {
        this.setAsCurrent = setAsCurrent;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public boolean isHasReport() {
        return hasReport;
    }

    public void setHasReport(boolean hasReport) {
        this.hasReport = hasReport;
    }
}
