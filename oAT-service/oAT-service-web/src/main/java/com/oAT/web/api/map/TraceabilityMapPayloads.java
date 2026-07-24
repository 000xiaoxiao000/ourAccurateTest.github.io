package com.oAT.web.api.map;

import com.oAT.web.verification.model.VerificationModels;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class TraceabilityMapPayloads {
    private TraceabilityMapPayloads() {
    }

    public record TraceabilityMapResponse(
            BaselineInfo baseline,
            TraceabilitySummary summary,
            List<TraceabilityNode> nodes,
            List<TraceabilityEdge> edges,
            List<CodeTreeNode> codeTree,
            CodeGraphData codeGraph,
            CoverageReportOverview coverageOverview,
            List<String> warnings) {
    }

    public record BaselineInfo(
            String id,
            String name,
            String sourceAppId,
            String sourceAssetId,
            String executionAssetId,
            String coverageAssetId,
            String repositoryUrl,
            String sourceBranch,
            String sourceCommit,
            String analyzerVersion,
            VerificationModels.Freshness freshness,
            LocalDateTime updateTime) {
    }

    public record TraceabilitySummary(
            int requirementCount,
            int testcaseCount,
            int codeCount,
            int codeFileCount,
            int codeClassCount,
            int codeMethodCount,
            int completeChainCount,
            double completeChainRate,
            int staticNodeCount,
            int dynamicNodeCount,
            int brokenRequirementCount,
            int brokenTestcaseCount,
            int dynamicEvidenceCount,
            int staticBridgeCount,
            boolean clipped) {
    }

    public record TraceabilityNode(
            String id,
            NodeKind kind,
            String label,
            String description,
            String locator,
            String layer,
            String language,
            String symbol,
            String parentId,
            EvidenceState evidenceState,
            CoverageSummary coverage,
            Map<String, Object> metadata) {
    }

    public record TraceabilityEdge(
            String id,
            String source,
            String target,
            Relation relation,
            String direction,
            EvidenceType evidenceType,
            CallEvidence callEvidence,
            double confidence,
            VerificationModels.EvidenceLevel evidenceLevel,
            String generationMethod,
            VerificationModels.ReviewStatus reviewStatus,
            List<EdgeEvidence> evidence) {
    }

    public record EdgeEvidence(
            String assetId,
            String traceId,
            String caseName,
            String locator,
            Integer line,
            String reason,
            Map<String, Object> metadata) {
    }

    public record CoverageSummary(
            Integer coveredLines,
            Integer totalLines,
            Double lineRate,
            Integer coveredBranches,
            Integer totalBranches,
            Double branchRate) {
    }

    public record CoverageReportOverview(
            int coveredClasses,
            int totalClasses,
            int coveredMethods,
            int totalMethods,
            int coveredBranches,
            int totalBranches,
            int coveredLines,
            int totalLines,
            int totalComplexity) {
    }

    public record CodeTreeNode(
            String id,
            CodeTreeKind kind,
            String label,
            String path,
            String parentId,
            String language,
            EvidenceState evidenceState,
            CoverageSummary coverage,
            List<CodeTreeNode> children) {
    }

    public record CodeGraphData(
            List<CodeDependency> dependencies,
            List<ControlFlowStep> controlFlows) {
    }

    public record CodeDependency(String source, String target, String kind) {
    }

    public record ControlFlowStep(String methodId, String methodLabel, String kind, String expression, int order) {
    }

    public enum NodeKind { REQUIREMENT, TESTCASE, CODE_FILE, CODE_CLASS, CODE_METHOD }
    public enum CodeTreeKind { DIRECTORY, FILE, CLASS, METHOD }
    public enum Relation { VERIFIED_BY, COVERS, IMPLEMENTED_BY, CALLS }
    public enum EvidenceType { DOCUMENT, AI, STATIC_ANALYSIS, COVERAGE, EXECUTION_TRACE, DERIVED }
    public enum CallEvidence { DYNAMIC_CONFIRMED, STATIC_BRIDGED, STATIC_ONLY }
    public enum EvidenceState { NONE, STATIC, DYNAMIC, BOTH }
}
