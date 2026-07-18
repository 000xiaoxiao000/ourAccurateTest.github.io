package com.oAT.web.verification.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class VerificationModels {
    public static final String ANALYZER_VERSION = "2.0.0";

    private VerificationModels() {
    }

    public record AssetSnapshot(
            String id,
            String projectId,
            AssetType assetType,
            SourceType sourceType,
            String externalId,
            String externalUrl,
            String sourceVersion,
            String fileName,
            String contentHash,
            String content,
            String storageType,
            String storageKey,
            long contentSize,
            String contentPreview,
            Map<String, Object> metadata,
            Freshness freshness,
            String importedBy,
            LocalDateTime capturedAt) {
    }

    public record Baseline(
            String id,
            String projectId,
            String name,
            String requirementAssetId,
            String testcaseAssetId,
            String sourceAssetId,
            String executionAssetId,
            String coverageAssetId,
            String sourceAppId,
            String repositoryUrl,
            String sourceBranch,
            String sourceCommit,
            String analyzerVersion,
            BaselineStatus status,
            Freshness freshness,
            String createdBy,
            LocalDateTime createTime,
            LocalDateTime updateTime) {
    }

    public record AcceptanceCriterion(
            String id,
            String baselineId,
            String requirementKey,
            String acKey,
            String title,
            String content,
            String sourceLocator,
            String priority,
            boolean testable,
            boolean ambiguity,
            double confidence) {
    }

    public record TestcaseProjection(
            String id,
            String baselineId,
            String externalKey,
            String title,
            String preconditions,
            String steps,
            String testData,
            String expected,
            String requirementRefs,
            String sourceLocator) {
    }

    public record TraceLink(
            String id,
            String baselineId,
            String sourceType,
            String sourceId,
            String targetType,
            String targetId,
            String relationType,
            String generationMethod,
            double confidence,
            EvidenceLevel evidenceLevel,
            ReviewStatus reviewStatus,
            Map<String, Object> evidence) {
    }

    public record Finding(
            String id,
            String baselineId,
            String acId,
            String findingType,
            Perspective perspective,
            Severity severity,
            String title,
            String description,
            String suggestion,
            double confidence,
            EvidenceLevel evidenceLevel,
            Verdict verdict,
            ReviewStatus reviewStatus,
            List<Map<String, Object>> evidence,
            String externalWorkItemUrl,
            String reviewedBy,
            String reviewReason) {
    }

    public record MatrixRow(
            AcceptanceCriterion criterion,
            List<TestcaseProjection> testcases,
            List<TraceLink> codeLinks,
            List<Finding> findings,
            Verdict verdict,
            EvidenceLevel evidenceLevel) {
    }

    public record BaselineDetail(
            Baseline baseline,
            AssetSnapshot requirementAsset,
            AssetSnapshot testcaseAsset,
            List<AcceptanceCriterion> criteria,
            List<TestcaseProjection> testcases,
            List<TraceLink> traceLinks,
            List<Finding> findings,
            Metrics metrics) {
    }

    public record Metrics(
            int totalCriteria,
            int coveredCriteria,
            int implementedCriteria,
            int executedCriteria,
            int coveredByRuntimeCriteria,
            int closedLoopCriteria,
            int openFindings,
            double testcaseCoverageRate,
            double implementationCoverageRate,
            double executionEvidenceRate,
            double runtimeCoverageRate,
            double closedLoopRate,
            int staticCodeCount,
            int dynamicCodeCount,
            int coverageFileCount,
            int coveredLines,
            int totalLines,
            double lineCoverageRate,
            int coveredBranches,
            int totalBranches,
            double branchCoverageRate) {
    }

    public record WriteBackAction(
            String id,
            String projectId,
            String baselineId,
            String findingId,
            String connectorType,
            String externalUrl,
            String status,
            String message,
            String createdBy,
            LocalDateTime createTime) {
    }

    public record AnalysisJob(
            String id,
            String projectId,
            String baselineId,
            AnalysisJobStatus status,
            String message,
            String createdBy,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            LocalDateTime finishTime) {
    }

    // ── Connector ────────────────────────────────────────────────────────────

    public record ConnectorConfig(
            String id,
            String projectId,
            String name,
            ConnectorType connectorType,
            String baseUrl,
            String credentialHint,
            Map<String, Object> fieldMapping,
            boolean writeBackEnabled,
            ConnectorStatus status,
            String createdBy,
            LocalDateTime createTime,
            LocalDateTime updateTime) {
    }

    // ── Change Impact ────────────────────────────────────────────────────────

    public record ChangeImpactReport(
            String id,
            String projectId,
            String baselineId,
            String changeDescription,
            List<ImpactedAc> impactedCriteria,
            List<ImpactedTestcase> impactedTestcases,
            List<OrphanItem> orphans,
            String createdBy,
            LocalDateTime createTime) {
    }

    public record ImpactedAc(
            String acId,
            String requirementKey,
            String acKey,
            String title,
            ImpactLevel impactLevel,
            String reason,
            List<String> affectedTestcaseIds,
            List<String> affectedSymbols) {
    }

    public record ImpactedTestcase(
            String testcaseId,
            String externalKey,
            String title,
            ImpactLevel impactLevel,
            String reason) {
    }

    public record OrphanItem(
            String itemId,
            OrphanType orphanType,
            String itemType,
            String title,
            String description) {
    }

    // ── Quality Gate ─────────────────────────────────────────────────────────

    public record QualityGatePolicy(
            String id,
            String projectId,
            String name,
            double minTestcaseCoverageRate,
            double minImplementationCoverageRate,
            int maxCriticalFindings,
            int maxHighFindings,
            boolean requireAllAmbiguitiesResolved,
            boolean requireChangeImpactVerified,
            boolean blockOnStaleBaseline,
            String createdBy,
            LocalDateTime createTime,
            LocalDateTime updateTime) {
    }

    public record QualityGateResult(
            String id,
            String projectId,
            String baselineId,
            String policyId,
            GateVerdict verdict,
            List<GateFailure> failures,
            List<GateExemption> activeExemptions,
            VerificationModels.Metrics metrics,
            String evaluatedBy,
            LocalDateTime evaluatedAt) {
    }

    public record GateFailure(
            String ruleId,
            String description,
            String actualValue,
            String threshold) {
    }

    public record GateExemption(
            String id,
            String projectId,
            String baselineId,
            String ruleId,
            String reason,
            String grantedBy,
            LocalDateTime expiresAt,
            LocalDateTime createTime) {
    }

    // ── Enums ────────────────────────────────────────────────────────────────

    public enum AssetType { REQUIREMENT, TESTCASE, SOURCE, EXECUTION, COVERAGE, DEFECT }
    public enum SourceType { FILE, GIT, API, AGENT, PASTE }
    public enum Freshness { LIVE, SNAPSHOT, MANUAL, STALE, UNKNOWN }
    public enum BaselineStatus { CREATED, ANALYZING, WAITING_REVIEW, COMPLETED, FAILED, STALE }
    public enum AnalysisJobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }
    public enum EvidenceLevel { E0, E1, E2, E3, E4 }
    public enum ReviewStatus { PENDING, CONFIRMED, REJECTED, WRITTEN_BACK, STALE, EXEMPTED }
    public enum Perspective { PRODUCT, TEST, DEVELOPMENT, CROSS }
    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
    public enum Verdict { SATISFIED, STATICALLY_CONSISTENT, PARTIAL, NOT_SATISFIED, AMBIGUOUS, NOT_VERIFIABLE, EXEMPTED, STALE }
    public enum ConnectorType { JIRA, TAPD, ZENTAO, PINGCODE, TESTLINK, TESTCASE_FILE, GIT, GENERIC_REST, LINK_ONLY }
    public enum ConnectorStatus { ACTIVE, INACTIVE, ERROR }
    public enum ImpactLevel { HIGH, MEDIUM, LOW, NONE }
    public enum OrphanType { NO_REQUIREMENT, NO_TESTCASE, NO_CODE, NO_DEFECT_FIX }
    public enum GateVerdict { PASSED, FAILED, WARNING, EXEMPTED }
}
