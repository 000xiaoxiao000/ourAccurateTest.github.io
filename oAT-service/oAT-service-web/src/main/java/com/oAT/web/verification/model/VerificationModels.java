package com.oAT.web.verification.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class VerificationModels {
    public static final String ANALYZER_VERSION = "1.0.0";

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
            double closedLoopRate) {
    }

    public record GateResult(
            String id,
            String baselineId,
            GateStatus status,
            Map<String, Object> policy,
            Metrics metrics,
            List<String> reasons,
            LocalDateTime createTime) {
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

    public enum AssetType { REQUIREMENT, TESTCASE, SOURCE, EXECUTION, COVERAGE, DEFECT }
    public enum SourceType { FILE, GIT, API, AGENT, PASTE }
    public enum Freshness { LIVE, SNAPSHOT, MANUAL, STALE, UNKNOWN }
    public enum BaselineStatus { CREATED, ANALYZING, WAITING_REVIEW, COMPLETED, FAILED, STALE }
    public enum EvidenceLevel { E0, E1, E2, E3, E4 }
    public enum ReviewStatus { PENDING, CONFIRMED, REJECTED, WRITTEN_BACK, STALE, EXEMPTED }
    public enum Perspective { PRODUCT, TEST, DEVELOPMENT, CROSS }
    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
    public enum Verdict { SATISFIED, STATICALLY_CONSISTENT, PARTIAL, NOT_SATISFIED, AMBIGUOUS, NOT_VERIFIABLE, EXEMPTED, STALE }
    public enum GateStatus { PASSED, WARNING, FAILED }
}
