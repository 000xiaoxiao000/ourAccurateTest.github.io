package com.oAT.web.verification.graph;

import com.oAT.web.persistence.ClassCoverageIndexRepository;
import com.oAT.web.persistence.entity.ClassCoverageIndex;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.GraphModels.FusionState;
import com.oAT.web.verification.model.GraphModels.GraphNodeKind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives the static/dynamic fusion view without duplicating storage. For every static METHOD node it computes
 * a tri-state by joining static reachability against dynamic evidence:
 *  - EXECUTED_CONFIRMED: dynamic evidence (coverage / runtime call / touched) proves execution
 *  - REACHABLE_NOT_EXECUTED: statically reachable but no dynamic evidence in this baseline
 *  - NOT_OBSERVABLE: neither statically reachable nor dynamically evidenced (e.g. dead or unobservable)
 */
@Service
public class FusionViewService {
    private final GraphRepository graphRepository;
    private final VerificationRepository verificationRepository;
    private final ClassCoverageIndexRepository coverageRepository;

    public FusionViewService(GraphRepository graphRepository, VerificationRepository verificationRepository,
                             ClassCoverageIndexRepository coverageRepository) {
        this.graphRepository = graphRepository;
        this.verificationRepository = verificationRepository;
        this.coverageRepository = coverageRepository;
    }

    public FusionView build(String projectId, String baselineId, int maxNodes) {
        verificationRepository.findBaseline(projectId, baselineId)
                .orElseThrow(() -> new IllegalArgumentException("分析基线不存在或不属于当前项目"));
        List<GraphRepository.GraphNode> methods = graphRepository.findActiveNodesByKind(baselineId, GraphNodeKind.METHOD);
        Set<String> executed = new HashSet<>(graphRepository.findDynamicallyEvidencedNodeIds(baselineId));
        List<CoveredMethod> coveredMethods = findCoveredMethods(projectId, baselineId);
        Set<String> reachable = new HashSet<>(graphRepository.findStaticallyReachableNodeIds(baselineId));

        List<FusionNode> nodes = new ArrayList<>();
        int executedConfirmed = 0;
        int reachableNotExecuted = 0;
        int notObservable = 0;
        int limit = maxNodes <= 0 ? 1_000 : Math.min(maxNodes, 5_000);
        for (GraphRepository.GraphNode method : methods) {
            FusionState state;
            if (executed.contains(method.id()) || coveredByAsset(method, coveredMethods)) {
                state = FusionState.EXECUTED_CONFIRMED;
                executedConfirmed++;
            } else if (reachable.contains(method.id())) {
                state = FusionState.REACHABLE_NOT_EXECUTED;
                reachableNotExecuted++;
            } else {
                state = FusionState.NOT_OBSERVABLE;
                notObservable++;
            }
            if (nodes.size() < limit) {
                nodes.add(new FusionNode(method.id(), method.stableSymbolId(), method.displayName(), method.locator(), state.name()));
            }
        }
        boolean clipped = methods.size() > limit;
        return new FusionView(nodes, methods.size(), executedConfirmed, reachableNotExecuted, notObservable, clipped,
                clipped ? "融合节点超过上限 " + limit + " 已裁剪，请按聚焦符号或缩小范围再查询" : null);
    }

    /**
     * The coverage tab reads the baseline coverage asset directly. Include the
     * same covered methods here even when the graph projection has not been run.
     */
    private List<CoveredMethod> findCoveredMethods(String projectId, String baselineId) {
        if (coverageRepository == null) return List.of();
        var baseline = verificationRepository.findBaseline(projectId, baselineId).orElse(null);
        if (baseline == null || baseline.coverageAssetId() == null || baseline.coverageAssetId().isBlank()) {
            return List.of();
        }
        List<CoveredMethod> methods = new ArrayList<>();
        for (ClassCoverageIndex file : coverageRepository.findByReportId(baseline.coverageAssetId())) {
            if (file.getMethods() == null) continue;
            for (ClassCoverageIndex.MethodCoverageDetail method : file.getMethods()) {
                if (method == null || (!method.isCovered() && method.getCoveredLines() <= 0
                        && method.getCoveredBranches() <= 0 && method.getCoveredBranchTargets() <= 0)
                        || method.getMethodName() == null || method.getMethodName().isBlank()) continue;
                methods.add(new CoveredMethod(value(method.getClassName(), file.getClassName()),
                        method.getMethodName(), file.getSourcePath(), method.getStartLine()));
            }
        }
        return methods;
    }

