package com.oAT.web.verification.impact;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ImpactModels {
    public static final String ANALYZER_VERSION = "3.0.0";

    private ImpactModels() {
    }

    public enum FileChangeType { ADD, MODIFY, DELETE, RENAME, COPY }
    public enum SymbolKind { FILE, TYPE, METHOD, CONSTRUCTOR, FIELD, ENDPOINT, CONFIG, SQL }
    public enum SymbolChangeType { ADD, DELETE, MODIFY, MOVE, RENAME, SIGNATURE_CHANGE }
    public enum ChangeFacet { BODY, CONTROL_FLOW, CALL, FIELD, ANNOTATION, VISIBILITY, RETURN_TYPE, PARAMETER, EXCEPTION, CONSTANT, SQL, CONFIG }
    public enum EdgeType { DECLARES, CALLS, READS, WRITES, EXTENDS, IMPLEMENTS, INJECTS, ROUTES_TO, QUERIES, CONFIGURES }
    public enum ImpactDirection { UPSTREAM, DOWNSTREAM, TRACEABILITY }
    public enum ImpactClassification { DIRECT, TRANSITIVE, POSSIBLE, UNKNOWN }
    public enum LlmDecision { CONFIRM, REJECT, UNCERTAIN }

    public record LineRange(int startLine, int endLine) {
        public boolean intersects(LineRange other) {
            return other != null && startLine <= other.endLine && other.startLine <= endLine;
        }
    }

    public record FileChange(String oldPath, String newPath, FileChangeType changeType, int renameScore,
                             String oldBlobId, String newBlobId, List<LineRange> oldRanges, List<LineRange> newRanges,
                             boolean binary, String language) {
        public String effectivePath() { return newPath == null || newPath.equals("/dev/null") ? oldPath : newPath; }
    }

    public record ChangeSet(String repositoryUrl, String baseCommit, String headCommit, String mergeBase,
                            String analyzerVersion, LocalDateTime createdAt, List<FileChange> files) {
    }

    public record SymbolSnapshot(String key, SymbolKind kind, String language, String qualifiedName,
                                 String signature, String path, LineRange range, String astHash, String bodyHash,
                                 String apiHash, String snippet, List<String> invokedNames,
                                 List<String> superTypes,
                                 List<String> fieldTypes,
                                 List<String> injectAnnotatedFields) {

        /** Backward-compatible constructor for callers that do not yet supply the structural fields. */
        public SymbolSnapshot(String key, SymbolKind kind, String language, String qualifiedName,
                              String signature, String path, LineRange range, String astHash, String bodyHash,
                              String apiHash, String snippet, List<String> invokedNames) {
            this(key, kind, language, qualifiedName, signature, path, range, astHash, bodyHash, apiHash,
                    snippet, invokedNames, List.of(), List.of(), List.of());
        }
    }

    public record SymbolChange(String oldKey, String newKey, SymbolChangeType changeType,
                               List<ChangeFacet> facets, SymbolSnapshot oldSymbol, SymbolSnapshot newSymbol,
                               List<LineRange> evidenceRanges) {
        public String symbolKey() { return newKey != null ? newKey : oldKey; }
    }

    public record GraphEdge(String source, String target, EdgeType type, double confidence,
                            boolean dynamicEvidence, String evidence) {
    }

    public record ImpactPath(List<String> symbols, List<EdgeType> edgeTypes, double confidence) {
    }

    public record ImpactCandidate(String seedSymbol, String targetSymbol, ImpactDirection direction,
                                  int distance, ImpactClassification classification, double ruleScore,
                                  Double semanticScore, double confidence, String reason, ImpactPath path,
                                  Map<String, Object> evidence) {
    }

    public record LlmJudgement(String candidateId, LlmDecision decision, double confidence,
                               String businessReason, String riskLevel, List<String> recommendedTests,
                               List<String> evidenceIds) {
    }

    public record ImpactReport(String id, ChangeSet changeSet, List<SymbolChange> directChanges,
                               List<ImpactCandidate> candidates, List<LlmJudgement> llmJudgements,
                               LocalDateTime createdAt) {
    }
}
