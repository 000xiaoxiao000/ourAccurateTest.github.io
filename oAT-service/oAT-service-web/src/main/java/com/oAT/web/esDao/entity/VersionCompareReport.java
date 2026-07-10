package com.oAT.web.esDao.entity;


import java.io.Serializable;
import java.util.Date;

public class VersionCompareReport implements Serializable {
    private String jobId;
    private String projectId;
    private String appId;
    private String jobName;
    private String jobLog;
    private String sourceVersion;
    private String targetVersion;
    private String gitBranch;
    private String gitOldCommit;
    private String gitNewCommit;
    /**
     有差异的项
     */
    private Difference[] differences;
    /**
     影响的用例及关联项
     */
    private ImpactCase[] cases;

    // 统计字段：类/方法/影响用例计数
    private int addClassCount;
    private int updateClassCount;
    private int deleteClassCount;

    private int addMethodCount;
    private int updateMethodCount;
    private int deleteMethodCount;

    private int impactCaseCount;

    /**
     * 与VersionCenterIndex.createTime 表示的是同一个值
     */
    private Date createTime;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getJobLog() {
        return jobLog;
    }

    public void setJobLog(String jobLog) {
        this.jobLog = jobLog;
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

    public Difference[] getDifferences() {
        return differences;
    }

    public void setDifferences(Difference[] differences) {
        this.differences = differences;
    }

    public ImpactCase[] getCases() {
        return cases;
    }

    public void setCases(ImpactCase[] cases) {
        this.cases = cases;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
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

    public static class Difference {
        String type;    //
        String model;// add update delete
        String value;

        public Difference() {
        }

        public Difference(String type, String model, String value) {
            this.type = type;
            this.model = model;
            this.value = value;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    // 影响用例
    public static class ImpactCase {
        private String caseId;
        private String[] differences;

        public ImpactCase(String caseId, String[] differences) {
            this.caseId = caseId;
            this.differences = differences;
        }

        public ImpactCase() {
        }

        public String getCaseId() {
            return caseId;
        }

        public void setCaseId(String caseId) {
            this.caseId = caseId;
        }

        public String[] getDifferences() {
            return differences;
        }

        public void setDifferences(String[] differences) {
            this.differences = differences;
        }
    }
}