    private boolean coveredByAsset(GraphRepository.GraphNode method, List<CoveredMethod> coveredMethods) {
        MethodIdentity identity = methodIdentity(method);
        return identity != null && coveredMethods.stream().anyMatch(covered -> covered.matches(identity));
    }

    private MethodIdentity methodIdentity(GraphRepository.GraphNode method) {
        String symbol = value(method.stableSymbolId(), method.logicalSymbolId());
        int hash = symbol.lastIndexOf('#');
        if (hash < 0) return null;
        int memberEnd = symbol.indexOf('(', hash);
        if (memberEnd < 0) memberEnd = symbol.indexOf(':', hash);
        if (memberEnd < 0) memberEnd = symbol.length();
        String owner = symbol.substring(0, hash);
        int symbolSeparator = owner.lastIndexOf(':');
        if (symbolSeparator >= 0) owner = owner.substring(symbolSeparator + 1);
        return new MethodIdentity(owner, symbol.substring(hash + 1, memberEnd), method.locator(), methodLine(method));
    }

    /**
     * Coverage reports use a binary class name while the graph stores a repository-qualified
     * symbol. Owner and method name are the stable cross-source join.
     */
    private record CoveredMethod(String className, String methodName, String sourcePath, int startLine) {
        boolean matches(MethodIdentity method) {
            if (!methodName.equalsIgnoreCase(method.name())) return false;
            if (sameOwner(className, method.owner())) return true;
            return samePath(sourcePath, method.locator())
                    && (startLine <= 0 || method.line() <= 0 || startLine == method.line());
        }
    }

    private record MethodIdentity(String owner, String name, String locator, int line) {}

    private int methodLine(GraphRepository.GraphNode method) {
        Object line = method.attributes() == null ? null : method.attributes().get("line");
        if (line instanceof Number number) return number.intValue();
        String locator = method.locator() == null ? "" : method.locator();
        int separator = locator.lastIndexOf(':');
        if (separator < 0) return 0;
        try {
            return Integer.parseInt(locator.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean sameOwner(String left, String right) {
        Set<String> leftVariants = ownerVariants(left);
        Set<String> rightVariants = ownerVariants(right);
        return leftVariants.stream().anyMatch(rightVariants::contains);
    }

    private static Set<String> ownerVariants(String owner) {
        String normalized = owner == null ? "" : owner.trim().toLowerCase(java.util.Locale.ROOT)
                .replace(" ", "").replace('/', '.').replace('$', '.');
        int classSuffix = normalized.indexOf(".class");
        if (classSuffix >= 0) normalized = normalized.substring(0, classSuffix);
        Set<String> variants = new HashSet<>();
        variants.add(normalized);
        int packageSeparator = normalized.lastIndexOf('.');
        if (packageSeparator >= 0) variants.add(normalized.substring(packageSeparator + 1));
        return variants;
    }

    private static boolean samePath(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return false;
        String normalizedLeft = left.trim().toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
        String normalizedRight = right.trim().toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
        int lineSeparator = normalizedRight.lastIndexOf(':');
        if (lineSeparator > normalizedRight.lastIndexOf('/')) normalizedRight = normalizedRight.substring(0, lineSeparator);
        return normalizedLeft.endsWith(normalizedRight) || normalizedRight.endsWith(normalizedLeft);
    }

    private String value(String first, String fallback) {
        return first == null || first.isBlank() ? (fallback == null ? "" : fallback) : first;
    }

    public record FusionNode(String nodeId, String stableSymbolId, String displayName, String locator, String fusionState) {}

    public record FusionView(List<FusionNode> nodes, int totalMethods, int executedConfirmed, int reachableNotExecuted,
                             int notObservable, boolean clipped, String clipReason) {}
}
