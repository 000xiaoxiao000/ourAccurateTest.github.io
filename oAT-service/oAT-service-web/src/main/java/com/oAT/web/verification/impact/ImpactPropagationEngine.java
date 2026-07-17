package com.oAT.web.verification.impact;

import com.oAT.web.verification.impact.ImpactModels.*;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ImpactPropagationEngine {
    public List<ImpactCandidate> propagate(List<SymbolChange> changes, Collection<GraphEdge> edges, int maxDepth) {
        Map<String, List<GraphEdge>> reverseCalls = new HashMap<>();
        Map<String, List<GraphEdge>> forward = new HashMap<>();
        for (GraphEdge edge : edges) {
            forward.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            if (edge.type() == EdgeType.CALLS) reverseCalls.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
        }
        List<ImpactCandidate> result = new ArrayList<>();
        for (SymbolChange change : changes) {
            String seed = change.symbolKey();
            result.add(candidate(seed, seed, ImpactDirection.TRACEABILITY, 0, ImpactClassification.DIRECT, 1d, "结构化变更", List.of(seed), List.of()));
            walk(seed, seed, reverseCalls, true, maxDepth, result);
            if (requiresDownstream(change.facets())) walk(seed, seed, forward, false, maxDepth, result);
        }
        return deduplicate(result);
    }

    private void walk(String seed, String start, Map<String, List<GraphEdge>> index, boolean upstream, int maxDepth, List<ImpactCandidate> output) {
        record Step(String symbol, List<String> nodes, List<EdgeType> types, double confidence, int distance) { }
        ArrayDeque<Step> queue = new ArrayDeque<>();
        queue.add(new Step(start, List.of(start), List.of(), 1d, 0));
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Step current = queue.removeFirst();
            if (current.distance() >= maxDepth) continue;
            for (GraphEdge edge : index.getOrDefault(current.symbol(), List.of())) {
                String next = upstream ? edge.source() : edge.target();
                String visit = seed + "|" + next + "|" + (current.distance() + 1);
                if (!visited.add(visit)) continue;
                double confidence = current.confidence() * edge.confidence() * 0.78d;
                List<String> nodes = new ArrayList<>(current.nodes()); nodes.add(next);
                List<EdgeType> types = new ArrayList<>(current.types()); types.add(edge.type());
                int distance = current.distance() + 1;
                output.add(candidate(seed, next, upstream ? ImpactDirection.UPSTREAM : ImpactDirection.DOWNSTREAM, distance,
                        confidence >= 0.55d ? ImpactClassification.TRANSITIVE : ImpactClassification.POSSIBLE,
                        confidence, edge.evidence(), nodes, types));
                if (confidence >= 0.25d) queue.addLast(new Step(next, nodes, types, confidence, distance));
            }
        }
    }

    private ImpactCandidate candidate(String seed, String target, ImpactDirection direction, int distance,
                                      ImpactClassification classification, double score, String reason,
                                      List<String> symbols, List<EdgeType> edgeTypes) {
        return new ImpactCandidate(seed, target, direction, distance, classification, score, null, score, reason,
                new ImpactPath(symbols, edgeTypes, score), Map.of("rule", "bounded-graph-propagation"));
    }

    private boolean requiresDownstream(List<ChangeFacet> facets) {
        return facets.stream().anyMatch(f -> f == ChangeFacet.RETURN_TYPE || f == ChangeFacet.PARAMETER
                || f == ChangeFacet.EXCEPTION || f == ChangeFacet.FIELD || f == ChangeFacet.SQL || f == ChangeFacet.CONFIG);
    }

    private List<ImpactCandidate> deduplicate(List<ImpactCandidate> candidates) {
        Map<String, ImpactCandidate> selected = new LinkedHashMap<>();
        for (ImpactCandidate candidate : candidates) {
            String key = candidate.seedSymbol() + "|" + candidate.targetSymbol() + "|" + candidate.direction();
            ImpactCandidate existing = selected.get(key);
            if (existing == null || candidate.confidence() > existing.confidence()) selected.put(key, candidate);
        }
        return new ArrayList<>(selected.values());
    }
}
