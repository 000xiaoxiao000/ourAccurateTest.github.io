package com.oAT.web.api.version;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VersionApiPayloads {

    private VersionApiPayloads() {
    }

    public static class StartCompareRequest {
        private String mode;
        private String sourceFile;
        private String targetFile;
        private String packageName;
        private String branch;
        private String oldCommit;
        private String newCommit;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getSourceFile() { return sourceFile; }
        public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
        public String getTargetFile() { return targetFile; }
        public void setTargetFile(String targetFile) { this.targetFile = targetFile; }
        public String getPackageName() { return packageName; }
        public void setPackageName(String packageName) { this.packageName = packageName; }
        public String getBranch() { return branch; }
        public void setBranch(String branch) { this.branch = branch; }
        public String getOldCommit() { return oldCommit; }
        public void setOldCommit(String oldCommit) { this.oldCommit = oldCommit; }
        public String getNewCommit() { return newCommit; }
        public void setNewCommit(String newCommit) { this.newCommit = newCommit; }
    }

    public static class VersionCenterPayload {
        private AppSummary app;
        private List<AppSummary> apps;
        private String currentUserRole;
        private List<VersionItemSummary> versions;
        private List<VersionItemSummary> packageVersions;
        private List<CompareReportSummary> compareReports;

        public AppSummary getApp() { return app; }
        public void setApp(AppSummary app) { this.app = app; }
        public List<AppSummary> getApps() { return apps; }
        public void setApps(List<AppSummary> apps) { this.apps = apps; }
        public String getCurrentUserRole() { return currentUserRole; }
        public void setCurrentUserRole(String currentUserRole) { this.currentUserRole = currentUserRole; }
        public List<VersionItemSummary> getVersions() { return versions; }
        public void setVersions(List<VersionItemSummary> versions) { this.versions = versions; }
        public List<VersionItemSummary> getPackageVersions() { return packageVersions; }
        public void setPackageVersions(List<VersionItemSummary> packageVersions) { this.packageVersions = packageVersions; }
        public List<CompareReportSummary> getCompareReports() { return compareReports; }
        public void setCompareReports(List<CompareReportSummary> compareReports) { this.compareReports = compareReports; }
    }

    public static class CompareJobPayload {
        private String jobId;
        private CompareJobSummary job;

        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public CompareJobSummary getJob() { return job; }
        public void setJob(CompareJobSummary job) { this.job = job; }
    }

    public static class VersionReportDetailPayload {
        private String state;
        private String retryMessage;
        private AppSummary app;
        private CompareReportDetailSummary report;
        private List<DifferenceGroupSummary> differences;
        private List<UsecaseImpactSummary> usecases;
        private List<EndpointImpactSummary> endpoints;

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getRetryMessage() { return retryMessage; }
        public void setRetryMessage(String retryMessage) { this.retryMessage = retryMessage; }
        public AppSummary getApp() { return app; }
        public void setApp(AppSummary app) { this.app = app; }
        public CompareReportDetailSummary getReport() { return report; }
        public void setReport(CompareReportDetailSummary report) { this.report = report; }
        public List<DifferenceGroupSummary> getDifferences() { return differences; }
        public void setDifferences(List<DifferenceGroupSummary> differences) { this.differences = differences; }
        public List<UsecaseImpactSummary> getUsecases() { return usecases; }
        public void setUsecases(List<UsecaseImpactSummary> usecases) { this.usecases = usecases; }
        public List<EndpointImpactSummary> getEndpoints() { return endpoints; }
        public void setEndpoints(List<EndpointImpactSummary> endpoints) { this.endpoints = endpoints; }
    }

    public static class AppSummary {
        private String id;
        private String name;
        private String currentVersion;
        private String currentBranch;
        private String currentCommitId;
        private boolean repoConfigured;
        private String sourceType;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCurrentVersion() { return currentVersion; }
        public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }
        public String getCurrentBranch() { return currentBranch; }
        public void setCurrentBranch(String currentBranch) { this.currentBranch = currentBranch; }
        public String getCurrentCommitId() { return currentCommitId; }
        public void setCurrentCommitId(String currentCommitId) { this.currentCommitId = currentCommitId; }
        public boolean isRepoConfigured() { return repoConfigured; }
        public void setRepoConfigured(boolean repoConfigured) { this.repoConfigured = repoConfigured; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    }

    public static class VersionItemSummary {
        private String id;
        private String versionNumber;
        private String describe;
        private String programFile;
        private String programName;
        private String sourceType;
        private String repoBranch;
        private String repoCommitId;
        private String createTimeText;
        private String createTimeRelativeText;
        private boolean fileExist;
        private boolean hasReport;
        private boolean current;
        private List<String> rawCoverageSourceTypes = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getVersionNumber() { return versionNumber; }
        public void setVersionNumber(String versionNumber) { this.versionNumber = versionNumber; }
        public String getDescribe() { return describe; }
        public void setDescribe(String describe) { this.describe = describe; }
        public String getProgramFile() { return programFile; }
        public void setProgramFile(String programFile) { this.programFile = programFile; }
        public String getProgramName() { return programName; }
        public void setProgramName(String programName) { this.programName = programName; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
        public String getRepoBranch() { return repoBranch; }
        public void setRepoBranch(String repoBranch) { this.repoBranch = repoBranch; }
        public String getRepoCommitId() { return repoCommitId; }
        public void setRepoCommitId(String repoCommitId) { this.repoCommitId = repoCommitId; }
        public String getCreateTimeText() { return createTimeText; }
        public void setCreateTimeText(String createTimeText) { this.createTimeText = createTimeText; }
        public String getCreateTimeRelativeText() { return createTimeRelativeText; }
        public void setCreateTimeRelativeText(String createTimeRelativeText) { this.createTimeRelativeText = createTimeRelativeText; }
        public boolean isFileExist() { return fileExist; }
        public void setFileExist(boolean fileExist) { this.fileExist = fileExist; }
        public boolean isHasReport() { return hasReport; }
        public void setHasReport(boolean hasReport) { this.hasReport = hasReport; }
        public boolean isCurrent() { return current; }
        public void setCurrent(boolean current) { this.current = current; }
        public List<String> getRawCoverageSourceTypes() { return rawCoverageSourceTypes; }
        public void setRawCoverageSourceTypes(List<String> rawCoverageSourceTypes) { this.rawCoverageSourceTypes = rawCoverageSourceTypes; }
    }

    public static class CompareReportSummary {
        private String id;
        private String name;
        private String createTimeText;
        private String createTimeRelativeText;
        private String sourceVersion;
        private String targetVersion;
        private String gitBranch;
        private String gitOldCommit;
        private String gitNewCommit;
        private int addClassCount;
        private int updateClassCount;
        private int deleteClassCount;
        private int addMethodCount;
        private int updateMethodCount;
        private int deleteMethodCount;
        private int impactCaseCount;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCreateTimeText() { return createTimeText; }
        public void setCreateTimeText(String createTimeText) { this.createTimeText = createTimeText; }
        public String getCreateTimeRelativeText() { return createTimeRelativeText; }
        public void setCreateTimeRelativeText(String createTimeRelativeText) { this.createTimeRelativeText = createTimeRelativeText; }
        public String getSourceVersion() { return sourceVersion; }
        public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }
        public String getTargetVersion() { return targetVersion; }
        public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }
        public String getGitBranch() { return gitBranch; }
        public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }
        public String getGitOldCommit() { return gitOldCommit; }
        public void setGitOldCommit(String gitOldCommit) { this.gitOldCommit = gitOldCommit; }
        public String getGitNewCommit() { return gitNewCommit; }
        public void setGitNewCommit(String gitNewCommit) { this.gitNewCommit = gitNewCommit; }
        public int getAddClassCount() { return addClassCount; }
        public void setAddClassCount(int addClassCount) { this.addClassCount = addClassCount; }
        public int getUpdateClassCount() { return updateClassCount; }
        public void setUpdateClassCount(int updateClassCount) { this.updateClassCount = updateClassCount; }
        public int getDeleteClassCount() { return deleteClassCount; }
        public void setDeleteClassCount(int deleteClassCount) { this.deleteClassCount = deleteClassCount; }
        public int getAddMethodCount() { return addMethodCount; }
        public void setAddMethodCount(int addMethodCount) { this.addMethodCount = addMethodCount; }
        public int getUpdateMethodCount() { return updateMethodCount; }
        public void setUpdateMethodCount(int updateMethodCount) { this.updateMethodCount = updateMethodCount; }
        public int getDeleteMethodCount() { return deleteMethodCount; }
        public void setDeleteMethodCount(int deleteMethodCount) { this.deleteMethodCount = deleteMethodCount; }
        public int getImpactCaseCount() { return impactCaseCount; }
        public void setImpactCaseCount(int impactCaseCount) { this.impactCaseCount = impactCaseCount; }
    }

    public static class CompareJobSummary {
        private String id;
        private String name;
        private int progress;
        private String progressName;
        private boolean finish;
        private boolean error;
        private String errorMessage;
        private String log;
        private String sourceFile;
        private String targetFile;
        private String gitBranch;
        private String gitOldCommit;
        private String gitNewCommit;
        private int addClassCount;
        private int updateClassCount;
        private int deleteClassCount;
        private int addMethodCount;
        private int updateMethodCount;
        private int deleteMethodCount;
        private String beginTimeText;
        private String endTimeText;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getProgress() { return progress; }
        public void setProgress(int progress) { this.progress = progress; }
        public String getProgressName() { return progressName; }
        public void setProgressName(String progressName) { this.progressName = progressName; }
        public boolean isFinish() { return finish; }
        public void setFinish(boolean finish) { this.finish = finish; }
        public boolean isError() { return error; }
        public void setError(boolean error) { this.error = error; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getLog() { return log; }
        public void setLog(String log) { this.log = log; }
        public String getSourceFile() { return sourceFile; }
        public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
        public String getTargetFile() { return targetFile; }
        public void setTargetFile(String targetFile) { this.targetFile = targetFile; }
        public String getGitBranch() { return gitBranch; }
        public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }
        public String getGitOldCommit() { return gitOldCommit; }
        public void setGitOldCommit(String gitOldCommit) { this.gitOldCommit = gitOldCommit; }
        public String getGitNewCommit() { return gitNewCommit; }
        public void setGitNewCommit(String gitNewCommit) { this.gitNewCommit = gitNewCommit; }
        public int getAddClassCount() { return addClassCount; }
        public void setAddClassCount(int addClassCount) { this.addClassCount = addClassCount; }
        public int getUpdateClassCount() { return updateClassCount; }
        public void setUpdateClassCount(int updateClassCount) { this.updateClassCount = updateClassCount; }
        public int getDeleteClassCount() { return deleteClassCount; }
        public void setDeleteClassCount(int deleteClassCount) { this.deleteClassCount = deleteClassCount; }
        public int getAddMethodCount() { return addMethodCount; }
        public void setAddMethodCount(int addMethodCount) { this.addMethodCount = addMethodCount; }
        public int getUpdateMethodCount() { return updateMethodCount; }
        public void setUpdateMethodCount(int updateMethodCount) { this.updateMethodCount = updateMethodCount; }
        public int getDeleteMethodCount() { return deleteMethodCount; }
        public void setDeleteMethodCount(int deleteMethodCount) { this.deleteMethodCount = deleteMethodCount; }
        public String getBeginTimeText() { return beginTimeText; }
        public void setBeginTimeText(String beginTimeText) { this.beginTimeText = beginTimeText; }
        public String getEndTimeText() { return endTimeText; }
        public void setEndTimeText(String endTimeText) { this.endTimeText = endTimeText; }
    }

    public static class CompareReportDetailSummary {
        private String jobId;
        private String projectId;
        private String appId;
        private String jobName;
        private String sourceVersion;
        private String targetVersion;
        private String gitBranch;
        private String gitOldCommit;
        private String gitNewCommit;
        private String createTimeText;
        private int addClassCount;
        private int updateClassCount;
        private int deleteClassCount;
        private int addMethodCount;
        private int updateMethodCount;
        private int deleteMethodCount;
        private int impactCaseCount;
        private String jobLog;

        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getJobName() { return jobName; }
        public void setJobName(String jobName) { this.jobName = jobName; }
        public String getSourceVersion() { return sourceVersion; }
        public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }
        public String getTargetVersion() { return targetVersion; }
        public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }
        public String getGitBranch() { return gitBranch; }
        public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }
        public String getGitOldCommit() { return gitOldCommit; }
        public void setGitOldCommit(String gitOldCommit) { this.gitOldCommit = gitOldCommit; }
        public String getGitNewCommit() { return gitNewCommit; }
        public void setGitNewCommit(String gitNewCommit) { this.gitNewCommit = gitNewCommit; }
        public String getCreateTimeText() { return createTimeText; }
        public void setCreateTimeText(String createTimeText) { this.createTimeText = createTimeText; }
        public int getAddClassCount() { return addClassCount; }
        public void setAddClassCount(int addClassCount) { this.addClassCount = addClassCount; }
        public int getUpdateClassCount() { return updateClassCount; }
        public void setUpdateClassCount(int updateClassCount) { this.updateClassCount = updateClassCount; }
        public int getDeleteClassCount() { return deleteClassCount; }
        public void setDeleteClassCount(int deleteClassCount) { this.deleteClassCount = deleteClassCount; }
        public int getAddMethodCount() { return addMethodCount; }
        public void setAddMethodCount(int addMethodCount) { this.addMethodCount = addMethodCount; }
        public int getUpdateMethodCount() { return updateMethodCount; }
        public void setUpdateMethodCount(int updateMethodCount) { this.updateMethodCount = updateMethodCount; }
        public int getDeleteMethodCount() { return deleteMethodCount; }
        public void setDeleteMethodCount(int deleteMethodCount) { this.deleteMethodCount = deleteMethodCount; }
        public int getImpactCaseCount() { return impactCaseCount; }
        public void setImpactCaseCount(int impactCaseCount) { this.impactCaseCount = impactCaseCount; }
        public String getJobLog() { return jobLog; }
        public void setJobLog(String jobLog) { this.jobLog = jobLog; }
    }

    public static class DifferenceGroupSummary {
        private String className;
        private String model;
        private List<MethodDifferenceSummary> methods;

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public List<MethodDifferenceSummary> getMethods() { return methods; }
        public void setMethods(List<MethodDifferenceSummary> methods) { this.methods = methods; }
    }

    public static class MethodDifferenceSummary {
        private String className;
        private String methodName;
        private String methodDesc;
        private String model;

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public String getMethodDesc() { return methodDesc; }
        public void setMethodDesc(String methodDesc) { this.methodDesc = methodDesc; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class UsecaseImpactSummary {
        private String id;
        private String title;
        private String directoryPath;
        private String[] differences;
        private String[] labels;
        private boolean available = true;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDirectoryPath() { return directoryPath; }
        public void setDirectoryPath(String directoryPath) { this.directoryPath = directoryPath; }
        public String[] getDifferences() { return differences; }
        public void setDifferences(String[] differences) { this.differences = differences; }
        public String[] getLabels() { return labels; }
        public void setLabels(String[] labels) { this.labels = labels; }
        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
    }

    public static class EndpointImpactSummary {
        private String id;
        private String endpointType;
        private String url;
        private String httpMethod;
        private String className;
        private String methodName;
        private boolean covered;
        private int hitCount;
        private List<String> matchedClasses = Collections.emptyList();
        private List<String> matchedMethods = Collections.emptyList();
        private List<EndpointLinkedUsecaseSummary> linkedUsecases = Collections.emptyList();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEndpointType() { return endpointType; }
        public void setEndpointType(String endpointType) { this.endpointType = endpointType; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getHttpMethod() { return httpMethod; }
        public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public boolean isCovered() { return covered; }
        public void setCovered(boolean covered) { this.covered = covered; }
        public int getHitCount() { return hitCount; }
        public void setHitCount(int hitCount) { this.hitCount = hitCount; }
        public List<String> getMatchedClasses() { return matchedClasses; }
        public void setMatchedClasses(List<String> matchedClasses) { this.matchedClasses = matchedClasses; }
        public List<String> getMatchedMethods() { return matchedMethods; }
        public void setMatchedMethods(List<String> matchedMethods) { this.matchedMethods = matchedMethods; }
        public List<EndpointLinkedUsecaseSummary> getLinkedUsecases() { return linkedUsecases; }
        public void setLinkedUsecases(List<EndpointLinkedUsecaseSummary> linkedUsecases) { this.linkedUsecases = linkedUsecases; }
    }

    public static class EndpointLinkedUsecaseSummary {
        private String id;
        private String title;
        private String directory;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
    }

}
