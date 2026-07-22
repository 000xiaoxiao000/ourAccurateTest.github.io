package com.oAT.web.verification.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Stable symbol identities are the join key between source revisions, graph facts and runtime evidence.
 */
public final class GraphModels {
    private GraphModels() {
    }

    public enum SnapshotKind { STATIC, STATIC_CFG, STATIC_DEPENDENCY, RUNTIME, RUNTIME_BRANCH, RUNTIME_TRACE, TRACEABILITY }
    public enum GraphNodeKind {
        REQUIREMENT, ACCEPTANCE_CRITERION, TESTCASE, TEST_STEP, TEST_EXECUTION,
        SOURCE_FILE, TYPE, METHOD, FIELD, ENDPOINT, CONFIG, SQL_STATEMENT,
        BASIC_BLOCK, DECISION, BRANCH, RUNTIME_SPAN, COVERAGE_UNIT
    }
    public enum GraphEdgeType {
        HAS_AC, VERIFIED_BY, EXECUTED_AS, IMPLEMENTED_BY, EXERCISES, TOUCHED, COVERED,
        DECLARES, CONTAINS, IMPORTS, EXTENDS, IMPLEMENTS, INJECTS, CALLS_STATIC,
        CALLS_RUNTIME, OVERRIDES, READS, WRITES, ROUTES_TO, QUERIES, CONFIGURES,
        PUBLISHES, CONSUMES, NORMAL, TRUE, FALSE, CASE, DEFAULT, LOOP_BACK,
        THROW, CATCH, FINALLY, RETURN
    }
    public enum EvidenceKind {
        STATIC_RESOLVED, STATIC_POSSIBLE, STATIC_UNRESOLVED,
        DYNAMIC_TRACE, COVERAGE, DOCUMENT_LINK, AI_CANDIDATE, DERIVED, MANUAL
    }
    public enum FusionState { REACHABLE_NOT_EXECUTED, EXECUTED_CONFIRMED, NOT_OBSERVABLE }

    public record SymbolIdentity(
            String repository,
            String commit,
            String path,
            String owner,
            String member,
            String parameterTypes,
            String returnType) {

        public SymbolIdentity {
            repository = require("repository", repository);
            commit = require("commit", commit);
            path = normalizePath(require("path", path));
            owner = require("owner", owner);
            member = normalize(member);
            parameterTypes = normalize(parameterTypes);
            returnType = normalize(returnType);
        }

        public String stableId() {
            String signature = member.isEmpty() ? owner : owner + "#" + member + "(" + parameterTypes + "):" + returnType;
            return repository + "@" + commit + ":" + path + ":" + signature;
        }

        public String logicalId() {
            String signature = member.isEmpty() ? owner : owner + "#" + member + "(" + parameterTypes + "):" + returnType;
            return repository + ":" + path + ":" + signature;
        }

        public String fingerprint() {
            return sha256(stableId());
        }
    }

    public static String fingerprint(String value) {
        return sha256(value);
    }

    public static String edgeId(String snapshotId, String sourceNodeId, String targetNodeId,
                                GraphEdgeType edgeType, EvidenceKind evidenceKind, String executionId) {
        return sha256(String.join("|", normalize(snapshotId), normalize(sourceNodeId), normalize(targetNodeId),
                edgeType.name(), evidenceKind.name(), normalize(executionId)));
    }

    private static String require(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizePath(String value) {
        return value.replace('\\', '/').replaceAll("/+", "/");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
