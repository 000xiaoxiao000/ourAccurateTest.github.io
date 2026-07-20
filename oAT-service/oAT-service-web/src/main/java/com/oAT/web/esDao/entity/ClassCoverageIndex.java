package com.oAT.web.esDao.entity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ClassCoverageIndex implements Serializable {
    private String id;
    private String reportId;
    private String appId;
    private String className;
    private String sourceType;
    private String language;
    private String displayName;
    private String sourcePath;
    private int totalMethods;
    private int coveredMethods;
    private int totalBranches;
    private int coveredBranches;
    private int totalBranchTargets;
    private int coveredBranchTargets;
    private int totalLines;
    private int coveredLines;
    private int totalComplexity;
    private Double lineRate;
    private Double branchRate;
    private Double methodRate;
    private List<Integer> coveredLineNumbers = new ArrayList<>();
    private List<Integer> totalLineNumbers = new ArrayList<>();
    private List<MethodCoverageDetail> methods = new ArrayList<>();
    private Boolean hasCodeChanges;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public int getTotalMethods() { return totalMethods; }
    public void setTotalMethods(int totalMethods) { this.totalMethods = totalMethods; }
    public int getCoveredMethods() { return coveredMethods; }
    public void setCoveredMethods(int coveredMethods) { this.coveredMethods = coveredMethods; }
    public int getTotalBranches() { return totalBranches; }
    public void setTotalBranches(int totalBranches) { this.totalBranches = totalBranches; }
    public int getCoveredBranches() { return coveredBranches; }
    public void setCoveredBranches(int coveredBranches) { this.coveredBranches = coveredBranches; }
    public int getTotalBranchTargets() { return totalBranchTargets; }
    public void setTotalBranchTargets(int totalBranchTargets) { this.totalBranchTargets = totalBranchTargets; }
    public int getCoveredBranchTargets() { return coveredBranchTargets; }
    public void setCoveredBranchTargets(int coveredBranchTargets) { this.coveredBranchTargets = coveredBranchTargets; }
    public int getTotalLines() { return totalLines; }
    public void setTotalLines(int totalLines) { this.totalLines = totalLines; }
    public int getCoveredLines() { return coveredLines; }
    public void setCoveredLines(int coveredLines) { this.coveredLines = coveredLines; }
    public int getTotalComplexity() { return totalComplexity; }
    public void setTotalComplexity(int totalComplexity) { this.totalComplexity = totalComplexity; }
    public Double getLineRate() { return lineRate; }
    public void setLineRate(Double lineRate) { this.lineRate = lineRate; }
    public Double getBranchRate() { return branchRate; }
    public void setBranchRate(Double branchRate) { this.branchRate = branchRate; }
    public Double getMethodRate() { return methodRate; }
    public void setMethodRate(Double methodRate) { this.methodRate = methodRate; }
    public List<Integer> getCoveredLineNumbers() { return coveredLineNumbers; }
    public void setCoveredLineNumbers(List<Integer> coveredLineNumbers) { this.coveredLineNumbers = coveredLineNumbers; }
    public List<Integer> getTotalLineNumbers() { return totalLineNumbers; }
    public void setTotalLineNumbers(List<Integer> totalLineNumbers) { this.totalLineNumbers = totalLineNumbers; }
    public List<MethodCoverageDetail> getMethods() { return methods; }
    public void setMethods(List<MethodCoverageDetail> methods) { this.methods = methods; }
    public Boolean getHasCodeChanges() { return hasCodeChanges; }
    public void setHasCodeChanges(Boolean hasCodeChanges) { this.hasCodeChanges = hasCodeChanges; }

    public static class MethodCoverageDetail implements Serializable {
        private String methodName;
        private String methodDesc;
        private int totalLines;
        private int coveredLines;
        private int totalBranches;
        private int coveredBranches;
        private int complexity;
        private boolean covered;
        private List<Integer> coveredLineNumbers = new ArrayList<>();
        private List<Integer> totalLineNumbers = new ArrayList<>();
        private Map<Integer, List<CoverageFootprintRecord>> lineFootprints;
        private List<Integer> coveredBranchLines = new ArrayList<>();
        private Map<String, List<Integer>> totalBranchTargetProbeMap;
        private Map<String, List<Integer>> coveredBranchTargetProbeMap;
        private Map<String, List<CoverageFootprintRecord>> branchFootprints;
        private int totalBranchTargets;
        private int coveredBranchTargets;
        private Double branchRate;
        private boolean hasCodeChanges;

        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public String getMethodDesc() { return methodDesc; }
        public void setMethodDesc(String methodDesc) { this.methodDesc = methodDesc; }
        public int getTotalLines() { return totalLines; }
        public void setTotalLines(int totalLines) { this.totalLines = totalLines; }
        public int getCoveredLines() { return coveredLines; }
        public void setCoveredLines(int coveredLines) { this.coveredLines = coveredLines; }
        public int getTotalBranches() { return totalBranches; }
        public void setTotalBranches(int totalBranches) { this.totalBranches = totalBranches; }
        public int getCoveredBranches() { return coveredBranches; }
        public void setCoveredBranches(int coveredBranches) { this.coveredBranches = coveredBranches; }
        public int getComplexity() { return complexity; }
        public void setComplexity(int complexity) { this.complexity = complexity; }
        public boolean isCovered() { return covered; }
        public void setCovered(boolean covered) { this.covered = covered; }
        public List<Integer> getCoveredLineNumbers() { return coveredLineNumbers; }
        public void setCoveredLineNumbers(List<Integer> coveredLineNumbers) { this.coveredLineNumbers = coveredLineNumbers; }
        public List<Integer> getTotalLineNumbers() { return totalLineNumbers; }
        public void setTotalLineNumbers(List<Integer> totalLineNumbers) { this.totalLineNumbers = totalLineNumbers; }
        public Map<Integer, List<CoverageFootprintRecord>> getLineFootprints() { return lineFootprints; }
        public void setLineFootprints(Map<Integer, List<CoverageFootprintRecord>> lineFootprints) { this.lineFootprints = lineFootprints; }
        public List<Integer> getCoveredBranchLines() { return coveredBranchLines; }
        public void setCoveredBranchLines(List<Integer> coveredBranchLines) { this.coveredBranchLines = coveredBranchLines; }
        public Map<String, List<Integer>> getTotalBranchTargetProbeMap() { return totalBranchTargetProbeMap; }
        public void setTotalBranchTargetProbeMap(Map<String, List<Integer>> totalBranchTargetProbeMap) { this.totalBranchTargetProbeMap = totalBranchTargetProbeMap; }
        public Map<String, List<Integer>> getCoveredBranchTargetProbeMap() { return coveredBranchTargetProbeMap; }
        public void setCoveredBranchTargetProbeMap(Map<String, List<Integer>> coveredBranchTargetProbeMap) { this.coveredBranchTargetProbeMap = coveredBranchTargetProbeMap; }
        public Map<String, List<CoverageFootprintRecord>> getBranchFootprints() { return branchFootprints; }
        public void setBranchFootprints(Map<String, List<CoverageFootprintRecord>> branchFootprints) { this.branchFootprints = branchFootprints; }
        public int getTotalBranchTargets() { return totalBranchTargets; }
        public void setTotalBranchTargets(int totalBranchTargets) { this.totalBranchTargets = totalBranchTargets; }
        public int getCoveredBranchTargets() { return coveredBranchTargets; }
        public void setCoveredBranchTargets(int coveredBranchTargets) { this.coveredBranchTargets = coveredBranchTargets; }
        public Double getBranchRate() { return branchRate; }
        public void setBranchRate(Double branchRate) { this.branchRate = branchRate; }
        public boolean isHasCodeChanges() { return hasCodeChanges; }
        public void setHasCodeChanges(boolean hasCodeChanges) { this.hasCodeChanges = hasCodeChanges; }
    }

    public static class CoverageFootprintRecord implements Serializable {
        private String traceId;
        private String caseName;
        private String testStage;
        private String buildId;
        private Long timestamp;

        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
        public String getCaseName() { return caseName; }
        public void setCaseName(String caseName) { this.caseName = caseName; }
        public String getTestStage() { return testStage; }
        public void setTestStage(String testStage) { this.testStage = testStage; }
        public String getBuildId() { return buildId; }
        public void setBuildId(String buildId) { this.buildId = buildId; }
        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

        public static CoverageFootprintRecord of(String traceId, String caseName, String testStage, String buildId, Long timestamp) {
            CoverageFootprintRecord record = new CoverageFootprintRecord();
            record.traceId = traceId;
            record.caseName = caseName;
            record.testStage = testStage;
            record.buildId = buildId;
            record.timestamp = timestamp;
            return record;
        }

        public static List<CoverageFootprintRecord> listOf(CoverageFootprintRecord record) {
            return record == null ? List.of() : List.of(record);
        }
    }
}
