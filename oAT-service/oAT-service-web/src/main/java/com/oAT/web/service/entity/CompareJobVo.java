package com.oAT.web.service.entity;

import com.oAT.web.common.compare.CompareResult;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

public class CompareJobVo implements Serializable {
    private String name;
    private String id;
    private String projectId;
    private String appId;
    private String sourceFile;
    private String targetFile;
    private Date begin;
    private Date end;
    private int progress;
    private String progressName;
    private String log;
    //private Job.JobState state;
    private boolean finish;
    private boolean error;
    private String errorMessage;

    // Git metadata (optional)
    private String gitBranch;
    private String gitOldCommit;
    private String gitNewCommit;

    // 实时统计
    public int addClassCount;
    public int updateClassCount;
    public int deleteClassCount;
    public int addMethodCount;
    public int updateMethodCount;
    public int deleteMethodCount;

    // 差异项.勿略序列化
    private transient List<CompareResult> differences;

    public CompareJobVo() {
    }

    public CompareJobVo(String sourceFile, String targetFile) {
        this.sourceFile = sourceFile;
        this.targetFile = targetFile;
        begin = new Date();
        finish = false;
    }

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAddClassCount() {
        return addClassCount;
    }

    public void setAddClassCount(int addClassCount) {
        this.addClassCount = addClassCount;
    }

    public int getAddMethodCount() {
        return addMethodCount;
    }

    public void setAddMethodCount(int addMethodCount) {
        this.addMethodCount = addMethodCount;
    }

    public Date getBegin() {
        return begin;
    }

    public void setBegin(Date begin) {
        this.begin = begin;
    }

    public int getDeleteClassCount() {
        return deleteClassCount;
    }

    public void setDeleteClassCount(int deleteClassCount) {
        this.deleteClassCount = deleteClassCount;
    }

    public int getDeleteMethodCount() {
        return deleteMethodCount;
    }

    public void setDeleteMethodCount(int deleteMethodCount) {
        this.deleteMethodCount = deleteMethodCount;
    }

    public Date getEnd() {
        return end;
    }

    public void setEnd(Date end) {
        this.end = end;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLog() {
        return log;
    }

    public void setLog(String log) {
        this.log = log;
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

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public String getTargetFile() {
        return targetFile;
    }

    public void setTargetFile(String targetFile) {
        this.targetFile = targetFile;
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

    public int getUpdateClassCount() {
        return updateClassCount;
    }

    public void setUpdateClassCount(int updateClassCount) {
        this.updateClassCount = updateClassCount;
    }

    public int getUpdateMethodCount() {
        return updateMethodCount;
    }

    public void setUpdateMethodCount(int updateMethodCount) {
        this.updateMethodCount = updateMethodCount;
    }


    public boolean isFinish() {
        return finish;
    }

    public void setFinish(boolean finish) {
        this.finish = finish;
    }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<CompareResult> getDifferences() {
        return differences;
    }

    public void setDifferences(List<CompareResult> differences) {
        this.differences = differences;
    }

    @Override
    public String toString() {
        return "CompareJobVo{" +
               "id='" + id + '\'' +
               ", sourceFile='" + sourceFile + '\'' +
               ", targetFile='" + targetFile + '\'' +
               ", begin=" + begin +
               ", finish=" + finish +
               '}';
    }

}
